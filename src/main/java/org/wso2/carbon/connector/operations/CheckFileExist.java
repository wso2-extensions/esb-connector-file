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
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import java.io.IOException;

/**
 * Implements Check File Exists operation.
 */
public class CheckFileExist extends AbstractConnector {

    private static final String PATH_PARAM = "path";
    private static final String INCLUDE_RESULT_AT_PARAM = "includeResultTo";
    private static final String RESULT_PROPERTY_NAME_PARAM = "resultPropertyName";
    private static final String OPERATION_NAME = "checkExist";
    private static final String ERROR_MESSAGE = "Error while performing file:checkExist for file/directory ";
    private static final String FILE_EXISTS_ELE_NAME = "fileExists";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String filePath = null;
        FileObject fileObject = null;
        FileOperationResult result;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {

            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, PATH_PARAM);

            if (StringUtils.isEmpty(filePath)) {
                throw new InvalidConfigurationException("Parameter '" + PATH_PARAM + "' is not provided ");
            }

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandlerConnection.getBaseDirectoryPath() + filePath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            fileObject = fsManager.resolveFile(filePath, fso);
            /*
                Temporarily reverting this fix with expensive file system resolve calls.
                No git issue is pointed in this commit. So  we cannot find a better solution which resolve both issues.
             */
            //fsManager.getFilesCache().removeFile(fileObject.getFileSystem(),  fileObject.getName());
            //fsManager.getFilesCache().removeFile(fileObject.getParent().getFileSystem(),  fileObject.getParent().getName());
            //fileObject = fsManager.resolveFile(filePath, fso);


            String operationResult;
            if (fileObject.exists()) {
                operationResult = Boolean.toString(Boolean.TRUE);
            } else {
                operationResult = Boolean.toString(Boolean.FALSE);
            }

            OMElement fileExistsEle = Utils.
                    createOMElement(FILE_EXISTS_ELE_NAME, operationResult);
            result = new FileOperationResult(OPERATION_NAME,
                    true,
                    fileExistsEle);

            String injectOperationResultAt = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, INCLUDE_RESULT_AT_PARAM);

            if (injectOperationResultAt.equals(Const.MESSAGE_BODY)) {
                Utils.setResultAsPayload(messageContext, result);
            } else if (injectOperationResultAt.equals(Const.MESSAGE_PROPERTY)) {
                String resultPropertyName = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, RESULT_PROPERTY_NAME_PARAM);
                if (StringUtils.isNotEmpty(resultPropertyName)) {
                    messageContext.setProperty(resultPropertyName, operationResult);
                } else {
                    throw new InvalidConfigurationException("Property name to set operation result is required");
                }
            } else {
                throw new InvalidConfigurationException("Parameter 'includeResultAt' is mandatory");
            }


        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } finally {

            if (fileObject != null) {
                try {
                    fileObject.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing folder object while merging files in "
                            + fileObject);
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
