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
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.MergeFileResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Implements Merge Files operation.
 */
public class MergeFiles extends AbstractConnector {

    private static final String SOURCE_DIRECTORY_PATH_PARAM = "sourceDirectoryPath";
    private static final String TARGET_FILE_PATH_PARAM = "targetFilePath";
    private static final String FILE_PATTERN_PARAM = "filePattern";
    private static final String WRITE_MODE_PARAM = "writeMode";
    private static final String DETAIL_ELE_NAME = "detail";
    private static final String NUMBER_OF_MERGED_FILES_ELE_NAME = "numberOfMergedFiles";
    private static final String TOTAL_WRITTEN_BYTES_ELE_NAME = "totalWrittenBytes";
    private static final String OPERATION_NAME = "mergeFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:merge for directory ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String sourceDirectoryPath = null;
        FileObject sourceDir = null;
        String targetFilePath;
        FileObject targetFile;
        FileOperationResult result;
        int numberOfMergedFiles = 0;
        long numberOfTotalBytesWritten = 0;

        try {

            String connectionName = Utils.getConnectionName(messageContext);
            sourceDirectoryPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_DIRECTORY_PATH_PARAM);
            targetFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_FILE_PATH_PARAM);
            String filePattern = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FILE_PATTERN_PARAM);
            String writeMode = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, WRITE_MODE_PARAM);

            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            sourceDirectoryPath = fileSystemHandler.getBaseDirectoryPath() + sourceDirectoryPath;
            targetFilePath = fileSystemHandler.getBaseDirectoryPath() + targetFilePath;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            sourceDir = fsManager.resolveFile(sourceDirectoryPath, fso);
            targetFile = fsManager.resolveFile(targetFilePath, fso);

            if (!sourceDir.exists()) {
                throw new IllegalPathException("Directory not found: " + sourceDirectoryPath);
            } else {
                if (!sourceDir.isFolder()) {
                    throw new IllegalPathException("Source Path does not point to a directory: " + sourceDirectoryPath);
                }
            }

            if (!targetFile.exists()) {
                targetFile.createFile();
            } else {
                if (writeMode.equals(Const.OVERWRITE)) {
                    boolean deleteDone = targetFile.delete();           //otherwise append is done automatically
                    if (!deleteDone) {
                        throw new FileOperationException("Error while overwriting existing file " + targetFilePath);
                    }
                    targetFile.createFile();
                }
            }


            FileObject[] children = sourceDir.getChildren();

            if (children != null && children.length != 0) {
                MergeFileResult mergeFileResult = mergeFiles(targetFile, filePattern, children);
                numberOfMergedFiles = mergeFileResult.getNumberOfMergedFiles();
                numberOfTotalBytesWritten = mergeFileResult.getNumberOfTotalWrittenBytes();
            }

            OMElement fileMergeDetailEle = Utils.createOMElement(DETAIL_ELE_NAME, null);
            OMElement mergeFileCountEle = Utils.
                    createOMElement(NUMBER_OF_MERGED_FILES_ELE_NAME, Integer.toString(numberOfMergedFiles));
            OMElement totalWrittenBytesEle = Utils.
                    createOMElement(TOTAL_WRITTEN_BYTES_ELE_NAME, Long.toString(numberOfTotalBytesWritten));
            fileMergeDetailEle.addChild(mergeFileCountEle);
            fileMergeDetailEle.addChild(totalWrittenBytesEle);
            result = new FileOperationResult(OPERATION_NAME,
                    true,
                    fileMergeDetailEle);
            Utils.setResultAsPayload(messageContext, result);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourceDirectoryPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (FileOperationException | IOException e) {     //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + sourceDirectoryPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + sourceDirectoryPath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);

        } finally {

            if (sourceDir != null) {
                try {
                    sourceDir.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing folder object while merging files in "
                            + sourceDir);
                }
            }
        }
    }

    /**
     * Perform File merging.
     *
     * @param targetFile  Target file to create after merge
     * @param filePattern Specific pattern of files to merge
     * @param children    Files to merge
     * @return Info object with result of the operation
     * @throws IOException In case of file operation issue
     */
    private MergeFileResult mergeFiles(FileObject targetFile, String filePattern, FileObject[] children) throws IOException {

        int numberOfMergedFiles = 0;
        long numberOfTotalBytesWritten = 0;
        OutputStream outputStream = null;
        BufferedOutputStream bufferedOutputStream = null;

        try {
            //we'll append all in source files
            outputStream = targetFile.getContent().getOutputStream(true);
            bufferedOutputStream = new BufferedOutputStream(outputStream);

            for (FileObject child : children) {
                long numberOfBytesWritten = 0;
                if (StringUtils.isNotEmpty(filePattern)) {
                    if (child.getName().getBaseName().matches(filePattern)) {
                        numberOfBytesWritten = child.getContent().write(bufferedOutputStream);
                    }
                } else {
                    numberOfBytesWritten = child.getContent().write(bufferedOutputStream);
                }
                if (numberOfBytesWritten != 0) {
                    bufferedOutputStream.flush();
                    outputStream.flush();
                    numberOfMergedFiles = numberOfMergedFiles + 1;
                    numberOfTotalBytesWritten = numberOfTotalBytesWritten + numberOfBytesWritten;
                }

                try {
                    child.close();
                } catch (IOException e) {
                    log.warn("Error while closing a file in the source folder: " + e.getMessage(), e);
                }
            }

            return new MergeFileResult(numberOfMergedFiles, numberOfTotalBytesWritten);

        } finally {

            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.close();
                } catch (IOException e) {
                    log.error("FileConnector: MergeFiles - Error while "
                            + "closing buffered outputStream for file " + targetFile.getURL());
                }
            }

            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error("FileConnector: MergeFiles - Error while"
                            + " closing outputStream for file " + targetFile.getURL());
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
        Utils.setError(OPERATION_NAME, msgCtx, e, error, errorDetail);
        handleException(errorDetail, e, msgCtx);
    }

}
