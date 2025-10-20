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
import org.wso2.org.apache.commons.vfs2.FileFilter;
import org.wso2.org.apache.commons.vfs2.FileFilterSelector;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.SimpleFileFiler;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements delete file or folder operation
 */
public class DeleteFileOrFolder extends AbstractConnectorOperation {

    private static final String MATCHING_PATTERN_PARAM = "matchingPattern";
    private static final String FILE_FILTER_TYPE = "fileFilterType";
    private static final String INCLUDE_FILES = "includeFiles";
    private static final String EXCLUDE_FILES = "excludeFiles";
    private static final String MAX_FILE_AGE = "maxFileAge";
    private static final String NUM_OF_DELETED_FILES_ELE = "numOfDeletedFiles";
    private static final String OPERATION_NAME = "deleteFile";
    private static final String ERROR_MESSAGE = "Error while performing file:delete for file/folder ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String fileOrFolderPath = null;
        FileObject fileObjectToDelete = null;
        String fileMatchingPattern;
        boolean isOperationSuccessful;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        int maxRetries;
        int retryDelay;
        int attempt = 0;
        boolean successOperation = false;
        String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                (messageContext, Const.DISK_SHARE_ACCESS_MASK);
        //read max retries and retry delay
        try {
            maxRetries = Integer.parseInt((String) ConnectorUtils.lookupTemplateParamater(messageContext,
                    Const.MAX_RETRY_PARAM));
            retryDelay = Integer.parseInt((String) ConnectorUtils.lookupTemplateParamater(messageContext,
                    Const.RETRY_DELAY_PARAM));
            if (log.isDebugEnabled()) {
                log.debug("Max retries: " + maxRetries + " Retry delay: " + retryDelay);
            }
        } catch (Exception e) {
            maxRetries = 0;
            retryDelay = 0;
        }
        while (attempt <= maxRetries && !successOperation) {
            try {

                fileSystemHandlerConnection = (FileSystemHandler) handler
                        .getConnection(Const.CONNECTOR_NAME, connectionName);
                fileMatchingPattern = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, MATCHING_PATTERN_PARAM);
                
                // Read new filtering parameters
                String fileFilterType = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, FILE_FILTER_TYPE);
                String includeFiles = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, INCLUDE_FILES);
                String excludeFiles = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, EXCLUDE_FILES);
                String maxFileAge = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, MAX_FILE_AGE);
                        
                fileOrFolderPath = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, Const.FILE_OR_DIRECTORY_PATH);
                FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
                FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
                Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
                fileOrFolderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + fileOrFolderPath;
                fileObjectToDelete = fileSystemHandlerConnection.resolveFileWithSuspension(fileOrFolderPath);

                if (log.isDebugEnabled()) {
                    log.debug("Delete file/folder attempt " + attempt + " of " + maxRetries + " for file/folder "
                            + fileOrFolderPath);
                }

                //Deletes this file. Does nothing if this file does not exist
                if (fileObjectToDelete.isFile()) {
                    isOperationSuccessful = fileObjectToDelete.delete();
                    JsonObject resultJSON = generateOperationResult(messageContext,
                            new FileOperationResult(OPERATION_NAME, isOperationSuccessful));
                    handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);
                }

                if (fileObjectToDelete.isFolder()) {
                    int numberOfDeletedFiles;
                    
                    // Determine which filter to use based on available parameters
                    FileFilter fileFilter = null;
                    boolean useAdvancedFilter = !StringUtils.isEmpty(includeFiles) || !StringUtils.isEmpty(excludeFiles) || !StringUtils.isEmpty(maxFileAge);
                    
                    if (useAdvancedFilter) {
                        // Use new advanced filter for include/exclude/age filtering
                        fileFilter = new AdvancedFileFilter(fileFilterType, includeFiles, excludeFiles, maxFileAge);
                        FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
                        numberOfDeletedFiles = fileObjectToDelete.delete(fileFilterSelector);
                    } else if (StringUtils.isNotEmpty(fileMatchingPattern)) {
                        // Use legacy simple filter for backward compatibility
                        fileFilter = new SimpleFileFiler(fileMatchingPattern);
                        FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
                        /*
                         * Deletes all descendants of this file that
                         * match a selector. Does nothing if this
                         * file does not exist.
                         */
                        numberOfDeletedFiles = fileObjectToDelete.delete(fileFilterSelector);
                    } else {
                        //Deletes this file and all children.
                        numberOfDeletedFiles = fileObjectToDelete.deleteAll();
                    }
                    isOperationSuccessful = true;
                    JsonObject resultJSON = generateOperationResult(messageContext,
                            new FileOperationResult(OPERATION_NAME, isOperationSuccessful));
                    resultJSON.addProperty(NUM_OF_DELETED_FILES_ELE, numberOfDeletedFiles);
                    handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);
                }
                successOperation = true;
            } catch (InvalidConfigurationException e) {

                String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
                handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

            } catch (Exception e) {

                String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
                log.error(errorDetail, e);
                Utils.closeFileSystem(fileObjectToDelete);
                if (attempt >= maxRetries - 1) {
                    handleError(messageContext, e, Error.RETRY_EXHAUSTED, errorDetail, responseVariable, overwriteBody);
                }
                // Log the retry attempt
                log.warn(Const.CONNECTOR_NAME + ":Error while write "
                        + fileOrFolderPath + ". Retrying after " + retryDelay + " milliseconds retry attempt " + (attempt + 1)
                        + " out of " + maxRetries);
                attempt++;
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    handleError(messageContext, ie, Error.OPERATION_ERROR, ERROR_MESSAGE + fileOrFolderPath,
                            responseVariable, overwriteBody);
                }
            } finally {

                if (fileObjectToDelete != null) {
                    try {
                        fileObjectToDelete.close();
                    } catch (FileSystemException e) {
                        log.error(Const.CONNECTOR_NAME
                                + ":Error while closing file object while creating directory "
                                + fileObjectToDelete);
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
