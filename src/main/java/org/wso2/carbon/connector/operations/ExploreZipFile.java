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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang.StringUtils;
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

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements ExploreZip File operation. This goes through
 * items in the zip file without extracting it.
 */
public class ExploreZipFile extends AbstractConnectorOperation {

    private static final String ZIP_FILE_PATH = "zipFilePath";
    private static final String ZIP_FILE_CONTENT_ELE = "zipFileContent";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String OPERATION_NAME = "exploreZipFile";
    private static final String ERROR_MESSAGE = "Error while performing file:exploreZipFile for file ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String filePath = null;
        FileObject zipFile = null;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {

            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);

            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, ZIP_FILE_PATH);
            
            String timeBetweenSizeCheckStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TIME_BETWEEN_SIZE_CHECK);

            if (StringUtils.isEmpty(filePath)) {
                throw new InvalidConfigurationException("Parameter '" + ZIP_FILE_PATH + "' is not provided ");
            }

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandlerConnection.getBaseDirectoryPath() + filePath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            zipFile = fileSystemHandlerConnection.resolveFileWithSuspension(filePath);

            if (!zipFile.exists()) {
                throw new IllegalPathException("Zip file not found at path " + filePath);
            }

            // Check file stability if timeBetweenSizeCheck is provided
            if (StringUtils.isNotEmpty(timeBetweenSizeCheckStr) && !isFileStable(zipFile, timeBetweenSizeCheckStr)) {
                throw new ConnectException("Zip file is still being modified. Cannot explore: " + filePath);
            }

            JsonArray zipFileContentEle = new JsonArray();

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
                        zipFileContentEle.add(zipEntryName);
                    }
                }
            }

            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            resultJSON.add(ZIP_FILE_CONTENT_ELE, zipFileContentEle);
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);


        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

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
                    Utils.addMaxAccessMaskToFSO(fileSystemHandlerConnection.getFsOptions());
                    handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
                }
            }
        }
    }

    /**
     * Checks if a file is stable by comparing its size over time.
     *
     * @param file The file to check for stability
     * @param timeBetweenSizeCheckStr Time to wait between size checks (e.g., "2s", "1000ms")
     * @return true if file size is stable, false otherwise
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
            
            // Refresh and check size again
            file.refresh();
            long finalSize = file.getContent().getSize();
            
            return initialSize == finalSize;
        } catch (Exception e) {
            log.warn("Error checking file stability for " + file + ": " + e.getMessage());
            return true; // Assume stable if we can't check
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
