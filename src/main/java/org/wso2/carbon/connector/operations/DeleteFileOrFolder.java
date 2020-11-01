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
import org.apache.commons.vfs2.FileFilter;
import org.apache.commons.vfs2.FileFilterSelector;
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
import org.wso2.carbon.connector.utils.SimpleFileFiler;

/**
 * Implements delete file or folder operation
 */
public class DeleteFileOrFolder extends AbstractConnector {

    private static final String MATCHING_PATTERN_PARAM = "matchingPattern";
    private static final String NUM_OF_DELETED_FILES_ELE = "numOfDeletedFiles";
    private static final String DELETE_FILE = "deleteFile";
    private static final String ERROR_MESSAGE = "Error while performing file:delete for file/folder ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String fileOrFolderPath = null;
        FileObject fileObjectToDelete = null;
        String fileMatchingPattern;
        boolean isOperationSuccessful = false;
        FileOperationResult result = null;
        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            fileMatchingPattern = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, MATCHING_PATTERN_PARAM);
            fileOrFolderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FileConnectorConstants.FILE_OR_DIRECTORY_PATH);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            fileOrFolderPath = fileSystemHandler.getBaseDirectoryPath() + fileOrFolderPath;
            fileObjectToDelete = fsManager.resolveFile(fileOrFolderPath, fso);

            //Deletes this file. Does nothing if this file does not exist
            if (fileObjectToDelete.isFile()) {
                isOperationSuccessful = fileObjectToDelete.delete();
                result = new FileOperationResult(
                        DELETE_FILE,
                        isOperationSuccessful);
            }

            if (fileObjectToDelete.isFolder()) {
                int numberOfDeletedFiles;
                if (StringUtils.isNotEmpty(fileMatchingPattern)) {
                    FileFilter fileFilter = new SimpleFileFiler(fileMatchingPattern);
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
                OMElement numOfDeletedFilesEle = FileConnectorUtils.
                        createOMElement(NUM_OF_DELETED_FILES_ELE,
                                Integer.toString(numberOfDeletedFiles));
                result = new FileOperationResult(
                        DELETE_FILE,
                        isOperationSuccessful,
                        numOfDeletedFilesEle);
            }

            FileConnectorUtils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;

            result = new FileOperationResult(
                    DELETE_FILE,
                    false,
                    Error.INVALID_CONFIGURATION,
                    errorDetail);

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + fileOrFolderPath;

            result = new FileOperationResult(
                    DELETE_FILE,
                    false,
                    Error.OPERATION_ERROR,
                    errorDetail);

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (fileObjectToDelete != null) {
                try {
                    fileObjectToDelete.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + fileObjectToDelete);
                }
            }
        }
    }
}
