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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implements unzip file operation
 */
public class UnzipFile extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String operationName = "unzipFile";
        String errorMessage = "Error while performing file:unzip for file ";

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String filePath = null;
        FileObject zipFile = null;
        String folderPathToExtract;
        FileObject targetFolder;
        FileOperationResult result;

        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "sourceFilePath");
            folderPathToExtract = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "targetDirectory");


            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandler.getBaseDirectoryPath() + filePath;
            folderPathToExtract = fileSystemHandler.getBaseDirectoryPath() + folderPathToExtract;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            zipFile = fsManager.resolveFile(filePath, fso);
            targetFolder = fsManager.resolveFile(folderPathToExtract, fso);

            //execute validations

            if (!zipFile.exists()) {
                result = new FileOperationResult(
                        operationName,
                        false,
                        Error.ILLEGAL_PATH,
                        "File not found: " + filePath);
                FileConnectorUtils.setResultAsPayload(messageContext, result);
                return;
            }

            if (!zipFile.isFile()) {
                result = new FileOperationResult(
                        operationName,
                        false,
                        Error.ILLEGAL_PATH,
                        "File is not a zip file: " + filePath);
                FileConnectorUtils.setResultAsPayload(messageContext, result);
                return;
            }

            if (!targetFolder.exists()) {
                targetFolder.createFolder();
            }

            OMElement zipFileContentEle = executeUnzip(zipFile, folderPathToExtract, fsManager, fso);

            result = new FileOperationResult(operationName, true, zipFileContentEle);

            FileConnectorUtils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = errorMessage + filePath;

            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.INVALID_CONFIGURATION,
                    errorDetail);

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = errorMessage + filePath;

            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.OPERATION_ERROR,
                    errorDetail);

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing file object while unzipping file. "
                            + zipFile);
                }
            }
        }
    }

    private OMElement executeUnzip(FileObject zipFile, String folderPathToExtract,
                                   FileSystemManager fsManager, FileSystemOptions fso) throws IOException {
        //execute unzip
        ZipInputStream zipIn = null;
        OMElement zipFileContentEle = FileConnectorUtils.
                createOMElement("zipFileContent", null);
        try {
            zipIn = new ZipInputStream(zipFile.getContent().getInputStream());
            ZipEntry entry = zipIn.getNextEntry();
            //iterate over the entries
            while (entry != null) {
                String zipEntryPath = folderPathToExtract + File.separator + entry.getName();
                //TODO: check if we need to use a different manager to resolve file here (support fix)
                FileObject zipEntryTargetFile = fsManager.resolveFile(zipEntryPath, fso);
                try {
                    if (!entry.isDirectory()) {
                        // if the entry is a file, extracts it
                        extractFile(zipIn, zipEntryTargetFile);
                        //TODO:is there a better character?
                        String entryName = entry.getName().replace("/", "--");
                        OMElement zipEntryEle = FileConnectorUtils.
                                createOMElement(entryName, "extracted");
                        zipFileContentEle.addChild(zipEntryEle);
                    } else {
                        // if the entry is a directory, make the directory
                        zipEntryTargetFile.createFolder();
                    }
                } catch (IOException e) {
                    log.error("Unable to process the zip file. ", e);
                } finally {
                    try {
                        zipIn.closeEntry();
                    } finally {
                        entry = zipIn.getNextEntry();
                    }
                }
            }
            return zipFileContentEle;
        } finally {
            if (zipIn != null) {
                zipIn.close();
            }
        }
    }

    //TODO: check exception handling
    private void extractFile(ZipInputStream zipIn, FileObject zipEntryTargetFile) throws IOException {
        BufferedOutputStream bos = null;
        OutputStream fOut = null;
        try {
            //open the zip file
            fOut = zipEntryTargetFile.getContent().getOutputStream();
            bos = new BufferedOutputStream(fOut);
            byte[] bytesIn = new byte[FileConnectorConstants.UNZIP_BUFFER_SIZE];
            int read;
            while ((read = zipIn.read(bytesIn)) != -1) {
                bos.write(bytesIn, 0, read);
            }
            bos.flush();
        } finally {
            //we must always close the zip file
            if (bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    log.error("FileConnector:Unzip Error while closing the BufferedOutputStream to target file: " + e.getMessage(), e);
                } finally {
                    try {
                        fOut.close();
                    } catch (IOException e) {
                        log.error("Error while closing the OutputStream to target file: " + e.getMessage(), e);
                    }
                }
            }
            if(fOut != null) {
                fOut.close();
            }
            zipEntryTargetFile.close();
        }
    }
}
