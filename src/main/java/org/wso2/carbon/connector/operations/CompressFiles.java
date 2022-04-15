/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.connector.operations;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Implements Compress Files operation.
 */
public class CompressFiles extends AbstractConnector {

    private static final String LOG_IDENTIFIER = "[FileConnector:compress] ";
    private static final String SOURCE_DIRECTORY_PATH = "sourceDirectoryPath";
    private static final String TARGET_FILE_PATH = "targetFilePath";
    private static final String INCLUDE_SUB_DIRECTORIES = "includeSubDirectories";
    private static final String NUMBER_OF_FILES_ADDED_ELEMENT = "NumberOfFilesAdded";
    private static final String OPERATION_NAME = "compress";
    private static final String ERROR_MESSAGE = "Error while performing file:compress for file/directory ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String sourceFilePath = null;
        FileObject fileToCompress = null;
        String targetZipFilePath = null;
        FileObject targetZipFile = null;
        boolean includeSubDirectories = true;
        FileOperationResult result;

        try {

            String connectionName = Utils.getConnectionName(messageContext);
            sourceFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_DIRECTORY_PATH);
            targetZipFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_FILE_PATH);

            if (StringUtils.isEmpty(sourceFilePath) || StringUtils.isEmpty(targetZipFilePath)) {
                throw new InvalidConfigurationException("Source or target file path is not provided ");
            }

            String includeSubDirectoriesAsStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, INCLUDE_SUB_DIRECTORIES);
            if (StringUtils.isNotEmpty(includeSubDirectoriesAsStr)) {
                includeSubDirectories = Boolean.parseBoolean(includeSubDirectoriesAsStr);
            }

            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            sourceFilePath = fileSystemHandler.getBaseDirectoryPath() + sourceFilePath;
            targetZipFilePath = fileSystemHandler.getBaseDirectoryPath() + targetZipFilePath;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            fileToCompress = fsManager.resolveFile(sourceFilePath, fso);

            if (!fileToCompress.exists()) {
                throw new IllegalPathException("File or directory to compress does not exist");
            }
            targetZipFile = fsManager.resolveFile(targetZipFilePath, fso);

            if(StringUtils.isEmpty(targetZipFile.getName().getExtension())) {
                throw new IllegalPathException("Target File path does not resolve to a file");
            }

            int numberOfCompressedFiles = compressFile(fileToCompress, targetZipFile, includeSubDirectories);
            OMElement compressedFilesEle =
                    Utils.createOMElement(NUMBER_OF_FILES_ADDED_ELEMENT,
                            Integer.toString(numberOfCompressedFiles));
            result = new FileOperationResult(OPERATION_NAME,
                    true,
                    compressedFilesEle);
            Utils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } finally {

            if (fileToCompress != null) {
                try {
                    fileToCompress.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing folder object while merging files in "
                            + fileToCompress);
                }
            }
        }

    }

    /**
     * Compresses files or folder.
     *
     * @param fileToCompress        File or folder to compress
     * @param targetZipFile         Zip file to create
     * @param includeSubDirectories True if to include sub-directories
     * @return How many files were added to compressed file
     * @throws IOException In case of error dealing with files
     */
    private int compressFile(FileObject fileToCompress,
                             FileObject targetZipFile, boolean includeSubDirectories) throws IOException {

        int numberOfFilesAddedToZip = 0;

        if (fileToCompress.isFolder()) {

            List<FileObject> fileList = new ArrayList<>();
            getAllFiles(fileToCompress, fileList, includeSubDirectories);
            writeZipFiles(fileToCompress, targetZipFile, fileList);
            numberOfFilesAddedToZip = fileList.size();

        } else {

            ZipOutputStream outputStream = null;
            InputStream fileIn = null;

            try {

                outputStream = new ZipOutputStream(targetZipFile.getContent().getOutputStream());
                fileIn = fileToCompress.getContent().getInputStream();
                ZipEntry zipEntry = new ZipEntry(fileToCompress.getName().getBaseName());
                outputStream.putNextEntry(zipEntry);
                final byte[] bytes = new byte[Const.ZIP_BUFFER_SIZE];
                int length;
                while ((length = fileIn.read(bytes)) != -1) {
                    outputStream.write(bytes, 0, length);
                }
                numberOfFilesAddedToZip = 1;

            } finally {

                try {
                    if (outputStream != null) {
                        outputStream.close();
                    }
                } catch (IOException e) {
                    log.error(LOG_IDENTIFIER + "Error while closing ZipOutputStream for file "
                            + targetZipFile.getURL(), e);
                }
                try {
                    if (fileIn != null) {
                        fileIn.close();
                    }
                } catch (IOException e) {
                    log.error(LOG_IDENTIFIER + "Error while closing InputStream "
                            + fileToCompress.getURL(), e);
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug(LOG_IDENTIFIER + "File archiving completed: " + targetZipFile.getURL());
        }

        return numberOfFilesAddedToZip;
    }

    /**
     * Get all files of the directory.
     *
     * @param dir                   Source directory
     * @param fileList              Container for file list
     * @param includeSubDirectories true if to include sub directories
     * @throws IOException In case of an error dealing with files
     */
    private void getAllFiles(FileObject dir, List<FileObject> fileList,
                             boolean includeSubDirectories) throws IOException {

        FileObject[] children = dir.getChildren();
        for (FileObject child : children) {
            fileList.add(child);
            if (child.getType() == FileType.FOLDER && includeSubDirectories) {
                getAllFiles(child, fileList, includeSubDirectories);
            }
        }
    }


    /**
     * Write files to the target zip file.
     *
     * @param fileToCompress Folder to compress
     * @param targetZipFile  Zip file to create
     * @param fileList       Files to compress and write
     * @throws IOException In case of file error
     */
    private void writeZipFiles(FileObject fileToCompress, FileObject targetZipFile,
                               List<FileObject> fileList) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(targetZipFile.getContent().getOutputStream())) {
            for (FileObject file : fileList) {
                if (file.getType() == FileType.FILE) {
                    addToZip(fileToCompress, file, zos);
                }
            }
        }
    }

    /**
     * Add file content to ZipOutputStream of target zip file.
     *
     * @param fileObject   Source fileObject
     * @param file         The file inside source folder
     * @param outputStream ZipOutputStream to target zip file
     */
    private void addToZip(FileObject fileObject, FileObject file, ZipOutputStream outputStream) throws IOException {
        InputStream fin = null;
        try {
            fin = file.getContent().getInputStream();
            String name = file.getName().toString();
            String entry = name.substring(fileObject.getName().toString().length() + 1);
            ZipEntry zipEntry = new ZipEntry(entry);
            outputStream.putNextEntry(zipEntry);
            final byte[] bytes = new byte[Const.ZIP_BUFFER_SIZE];
            int length;
            while ((length = fin.read(bytes)) != -1) {
                outputStream.write(bytes, 0, length);
            }
        } finally {

            try {
                outputStream.closeEntry();
            } catch (IOException e) {
                log.error("FileConnector:compress - "
                        + "Error while closing OutputStream ", e);
            }

            try {
                if (fin != null) {
                    fin.close();
                }
            } catch (IOException e) {
                log.error("FileConnector:compress - "
                        + "Error while closing InputStream for file " + file.getURL(), e);
            }

        }
    }

    /**
     * Sets error to context and handle.
     *
     * @param msgCtx      Message Context to set info
     * @param e           Exception associated
     * @param error       Error code
     * @param errorDetail Error detail
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        Utils.setError(OPERATION_NAME, msgCtx, e, error, errorDetail);
        handleException(errorDetail, e, msgCtx);
    }
}
