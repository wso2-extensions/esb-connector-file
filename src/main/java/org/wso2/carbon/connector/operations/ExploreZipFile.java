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
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implements ExploreZip File operation. This goes through
 * items in the zip file without extracting it.
 */
public class ExploreZipFile extends AbstractConnector {

    private static final String ZIP_FILE_PATH = "zipFilePath";
    private static final String ZIP_FILE_CONTENT_ELE = "zipFileContent";
    private static final String OPERATION_NAME = "exploreZipFile";
    private static final String ERROR_MESSAGE = "Error while performing file:exploreZipFile for file ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String filePath = null;
        FileObject zipFile = null;
        FileOperationResult result;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {

            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, ZIP_FILE_PATH);

            if (StringUtils.isEmpty(filePath)) {
                throw new InvalidConfigurationException("Parameter '" + ZIP_FILE_PATH + "' is not provided ");
            }

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandlerConnection.getBaseDirectoryPath() + filePath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            zipFile = fsManager.resolveFile(filePath, fso);

            if (!zipFile.exists()) {
                throw new IllegalPathException("Zip file not found at path " + filePath);
            }

            OMElement zipFileContentEle = Utils.
                    createOMElement(ZIP_FILE_CONTENT_ELE, null);

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
                        OMElement zipEntryEle = Utils.
                                createOMElement(Const.FILE, zipEntryName);
                        zipFileContentEle.addChild(zipEntryEle);
                    }
                }
            }

            result = new FileOperationResult(OPERATION_NAME,
                    true,
                    zipFileContentEle);
            Utils.setResultAsPayload(messageContext, result);


        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } finally {

            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing zip file object while zip file explore in "
                            + zipFile);
                }
            }
            if (handler.getStatusOfConnection(Const.CONNECTOR_NAME, connectionName)) {
                if (fileSystemHandlerConnection != null) {
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
