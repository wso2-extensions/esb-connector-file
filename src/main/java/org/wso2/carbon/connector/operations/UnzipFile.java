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
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implements unzip file operation
 */
public class UnzipFile extends AbstractConnector {

    private static final String SOURCE_FILE_PATH_PARAM = "sourceFilePath";
    private static final String TARGET_DIRECTORY_PARAM = "targetDirectory";
    private static final String OPERATION_NAME = "unzipFile";
    private static final String ERROR_MESSAGE = "Error while performing file:unzip for file ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String filePath = null;
        FileObject compressedFile = null;
        String folderPathToExtract;
        FileObject targetFolder;
        FileOperationResult result;

        try {

            String connectionName = Utils.getConnectionName(messageContext);
            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_FILE_PATH_PARAM);
            folderPathToExtract = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_DIRECTORY_PARAM);


            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandler.getBaseDirectoryPath() + filePath;
            folderPathToExtract = fileSystemHandler.getBaseDirectoryPath() + folderPathToExtract;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            compressedFile = fsManager.resolveFile(filePath, fso);
            targetFolder = fsManager.resolveFile(folderPathToExtract, fso);

            //execute validations

            if (!compressedFile.exists()) {
                throw new IllegalPathException("File not found: " + filePath);
            }

            if (!compressedFile.isFile()) {
                throw new IllegalPathException("File is not a compressed file: " + filePath);
            }

            if (!targetFolder.exists()) {
                targetFolder.createFolder();
            }

            OMElement fileContentEle = executeDecompression(compressedFile, folderPathToExtract, fsManager, fso);

            result = new FileOperationResult(OPERATION_NAME, true, fileContentEle);

            Utils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } finally {

            if (compressedFile != null) {
                try {
                    compressedFile.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing file object while decompressing the file. "
                            + compressedFile);
                }
            }
        }
    }

    /**
     * Execute decompression, iterating over compressed entries.
     *
     * @param sourceFile             Compressed file
     * @param folderPathToExtract Directory path to decompress
     * @param fsManager           File System Manager associated with the file connection
     * @param fso                 File System Options associated with the file connection
     * @return OMElement with compressed file entries extracted
     * @throws IOException In case of I/O error
     */
    private OMElement executeDecompression(FileObject sourceFile, String folderPathToExtract,
                                           FileSystemManager fsManager, FileSystemOptions fso) throws IOException {
        //execute decompression
        String fileExtension = sourceFile.getName().getExtension();
        OMElement compressedFileContentEle;
        if (fileExtension.equals("gz")) {
            compressedFileContentEle = Utils.
                    createOMElement("gzFileContent", null);
            FileObject target = fsManager.resolveFile(folderPathToExtract + Const.FILE_SEPARATOR
                    + sourceFile.getName().getBaseName()
                    .replace("." + sourceFile.getName().getExtension(), ""), fso);
            extractGzip(sourceFile, target);
            String entryName = sourceFile.getName().getBaseName().replace("/", "--");
            OMElement EntryEle = Utils.
                    createOMElement(entryName, "extracted");
            compressedFileContentEle.addChild(EntryEle);
            return compressedFileContentEle;
        } else {
            ZipInputStream zipIn = null;
            compressedFileContentEle = Utils.
                    createOMElement("zipFileContent", null);
            try {
                zipIn = new ZipInputStream(sourceFile.getContent().getInputStream());
                ZipEntry entry = zipIn.getNextEntry();
                //iterate over the entries
                while (entry != null) {
                    String zipEntryPath = folderPathToExtract + Const.FILE_SEPARATOR + entry.getName();
                    FileObject zipEntryTargetFile = fsManager.resolveFile(zipEntryPath, fso);
                    try {
                        if (!entry.isDirectory()) {
                            // if the entry is a file, extracts it
                            extractFile(zipIn, zipEntryTargetFile);
                            //"/" is not allowed when constructing XML
                            String entryName = entry.getName().replace("/", "--");
                            OMElement zipEntryEle = Utils.
                                    createOMElement(entryName, "extracted");
                            compressedFileContentEle.addChild(zipEntryEle);
                        } else {
                            // if the entry is a directory, make the directory
                            zipEntryTargetFile.createFolder();
                        }
                    } catch (IOException e) {
                        log.error("Unable to extract the zip file. ", e);
                    } finally {
                        try {
                            zipIn.closeEntry();
                        } finally {
                            entry = zipIn.getNextEntry();
                        }
                    }
                }
                return compressedFileContentEle;
            } finally {
                if (zipIn != null) {
                    zipIn.close();
                }
            }
        }
    }

    public static void extractGzip(FileObject source, FileObject target) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(source.getContent().getInputStream());
             OutputStream fos = target.getContent().getOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    /**
     * Extract Zip entry and write to file.
     *
     * @param zipIn              ZipInputStream to zip entry
     * @param zipEntryTargetFile FileObject pointing to extracted file related to zip entry
     * @throws IOException In case of I/O error
     */
    private void extractFile(ZipInputStream zipIn, FileObject zipEntryTargetFile) throws IOException {
        BufferedOutputStream bos = null;
        OutputStream fOut = null;
        try {
            //open the zip file
            fOut = zipEntryTargetFile.getContent().getOutputStream();
            bos = new BufferedOutputStream(fOut);
            byte[] bytesIn = new byte[Const.UNZIP_BUFFER_SIZE];
            int read;
            while ((read = zipIn.read(bytesIn)) != -1) {
                bos.write(bytesIn, 0, read);
            }
            bos.flush();
        } finally {
            //we must always close the zip file
            if (bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    log.error("FileConnector:Unzip Error while closing the BufferedOutputStream to target file: "
                            + e.getMessage(), e);
                }
            }
            if (fOut != null) {
                try {
                    fOut.close();
                } catch (IOException e) {
                    log.error("Error while closing the OutputStream to target file: "
                            + e.getMessage(), e);
                }
            }
            zipEntryTargetFile.close();
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
        Utils.setError(OPERATION_NAME, msgCtx, e, error, errorDetail);
        handleException(errorDetail, e, msgCtx);
    }
}
