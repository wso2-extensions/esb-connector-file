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
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implements ExploreZip File operation. This goes through
 * items in the zip file without extracting it.
 */
public class ExploreZipFile extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String operationName = "exploreZipFile";
        String errorMessage = "Error while performing file:exploreZipFile for file ";

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String filePath = null;
        FileObject zipFile = null;
        FileOperationResult result;

        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "zipFilePath");

            if (StringUtils.isEmpty(filePath)) {
                throw new InvalidConfigurationException("Parameter 'zipFilePath' is not provided ");
            }

            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandler.getBaseDirectoryPath() + filePath;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            zipFile = fsManager.resolveFile(filePath, fso);

            if (!zipFile.exists()) {
                throw new IllegalPathException("Zip file not found at path " + filePath);
            }

            OMElement zipFileContentEle = FileConnectorUtils.
                    createOMElement("zipFileContent", null);

            // open the zip file
            InputStream input = zipFile.getContent().getInputStream();

            //Java handles zip.close() - automatic resource mgt
            try (ZipInputStream zip = new ZipInputStream(input)) {
                String zipEntryName;
                ZipEntry zipEntry;
                // iterates over entries in the zip file
                while ((zipEntry = zip.getNextEntry()) != null) {
                    if (!zipEntry.isDirectory()) {
                        zipEntryName = zipEntry.getName();
                        OMElement zipEntryEle = FileConnectorUtils.
                                createOMElement(FileConnectorConstants.FILE, zipEntryName);
                        zipFileContentEle.addChild(zipEntryEle);
                    }
                }
            }

            result = new FileOperationResult(operationName,
                    true,
                    zipFileContentEle);
            FileConnectorUtils.setResultAsPayload(messageContext, result);


        } catch (InvalidConfigurationException e) {

            String errorDetail = errorMessage + filePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.INVALID_CONFIGURATION,
                    e.getMessage());

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (IllegalPathException e) {
            String errorDetail = errorMessage + filePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.ILLEGAL_PATH,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = errorMessage + filePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.OPERATION_ERROR,
                    e.getMessage());

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing zip file object while zip file explore in "
                            + zipFile);
                }
            }
        }
    }
}
