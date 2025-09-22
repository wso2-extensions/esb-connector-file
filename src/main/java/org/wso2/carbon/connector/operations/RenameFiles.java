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
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.apache.commons.lang.StringUtils;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements rename operation.
 */
public class RenameFiles extends AbstractConnectorOperation {

    private static final String OVERWRITE_PARAM = "overwrite";
    private static final String RENAME_TO_PARAM = "renameTo";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String OPERATION_NAME = "renameFile";
    private static final String ERROR_MESSAGE = "Error while performing file:rename for file/folder ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String fileOrFolderPath = null;
        String newName;
        FileObject fileToRename = null;
        boolean overwrite;
        FileOperationResult result;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {

            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);
            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            overwrite = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, OVERWRITE_PARAM));
            newName = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, RENAME_TO_PARAM);
            String timeBetweenSizeCheck = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TIME_BETWEEN_SIZE_CHECK);
            fileOrFolderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, Const.FILE_OR_DIRECTORY_PATH);
            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            fileOrFolderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + fileOrFolderPath;
            fileToRename = fileSystemHandlerConnection.resolveFileWithSuspension(fileOrFolderPath);

            //path after rename
            String newFilePath = fileOrFolderPath.substring(0, fileOrFolderPath.lastIndexOf(Const.FILE_SEPARATOR))
                    + Const.FILE_SEPARATOR + newName;
            FileObject newFile = fileSystemHandlerConnection.resolveFileWithSuspension(newFilePath);

            if (fileToRename.exists()) {

                // Check file stability if parameter is provided
                if (!StringUtils.isEmpty(timeBetweenSizeCheck) && fileToRename.isFile()) {
                    if (!isFileStable(fileToRename, timeBetweenSizeCheck)) {
                        handleError(messageContext, null, Error.OPERATION_ERROR,
                                "File is not stable (still being written). Cannot rename at this time.",
                                responseVariable, overwriteBody);
                        return;
                    }
                }

                if (fileToRename.canRenameTo(newFile)) {

                    if (!overwrite && newFile.exists()) {
                        handleError(messageContext, null, Error.FILE_ALREADY_EXISTS,
                                "Destination file already exists and overwrite not allowed.",
                                responseVariable, overwriteBody);
                    } else {
                        fileToRename.moveTo(newFile);
                    }

                } else {

                    log.error(ERROR_MESSAGE + ". File " + fileOrFolderPath
                            + " cannot be renamed to " + newName);
                    handleError(messageContext, null, Error.OPERATION_ERROR,
                            "File " + fileOrFolderPath + " cannot be renamed to " + newName,
                            responseVariable, overwriteBody);
                }
            } else {
                throw new IllegalPathException("File or folder does not exist " + fileOrFolderPath);
            }

            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

        } finally {

            if (fileToRename != null) {
                try {
                    fileToRename.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + fileToRename);
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
