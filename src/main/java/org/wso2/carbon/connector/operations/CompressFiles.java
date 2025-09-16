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

import com.google.gson.JsonObject;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSelectInfo;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements Compress Files operation.
 */
public class CompressFiles extends AbstractConnectorOperation {

    private static final String LOG_IDENTIFIER = "[FileConnector:compress] ";
    private static final String SOURCE_DIRECTORY_PATH = "sourceDirectoryPath";
    private static final String TARGET_FILE_PATH = "targetFilePath";
    private static final String INCLUDE_SUB_DIRECTORIES = "includeSubDirectories";
    private static final String FILE_FILTER_TYPE = "fileFilterType";
    private static final String INCLUDE_FILES = "includeFiles";
    private static final String EXCLUDE_FILES = "excludeFiles";
    private static final String MAX_FILE_AGE = "maxFileAge";
    private static final String SUB_DIRECTORY_MAX_DEPTH = "subDirectoryMaxDepth";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String NUMBER_OF_FILES_ADDED_ELEMENT = "NumberOfFilesAdded";
    private static final String OPERATION_NAME = "compress";
    private static final String ERROR_MESSAGE = "Error while performing file:compress for file/directory ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String sourceFilePath = null;
        FileObject fileToCompress = null;
        String targetZipFilePath = null;
        FileObject targetZipFile = null;
        boolean includeSubDirectories = true;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {
            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);

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

