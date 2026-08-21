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
import java.io.InputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
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
import org.apache.commons.lang.StringUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements unzip file operation
 */
public class UnzipFile extends AbstractConnectorOperation {

    private static final String SOURCE_FILE_PATH_PARAM = "sourceFilePath";
    private static final String TARGET_DIRECTORY_PARAM = "targetDirectory";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String OPERATION_NAME = "unzipFile";
    private static final String INCLUDE_FILE_NAMES = "includeFileNames";
    private static final String ERROR_MESSAGE = "Error while performing file:unzip for file ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String filePath = null;
        FileObject compressedFile = null;
        String folderPathToExtract;
        FileObject targetFolder;
        FileOperationResult result;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        String fileNameEncoding;
        String validatedFileNameEncoding;

        try {
            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);
            filePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_FILE_PATH_PARAM);
            folderPathToExtract = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_DIRECTORY_PARAM);
            fileNameEncoding = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, Const.FILE_NAME_ENCODING);
            String timeBetweenSizeCheck = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TIME_BETWEEN_SIZE_CHECK);
            boolean includeFileNames = Utils.
                    lookUpBooleanParam(messageContext, INCLUDE_FILE_NAMES, false);

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            filePath = fileSystemHandlerConnection.getBaseDirectoryPath() + filePath;
            folderPathToExtract = fileSystemHandlerConnection.getBaseDirectoryPath() + folderPathToExtract;

            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            compressedFile = fileSystemHandlerConnection.resolveFileWithSuspension(filePath);
            targetFolder = fileSystemHandlerConnection.resolveFileWithSuspension(folderPathToExtract);

            //execute validations

            if (!compressedFile.exists()) {
                throw new IllegalPathException("File not found: " + filePath);
            }

            if (!compressedFile.isFile()) {
                throw new IllegalPathException("File is not a compressed file: " + filePath);
            }

            if (!targetFolder.exists()) {
                targetFolder.createFolder();
            }
            validatedFileNameEncoding = Utils.validateEncoding(fileNameEncoding, log);

            // Check file stability if parameter is provided
            if (!StringUtils.isEmpty(timeBetweenSizeCheck) && compressedFile.isFile()) {
                if (!isFileStable(compressedFile, timeBetweenSizeCheck)) {
                    handleError(messageContext, new IllegalPathException("File is not stable (still being written). Cannot unzip at this time."),
                            Error.OPERATION_ERROR, "File is not stable (still being written). Cannot unzip at this time.",
                            responseVariable, overwriteBody);
                    return;
                }
            }

            //keep this null when not requested, so no name list is built for large archives
            JsonArray zipFileContentEle = includeFileNames ? new JsonArray() : null;

            executeDecompression(compressedFile, folderPathToExtract, validatedFileNameEncoding,
                    fileSystemHandlerConnection, zipFileContentEle);

            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            if (zipFileContentEle != null) {
                resultJSON.add(Const.ZIP_FILE_CONTENT_ELEMENT, zipFileContentEle);
            }
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + filePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

        } finally {
            if (compressedFile != null) {
                try {
                    compressedFile.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing file object while decompressing the file. "
                            + compressedFile);
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
     * Execute decompression, iterating over compressed entries.
     *
     * @param sourceFile                   Compressed file
     * @param folderPathToExtract          Directory path to decompress
     * @param fileNameEncoding             Encoding to interpret compressed entry names with
     * @param fileSystemHandlerConnection  File system handler associated with the file connection
     * @param zipFileContentEle            Array to collect extracted file names into, or null to skip collecting
     * @throws IOException In case of I/O error
     */
    private void executeDecompression(FileObject sourceFile,
                                      String folderPathToExtract,
                                      String fileNameEncoding,
                                      FileSystemHandler fileSystemHandlerConnection,
                                      JsonArray zipFileContentEle) throws IOException {
        //execute decompression
        String fileExtension = sourceFile.getName().getExtension();
        if (fileExtension.equals("gz")) {
            String targetName = sourceFile.getName().getBaseName()
                    .replace("." + sourceFile.getName().getExtension(), "");
            FileObject target = fileSystemHandlerConnection.resolveFileWithSuspension(
                    folderPathToExtract + Const.FILE_SEPARATOR + targetName);
            extractGzip(sourceFile, target);
            if (zipFileContentEle != null) {
                zipFileContentEle.add(targetName);
            }
            return;
        }

        try (InputStream inputStream = sourceFile.getContent().getInputStream();
             ZipArchiveInputStream zipIn = new ZipArchiveInputStream(inputStream, fileNameEncoding, true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextZipEntry()) != null) {
                String zipEntryPath = folderPathToExtract + Const.FILE_SEPARATOR + entry.getName();
                FileObject zipEntryTargetFile = fileSystemHandlerConnection.resolveFileWithSuspension(zipEntryPath);
                if (!entry.isDirectory()) {
                    // if the entry is a file, extracts it
                    extractFile(zipIn, zipEntryTargetFile);
                    if (zipFileContentEle != null) {
                        zipFileContentEle.add(entry.getName());
                    }
                } else {
                    // if the entry is a directory, make the directory
                    zipEntryTargetFile.createFolder();
                }
            }
        }
    }

    public static void extractGzip(FileObject source, FileObject target) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(source.getContent().getInputStream());
             OutputStream fos = target.getContent().getOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    /**
     * Extract Zip entry and write to file.
     *
     * @param zipIn              ZipInputStream to zip entry
     * @param zipEntryTargetFile FileObject pointing to extracted file related to zip entry
     * @throws IOException In case of I/O error
     */
    private void extractFile(ZipArchiveInputStream zipIn, FileObject zipEntryTargetFile) throws IOException {
        BufferedOutputStream bos = null;
        OutputStream fOut = null;
        try {
            //open the zip file
            fOut = zipEntryTargetFile.getContent().getOutputStream();
            bos = new BufferedOutputStream(fOut);
            byte[] bytesIn = new byte[Const.UNZIP_BUFFER_SIZE];
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
                    log.error("FileConnector:Unzip Error while closing the BufferedOutputStream to target file: "
                            + e.getMessage(), e);
                }
            }
            if (fOut != null) {
                try {
                    fOut.close();
                } catch (IOException e) {
                    log.error("Error while closing the OutputStream to target file: "
                            + e.getMessage(), e);
                }
            }
            zipEntryTargetFile.close();
        }
    }

    /**
     * Check if file is stable (not being written to) by comparing file sizes
     * over a specified interval.
     * 
     * @param file File to check for stability
     * @param sizeCheckInterval Time in milliseconds to wait between size checks
     * @return true if file is stable, false if still being written
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
            
            // Re-read file size and compare
            long finalSize = file.getContent().getSize();
            
            // File is stable if size hasn't changed
            return initialSize == finalSize;
            
        } catch (NumberFormatException e) {
            // If we can't parse the interval, assume file is stable
            log.warn("Invalid timeBetweenSizeCheck value: " + sizeCheckInterval + ". Skipping stability check.");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // If interrupted, assume file is stable
            return true;
        } catch (Exception e) {
            // If we can't check stability, assume file is stable
            log.warn("Error checking file stability: " + e.getMessage() + ". Assuming file is stable.");
            return true;
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
