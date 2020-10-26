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
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;

import java.io.File;

/**
 * Implements rename operation.
 */
public class RenameFiles extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String operationName = "renameFile";
        String errorMessage = "Error while performing file:rename for file/folder ";

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String fileOrFolderPath = null;
        String newName;
        FileObject fileToRename = null;
        boolean overwrite;
        FileOperationResult result;
        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            overwrite = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "overwrite"));
            newName = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "renameTo");
            fileOrFolderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FileConnectorConstants.FILE_OR_DIRECTORY_PATH);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            fileOrFolderPath = fileSystemHandler.getBaseDirectoryPath() + fileOrFolderPath;
            fileToRename = fsManager.resolveFile(fileOrFolderPath, fso);

            //path after rename
            String newFilePath = fileOrFolderPath.substring(0, fileOrFolderPath.lastIndexOf(File.separator))
                    + File.separator + newName;
            FileObject newFile = fsManager.resolveFile(newFilePath, fso);

            if (fileToRename.exists()) {

                if (fileToRename.canRenameTo(newFile)) {

                    if (!overwrite && newFile.exists()) {
                        result = new FileOperationResult(
                                operationName,
                                false,
                                Error.FILE_ALREADY_EXISTS,
                                "Destination file already exists and overwrite not allowed.");
                    } else {
                        fileToRename.moveTo(newFile);
                        result = new FileOperationResult(
                                operationName,
                                true);
                    }

                } else {

                    log.error(errorMessage + ". File " + fileOrFolderPath
                            + " cannot be renamed to " + newName);

                    result = new FileOperationResult(
                            operationName,
                            false,
                            Error.OPERATION_ERROR,
                            "Cannot rename file " + fileOrFolderPath);
                }
            } else {
                result = new FileOperationResult(
                        operationName,
                        false,
                        Error.ILLEGAL_PATH,
                        "File or folder does not exist " + fileOrFolderPath);
            }

            FileConnectorUtils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = errorMessage + fileOrFolderPath;

            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.INVALID_CONFIGURATION,
                    errorDetail);

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (FileSystemException e) {

            String errorDetail = errorMessage + fileOrFolderPath;

            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.OPERATION_ERROR,
                    errorDetail);

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (fileToRename != null) {
                try {
                    fileToRename.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + fileToRename);
                }
            }
        }
    }
}
