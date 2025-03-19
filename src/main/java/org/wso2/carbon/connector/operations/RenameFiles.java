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

/**
 * Implements rename operation.
 */
public class RenameFiles extends AbstractConnector {

    private static final String OVERWRITE_PARAM = "overwrite";
    private static final String RENAME_TO_PARAM = "renameTo";
    private static final String OPERATION_NAME = "renameFile";
    private static final String ERROR_MESSAGE = "Error while performing file:rename for file/folder ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

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
            fileOrFolderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, Const.FILE_OR_DIRECTORY_PATH);
            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            fileOrFolderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + fileOrFolderPath;
            fileToRename = fsManager.resolveFile(fileOrFolderPath, fso);

            //path after rename
            String newFilePath = fileOrFolderPath.substring(0, fileOrFolderPath.lastIndexOf(Const.FILE_SEPARATOR))
                    + Const.FILE_SEPARATOR + newName;
            FileObject newFile = fsManager.resolveFile(newFilePath, fso);

            if (fileToRename.exists()) {

                if (fileToRename.canRenameTo(newFile)) {

                    if (!overwrite && newFile.exists()) {
                        result = new FileOperationResult(
                                OPERATION_NAME,
                                false,
                                Error.FILE_ALREADY_EXISTS,
                                "Destination file already exists and overwrite not allowed.");
                    } else {
                        fileToRename.moveTo(newFile);
                        result = new FileOperationResult(
                                OPERATION_NAME,
                                true);
                    }

                } else {

                    log.error(ERROR_MESSAGE + ". File " + fileOrFolderPath
                            + " cannot be renamed to " + newName);

                    result = new FileOperationResult(
                            OPERATION_NAME,
                            false,
                            Error.OPERATION_ERROR,
                            "Cannot rename file " + fileOrFolderPath);
                }
            } else {
                throw new IllegalPathException("File or folder does not exist " + fileOrFolderPath);
            }

            Utils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);

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
