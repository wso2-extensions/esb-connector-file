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
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements Create Directory operation.
 */
public class CreateDirectory extends AbstractConnectorOperation {

    private static final String OPERATION_NAME = "createDirectory";
    private static final String ERROR_MESSAGE = "Error while performing file:createDirectory for folder ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String folderPath = null;
        FileObject folderToCreate = null;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {
            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);
            String autoCreate = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, "autoCreate");
            String permissions = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, "permissions");
            
            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            folderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, Const.DIRECTORY_PATH);
            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            folderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + folderPath;
            // Use suspension-enabled file resolution for FTP/FTPS
            folderToCreate = fileSystemHandlerConnection.resolveFileWithSuspension(folderPath);
            
            // Check if auto create is enabled for parent directories
            boolean shouldAutoCreate = autoCreate != null && Boolean.parseBoolean(autoCreate);
            if (shouldAutoCreate) {
                // Create parent directories if they don't exist
                FileObject parent = folderToCreate.getParent();
                if (parent != null && !parent.exists()) {
                    parent.createFolder();
                }
            }
            
            //create folder if it doesn't exist
            folderToCreate.createFolder();
            
            // Set permissions if specified (mainly for local/SFTP file systems)
            if (permissions != null && !permissions.trim().isEmpty()) {
                try {
                    // Convert permissions string to octal (e.g., "755" -> 0755)
                    if (permissions.matches("\\d{3,4}")) {
                        int permissionValue = Integer.parseInt(permissions, 8);
                        // Set permissions using Java NIO POSIX file permissions
                        Utils.setFilePermissions(folderToCreate, permissionValue);
                    }
                } catch (Exception e) {
                    log.warn("Could not set permissions " + permissions + " on directory " + folderPath + 
                             ". Permissions setting may not be supported on this file system.", e);
                }
            }

            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

        } finally {

            if (folderToCreate != null) {
                try {
                    folderToCreate.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + folderToCreate);
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