            // Advanced filtering parameters
            String fileFilterType = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FILE_FILTER_TYPE);
            String includeFiles = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, INCLUDE_FILES);
            String excludeFiles = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, EXCLUDE_FILES);
            String maxFileAge = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, MAX_FILE_AGE);
            String subDirectoryMaxDepthStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SUB_DIRECTORY_MAX_DEPTH);
            String timeBetweenSizeCheckStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TIME_BETWEEN_SIZE_CHECK);

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            sourceFilePath = fileSystemHandlerConnection.getBaseDirectoryPath() + sourceFilePath;
            targetZipFilePath = fileSystemHandlerConnection.getBaseDirectoryPath() + targetZipFilePath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            fileToCompress = fsManager.resolveFile(sourceFilePath, fso);

            if (!fileToCompress.exists()) {
                throw new IllegalPathException("File or directory to compress does not exist");
            }
            targetZipFile = fsManager.resolveFile(targetZipFilePath, fso);

            if(StringUtils.isEmpty(targetZipFile.getName().getExtension())) {
                throw new IllegalPathException("Target File path does not resolve to a file");
            }

            int numberOfCompressedFiles;
            
            // Use advanced filtering if any advanced parameters are provided
            if (StringUtils.isNotEmpty(fileFilterType) || StringUtils.isNotEmpty(includeFiles) || 
                StringUtils.isNotEmpty(excludeFiles) || StringUtils.isNotEmpty(maxFileAge) ||
                StringUtils.isNotEmpty(subDirectoryMaxDepthStr) || StringUtils.isNotEmpty(timeBetweenSizeCheckStr)) {
                numberOfCompressedFiles = compressFileWithAdvancedFilter(fileToCompress, targetZipFile, 
                    includeSubDirectories, fileFilterType, includeFiles, excludeFiles, maxFileAge, 
                    subDirectoryMaxDepthStr, timeBetweenSizeCheckStr);
            } else {
                numberOfCompressedFiles = compressFile(fileToCompress, targetZipFile, includeSubDirectories);
            }

            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            resultJSON.addProperty(NUMBER_OF_FILES_ADDED_ELEMENT, numberOfCompressedFiles);
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

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
            if (handler.getStatusOfConnection(Const.CONNECTOR_NAME, connectionName)) {
                if (fileSystemHandlerConnection != null) {
                    Utils.addMaxAccessMaskToFSO(fileSystemHandlerConnection.getFsOptions());
                    handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
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
     * Compresses files or folder with advanced filtering capabilities.
     *
     * @param fileToCompress        File or folder to compress
     * @param targetZipFile         Zip file to create
     * @param includeSubDirectories True if to include sub-directories
     * @param fileFilterType        Type of file filtering (ant or regex)
     * @param includeFiles          Include file patterns
     * @param excludeFiles          Exclude file patterns
     * @param maxFileAge            Maximum file age
     * @param subDirectoryMaxDepthStr Maximum subdirectory depth
     * @param timeBetweenSizeCheckStr Time to wait between size checks
     * @return How many files were added to compressed file
     * @throws IOException In case of error dealing with files
     */
    private int compressFileWithAdvancedFilter(FileObject fileToCompress, FileObject targetZipFile, 
                                             boolean includeSubDirectories, String fileFilterType, 
                                             String includeFiles, String excludeFiles, String maxFileAge,
                                             String subDirectoryMaxDepthStr, String timeBetweenSizeCheckStr) throws IOException {

        int numberOfFilesAddedToZip = 0;

        if (fileToCompress.isFolder()) {
            // Parse subdirectory max depth
            Integer subDirectoryMaxDepth = null;
            if (StringUtils.isNotEmpty(subDirectoryMaxDepthStr)) {
                try {
                    subDirectoryMaxDepth = Integer.parseInt(subDirectoryMaxDepthStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid subDirectoryMaxDepth value: " + subDirectoryMaxDepthStr + ". Using unlimited depth.");
                }
            }

            // Create advanced file filter
            AdvancedFileFilter filter = new AdvancedFileFilter(fileFilterType, includeFiles, excludeFiles, maxFileAge);

            List<FileObject> fileList = new ArrayList<>();
            getAllFilesWithAdvancedFilter(fileToCompress, fileList, includeSubDirectories, filter, 
                                         subDirectoryMaxDepth, 0, timeBetweenSizeCheckStr);
            
            writeZipFiles(fileToCompress, targetZipFile, fileList);
            numberOfFilesAddedToZip = fileList.size();

        } else {
            // For single file, check stability if required
            if (StringUtils.isNotEmpty(timeBetweenSizeCheckStr) && !isFileStable(fileToCompress, timeBetweenSizeCheckStr)) {
                log.warn("File is not stable (still being written), skipping: " + fileToCompress.getName().getBaseName());
                return 0;
            }

            // For single file, apply filtering
            AdvancedFileFilter filter = new AdvancedFileFilter(fileFilterType, includeFiles, excludeFiles, maxFileAge);
            if (!acceptFile(filter, fileToCompress)) {
                return 0; // File doesn't match filter criteria
            }

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
     * Get all files of the directory with advanced filtering.
     *
     * @param dir                   Source directory
     * @param fileList              Container for file list
     * @param includeSubDirectories true if to include sub directories
     * @param filter                Advanced file filter
     * @param maxDepth              Maximum depth to traverse (null for unlimited)
     * @param currentDepth          Current traversal depth
     * @param timeBetweenSizeCheckStr Time between size checks for stability
     * @throws IOException In case of an error dealing with files
     */
    private void getAllFilesWithAdvancedFilter(FileObject dir, List<FileObject> fileList,
                                             boolean includeSubDirectories, AdvancedFileFilter filter,
                                             Integer maxDepth, int currentDepth, String timeBetweenSizeCheckStr) throws IOException {

        // Check depth limit
        if (maxDepth != null && currentDepth >= maxDepth) {
            return;
        }

        FileObject[] children = dir.getChildren();
        for (FileObject child : children) {
            if (child.getType() == FileType.FILE) {
                // Check file stability if required
                if (StringUtils.isNotEmpty(timeBetweenSizeCheckStr) && !isFileStable(child, timeBetweenSizeCheckStr)) {
                    log.warn("File is not stable (still being written), skipping: " + child.getName().getBaseName());
                    continue;
                }
                
                // Apply advanced filtering
                if (acceptFile(filter, child)) {
                    fileList.add(child);
                }
            } else if (child.getType() == FileType.FOLDER && includeSubDirectories) {
                getAllFilesWithAdvancedFilter(child, fileList, includeSubDirectories, filter, 
                                            maxDepth, currentDepth + 1, timeBetweenSizeCheckStr);
            }
        }
    }

    /**
     * Checks if a file is stable by comparing its size over time.
     *
     * @param file The file to check for stability
     * @param sizeCheckInterval Time to wait between size checks in milliseconds
     * @return true if file size is stable, false otherwise
     */
    private boolean isFileStable(FileObject file, String sizeCheckInterval) {
        try {
            long interval = Long.parseLong(sizeCheckInterval);
            if (interval <= 0) {
                return true; // No stability check if interval is 0 or negative
            }
            
            long initialSize = file.getContent().getSize();
            
            // Wait for the specified interval
            Thread.sleep(interval);
            
            // Refresh and check size again
            file.refresh();
            long finalSize = file.getContent().getSize();
            
            return initialSize == finalSize;
        } catch (Exception e) {
            log.warn("Error checking file stability for " + file + ": " + e.getMessage());
            return true; // Assume stable if we can't check
        }
    }

    /**
     * Helper method to check if a file object matches the advanced filter criteria.
     *
     * @param filter The advanced file filter
     * @param file   The file object to check
     * @return true if the file matches the filter criteria, false otherwise
     */
    private boolean acceptFile(AdvancedFileFilter filter, FileObject file) {
        try {
            // Create a simple FileSelectInfo wrapper for the FileObject
            FileSelectInfo fileSelectInfo = new FileSelectInfo() {
                @Override
                public FileObject getFile() {
                    return file;
                }

                @Override
                public int getDepth() {
                    return 0; // Not used in our filtering logic
                }

                @Override
                public FileObject getBaseFolder() {
                    try {
                        return file.getParent();
                    } catch (Exception e) {
                        return null;
                    }
                }
            };
            
            return filter.accept(fileSelectInfo);
        } catch (Exception e) {
            log.warn("Error checking file filter for " + file + ": " + e.getMessage());
            return false;
        }
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
     * @param responseVariable Response variable name
     * @param overwriteBody Overwrite body
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail,
                             String responseVariable, boolean overwriteBody) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        FileOperationResult result = new FileOperationResult(OPERATION_NAME, false, error, e.getMessage());
        JsonObject resultJSON = generateOperationResult(msgCtx, result);
        handleConnectorResponse(msgCtx, responseVariable, overwriteBody, resultJSON, null, null);
        handleException(errorDetail, e, msgCtx);
    }
}
