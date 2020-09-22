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
import org.apache.commons.io.filefilter.RegexFileFilter;
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

import java.util.List;

/**
 * Implements File listing capability in a directory
 */
public class ListFiles extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String folderPath = null;
        FileObject folder = null;
        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();

            folderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FileConnectorConstants.DIRECTORY_PATH);
            folderPath = fileSystemHandler.getBaseDirectoryPath() + folderPath;
            folder = fsManager.resolveFile(folderPath, fso);

            //TODO: check if folder exists? test and see
            if(!folder.isFolder()) {
                FileOperationResult result = new FileOperationResult("listFiles",
                        false, Error.ILLEGAL_PATH);
                FileConnectorUtils.setResultAsPayload(messageContext, result);
                handleException("Error while performing file:listFiles for folder"
                        + folderPath, messageContext);

            } else {

            }

        } catch (InvalidConfigurationException e) {
            FileOperationResult result = new FileOperationResult("listFiles",
                    false, Error.INVALID_CONFIGURATION);
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException("Error while performing file:listFiles for folder"
                    + folderPath, e, messageContext);
        } catch (FileSystemException e) {
            FileOperationResult result = new FileOperationResult("listFiles",
                    false, Error.OPERATION_ERROR);
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException("Error while performing file:listFiles for folder"
                    + folderPath, e, messageContext);
        }
        finally {

        }
    }

//    private OMElement listFilesInFolder(FileObject folder, String pattern) {
//
//    }
//
//    /**
//     * Finds the set of matching descendants of this file.
//     *
//     * @param pattern
//     * @param filesList
//     */
//    private void listFiles(FileObject folder, String pattern, List<FileObject> filesList) {
//        FileFilterSelector fileFilterSelector = new FileFilterSelector(new RegexFileFilter("Ë†.*[tT]est(-\\d+)?\\.java$"));
//    }
}
