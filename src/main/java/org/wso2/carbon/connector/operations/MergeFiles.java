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
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.MergeFileResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;
import org.apache.commons.vfs2.FileSelectInfo;
import org.apache.commons.vfs2.FileSelector;
import org.apache.commons.vfs2.Selectors;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements Merge Files operation.
 */
public class MergeFiles extends AbstractConnectorOperation {

    private static final String SOURCE_DIRECTORY_PATH_PARAM = "sourceDirectoryPath";
    private static final String TARGET_FILE_PATH_PARAM = "targetFilePath";
    private static final String FILE_PATTERN_PARAM = "filePattern";
    private static final String FILE_FILTER_TYPE = "fileFilterType";
    private static final String INCLUDE_FILES = "includeFiles";
    private static final String EXCLUDE_FILES = "excludeFiles";
    private static final String MAX_FILE_AGE = "maxFileAge";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String WRITE_MODE_PARAM = "writeMode";
    private static final String DETAIL_ELE_NAME = "detail";
    private static final String NUMBER_OF_MERGED_FILES_ELE_NAME = "numberOfMergedFiles";
    private static final String TOTAL_WRITTEN_BYTES_ELE_NAME = "totalWrittenBytes";
    private static final String OPERATION_NAME = "mergeFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:merge for directory ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String sourceDirectoryPath = null;
        FileObject sourceDir = null;
        String targetFilePath;
        FileObject targetFile;
        FileOperationResult result;
        int numberOfMergedFiles = 0;
        long numberOfTotalBytesWritten = 0;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;

        try {
            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);

            sourceDirectoryPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_DIRECTORY_PATH_PARAM);
            targetFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_FILE_PATH_PARAM);
            String filePattern = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FILE_PATTERN_PARAM);
            
            // Read new filtering parameters
            String fileFilterType = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FILE_FILTER_TYPE);
            String includeFiles = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, INCLUDE_FILES);
            String excludeFiles = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, EXCLUDE_FILES);
            String maxFileAge = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, MAX_FILE_AGE);
            String timeBetweenSizeCheck = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TIME_BETWEEN_SIZE_CHECK);
                    
            String writeMode = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, WRITE_MODE_PARAM);

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            sourceDirectoryPath = fileSystemHandlerConnection.getBaseDirectoryPath() + sourceDirectoryPath;
            targetFilePath = fileSystemHandlerConnection.getBaseDirectoryPath() + targetFilePath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            sourceDir = fileSystemHandlerConnection.resolveFileWithSuspension(sourceDirectoryPath);
            targetFile = fileSystemHandlerConnection.resolveFileWithSuspension(targetFilePath);

            if (!sourceDir.exists()) {
                throw new IllegalPathException("Directory not found: " + sourceDirectoryPath);
            } else {
                if (!sourceDir.isFolder()) {
                    throw new IllegalPathException("Source Path does not point to a directory: " + sourceDirectoryPath);
                }
            }

            if (!targetFile.exists()) {
                targetFile.createFile();
            } else {
                if (writeMode.equals(Const.OVERWRITE)) {
                    boolean deleteDone = targetFile.delete();           //otherwise append is done automatically
                    if (!deleteDone) {
                        throw new FileOperationException("Error while overwriting existing file " + targetFilePath);
                    }
                    targetFile.createFile();
                }
            }


            // Determine which filtering to use
            boolean useAdvancedFilter = !StringUtils.isEmpty(includeFiles) || !StringUtils.isEmpty(excludeFiles) 
                                      || !StringUtils.isEmpty(maxFileAge);
            
            FileObject[] children = sourceDir.getChildren();

            if (children != null && children.length != 0) {
                MergeFileResult mergeFileResult;
                if (useAdvancedFilter) {
                    // Use advanced filtering
                    AdvancedFileFilter advancedFilter = new AdvancedFileFilter(fileFilterType, includeFiles, excludeFiles, maxFileAge);
                    mergeFileResult = mergeFilesWithAdvancedFilter(targetFile, advancedFilter, timeBetweenSizeCheck, children);
                } else {
                    // Use legacy pattern filtering
                    mergeFileResult = mergeFiles(targetFile, filePattern, timeBetweenSizeCheck, children);
                }
                numberOfMergedFiles = mergeFileResult.getNumberOfMergedFiles();
                numberOfTotalBytesWritten = mergeFileResult.getNumberOfTotalWrittenBytes();
            }

            JsonObject fileMergeDetailEle = new JsonObject();
            fileMergeDetailEle.addProperty(NUMBER_OF_MERGED_FILES_ELE_NAME, numberOfMergedFiles);
            fileMergeDetailEle.addProperty(TOTAL_WRITTEN_BYTES_ELE_NAME, numberOfTotalBytesWritten);
            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            resultJSON.add(DETAIL_ELE_NAME, fileMergeDetailEle);
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourceDirectoryPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (FileOperationException | IOException e) {     //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + sourceDirectoryPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + sourceDirectoryPath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

        } finally {

            if (sourceDir != null) {
                try {
                    sourceDir.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing folder object while merging files in "
                            + sourceDir);
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
     * Perform File merging.
     *
     * @param targetFile  Target file to create after merge
     * @param filePattern Specific pattern of files to merge
     * @param timeBetweenSizeCheck Time to wait between size checks for stability
     * @param children    Files to merge
     * @return Info object with result of the operation
     * @throws IOException In case of file operation issue
     */
    private MergeFileResult mergeFiles(FileObject targetFile, String filePattern, String timeBetweenSizeCheck, FileObject[] children) throws IOException {

        int numberOfMergedFiles = 0;
        long numberOfTotalBytesWritten = 0;
        OutputStream outputStream = null;
        BufferedOutputStream bufferedOutputStream = null;

        try {
            //we'll append all in source files
            outputStream = targetFile.getContent().getOutputStream(true);
            bufferedOutputStream = new BufferedOutputStream(outputStream);

            for (FileObject child : children) {
                long numberOfBytesWritten = 0;
                
                // Check file stability if parameter is provided
                if (!StringUtils.isEmpty(timeBetweenSizeCheck) && child.isFile()) {
                    if (!isFileStable(child, timeBetweenSizeCheck)) {
                        log.warn("File is not stable (still being written), skipping: " + child.getName().getBaseName());
                        continue;
                    }
                }
                
                if (StringUtils.isNotEmpty(filePattern)) {
                    if (child.getName().getBaseName().matches(filePattern)) {
                        numberOfBytesWritten = child.getContent().write(bufferedOutputStream);
                    }
                } else {
                    numberOfBytesWritten = child.getContent().write(bufferedOutputStream);
                }
                if (numberOfBytesWritten != 0) {
                    bufferedOutputStream.flush();
                    outputStream.flush();
                    numberOfMergedFiles = numberOfMergedFiles + 1;
                    numberOfTotalBytesWritten = numberOfTotalBytesWritten + numberOfBytesWritten;
                }

                try {
                    child.close();
                } catch (IOException e) {
                    log.warn("Error while closing a file in the source folder: " + e.getMessage(), e);
                }
            }

            return new MergeFileResult(numberOfMergedFiles, numberOfTotalBytesWritten);

        } finally {

            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.close();
                } catch (IOException e) {
                    log.error("FileConnector: MergeFiles - Error while "
                            + "closing buffered outputStream for file " + targetFile.getURL());
                }
            }

            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error("FileConnector: MergeFiles - Error while"
                            + " closing outputStream for file " + targetFile.getURL());
                }
            }
        }
    }

    /**
     * Perform File merging with advanced filtering.
     *
     * @param targetFile           Target file to create after merge
     * @param advancedFilter       Advanced filter for file selection
     * @param timeBetweenSizeCheck Time to wait between size checks for stability
     * @param children             Files to merge
     * @return Info object with result of the operation
     * @throws IOException In case of file operation issue
     */
    private MergeFileResult mergeFilesWithAdvancedFilter(FileObject targetFile, AdvancedFileFilter advancedFilter, 
                                                        String timeBetweenSizeCheck, FileObject[] children) throws IOException {
        int numberOfMergedFiles = 0;
        long numberOfTotalBytesWritten = 0;
        OutputStream outputStream = null;
        BufferedOutputStream bufferedOutputStream = null;

        try {
            //we'll append all in source files
            outputStream = targetFile.getContent().getOutputStream(true);
            bufferedOutputStream = new BufferedOutputStream(outputStream);

            for (FileObject child : children) {
                long numberOfBytesWritten = 0;
                
                // Check file stability if parameter is provided
                if (!StringUtils.isEmpty(timeBetweenSizeCheck) && child.isFile()) {
                    if (!isFileStable(child, timeBetweenSizeCheck)) {
                        log.warn("File is not stable (still being written), skipping: " + child.getName().getBaseName());
                        continue;
                    }
                }
                
                // Use advanced filter to check if file should be included
                try {
                    FileSelectInfo fileSelectInfo = new FileSelectInfo() {
                        @Override
                        public FileObject getFile() {
                            return child;
                        }

                        @Override
                        public int getDepth() {
                            return 1; // Files in source directory are at depth 1
                        }

                        @Override
                        public FileObject getBaseFolder() {
                            try {
                                return child.getParent();
                            } catch (Exception e) {
                                return null;
                            }
                        }
                    };
                    
                    if (advancedFilter.accept(fileSelectInfo)) {
                        numberOfBytesWritten = child.getContent().write(bufferedOutputStream);
                    }
                } catch (Exception e) {
                    log.warn("Error applying advanced filter to file " + child.getName().getBaseName() + ": " + e.getMessage());
                    continue;
                }
                
                if (numberOfBytesWritten != 0) {
                    bufferedOutputStream.flush();
                    outputStream.flush();
                    numberOfMergedFiles = numberOfMergedFiles + 1;
                    numberOfTotalBytesWritten = numberOfTotalBytesWritten + numberOfBytesWritten;
                }

                try {
                    child.close();
                } catch (IOException e) {
                    log.warn("Error while closing a file in the source folder: " + e.getMessage(), e);
                }
            }

            return new MergeFileResult(numberOfMergedFiles, numberOfTotalBytesWritten);

        } finally {

            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.close();
                } catch (IOException e) {
                    log.error("FileConnector: MergeFiles - Error while "
                            + "closing buffered outputStream for file " + targetFile.getURL());
                }
            }

            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error("FileConnector: MergeFiles - Error while"
                            + " closing outputStream for file " + targetFile.getURL());
                }
            }
        }
    }

    /**
     * Check if file is stable (not being written to) by comparing file sizes
     * over a specified interval.
     * 
     * @param file File to check for stability
     * @param sizeCheckInterval Time in milliseconds to wait between size checks
     * @return true if file is stable, false if still being written
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
            
            // Re-read file size and compare
            long finalSize = file.getContent().getSize();
            
            // File is stable if size hasn't changed
            return initialSize == finalSize;
            
        } catch (NumberFormatException e) {
            // If we can't parse the interval, assume file is stable
            log.warn("Invalid timeBetweenSizeCheck value: " + sizeCheckInterval + ". Skipping stability check.");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // If interrupted, assume file is stable
            return true;
        } catch (Exception e) {
            // If we can't check stability, assume file is stable
            log.warn("Error checking file stability: " + e.getMessage() + ". Assuming file is stable.");
            return true;
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
