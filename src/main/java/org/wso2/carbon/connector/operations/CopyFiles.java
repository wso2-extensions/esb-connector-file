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

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSelector;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.Selectors;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileAlreadyExistsException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.SimpleFileSelector;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Implements copy files operation.
 */
public class CopyFiles extends AbstractConnector {

    private static final String SOURCE_PATH = "sourcePath";
    private static final String TARGET_PATH = "targetPath";
    private static final String SOURCE_FILE_PATTERN_PARAM = "sourceFilePattern";
    private static final String INCLUDE_PARENT_PARAM = "includeParent";
    private static final String OVERWRITE_PARAM = "overwrite";
    private static final String RENAME_TO_PARAM = "renameTo";

    private static final String OPERATION_NAME = "copyFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:copy for file/folder ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String sourcePath = null;
        String targetPath = null;
        String sourceFilePattern;
        boolean includeParent;
        boolean overwrite;
        String renameTo;
        FileObject sourceFile = null;
        FileSelector fileSelector;
        int maxRetries;
        int retryDelay;
        int attempt = 0;
        boolean successOperation = false;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        //read max retries and retry delay
        try {
            maxRetries = Integer.parseInt((String) ConnectorUtils.lookupTemplateParamater(messageContext,
                    Const.MAX_RETRY_PARAM));
            retryDelay = Integer.parseInt((String) ConnectorUtils.lookupTemplateParamater(messageContext,
                    Const.RETRY_DELAY_PARAM));
            if (log.isDebugEnabled()) {
                log.debug("Max retries: " + maxRetries + " Retry delay: " + retryDelay);
            }
        } catch (Exception e) {
            maxRetries = 0;
            retryDelay = 0;
        }
        while (attempt <= maxRetries && !successOperation) {
            try {

                fileSystemHandlerConnection = (FileSystemHandler) handler
                        .getConnection(Const.CONNECTOR_NAME, connectionName);
                FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
                FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
                //read inputs
                sourcePath = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, SOURCE_PATH);
                targetPath = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, TARGET_PATH);
                sourceFilePattern = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, SOURCE_FILE_PATTERN_PARAM);
                includeParent = Boolean.parseBoolean((String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, INCLUDE_PARENT_PARAM));
                overwrite = Boolean.parseBoolean((String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, OVERWRITE_PARAM));
                renameTo = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, RENAME_TO_PARAM);

                sourcePath = fileSystemHandlerConnection.getBaseDirectoryPath() + sourcePath;
                targetPath = fileSystemHandlerConnection.getBaseDirectoryPath() + targetPath;

                if (log.isDebugEnabled()) {
                    log.debug("Copying file/folder from " + sourcePath + " to " + targetPath);
                }
                if (StringUtils.isNotEmpty(sourceFilePattern)) {
                    fileSelector = new SimpleFileSelector(sourceFilePattern);
                } else {
                    fileSelector = Selectors.SELECT_ALL;
                }

                //execute copy
                sourceFile = fsManager.resolveFile(sourcePath, fso);
                if (sourceFile.exists()) {

                    if (sourceFile.isFile()) {

                        String targetFilePath;

                        //check if we have given a new name
                        if (StringUtils.isNotEmpty(renameTo)) {
                            targetFilePath = targetPath + Const.FILE_SEPARATOR
                                    + renameTo;
                        } else {
                            targetFilePath = targetPath + Const.FILE_SEPARATOR
                                    + sourceFile.getName().getBaseName();
                        }

                        FileObject targetFile = fsManager.resolveFile(targetFilePath, fso);
                        boolean success = copyFile(sourceFile, fileSelector, targetFile, overwrite);
                        FileOperationResult result;
                        if (success) {
                            result = new FileOperationResult(
                                    OPERATION_NAME,
                                    true);
                            Utils.setResultAsPayload(messageContext, result, Utils.
                                    lookUpStringParam(messageContext, Const.RESPONSE_VARIABLE, Const.EMPTY_STRING));
                        } else {
                            throw new FileAlreadyExistsException("Destination file already "
                                    + "exists and overwrite not allowed");
                        }

                    } else {
                        //in case of folder
                        if (includeParent) {
                            if (StringUtils.isNotEmpty(renameTo)) {
                                targetPath = targetPath + Const.FILE_SEPARATOR + renameTo;
                            } else {
                                String sourceParentFolderName = sourceFile.getName().getBaseName();
                                targetPath = targetPath + Const.FILE_SEPARATOR + sourceParentFolderName;
                            }
                        }

                        FileObject targetFile = fsManager.resolveFile(targetPath, fso);

                        boolean success = copyFolder(sourceFile, fileSelector, targetFile, overwrite);
                        FileOperationResult result;
                        if (success) {
                            result = new FileOperationResult(
                                    OPERATION_NAME,
                                    true);
                            Utils.setResultAsPayload(messageContext, result, Utils.
                                    lookUpStringParam(messageContext, Const.RESPONSE_VARIABLE, Const.EMPTY_STRING));
                        } else {
                            throw new FileAlreadyExistsException("Folder or one or more "
                                    + "sub-directories already exists and overwrite not allowed");
                        }
                    }

                } else {
                    throw new IllegalPathException("File/Folder does not exist : " + sourcePath);
                }
                successOperation = true;
            } catch (InvalidConfigurationException e) {

                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);
            } catch (FileSystemException e) {
                log.error(e);
                Utils.closeFileSystem(sourceFile);
                if (attempt >= maxRetries - 1) {
                    String errorDetail = ERROR_MESSAGE + sourcePath;
                    handleError(messageContext, e, Error.RETRY_EXHAUSTED, errorDetail);
                }
                // Log the retry attempt
                log.warn(Const.CONNECTOR_NAME + ":Error while copying file/folder "
                        + sourcePath + ". Retrying after " + retryDelay + " milliseconds retry attempt " + (attempt + 1)
                        + " out of " + maxRetries);
                attempt++;
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    handleError(messageContext, ie, Error.OPERATION_ERROR, ERROR_MESSAGE + sourcePath);
                }
            } catch (FileAlreadyExistsException e) {
                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.FILE_ALREADY_EXISTS, errorDetail);
            } catch (IllegalPathException e) {
                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);
            } catch (Exception e) {
                log.error("Exception while copying file/folder " + sourcePath + ". Error: " + e.getMessage());
                Utils.closeFileSystem(sourceFile);
                if (attempt >= maxRetries - 1) {
                    handleError(messageContext, e, Error.RETRY_EXHAUSTED, ERROR_MESSAGE + sourcePath);
                }
                // Log the retry attempt
                log.warn(Const.CONNECTOR_NAME + ":Error while copying file/folder "
                        + sourcePath + ". Retrying after " + retryDelay + " milliseconds retry attempt " + (attempt + 1)
                        + " out of " + maxRetries);
                attempt++;
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    handleError(messageContext, ie, Error.OPERATION_ERROR, ERROR_MESSAGE + sourcePath);
                }
            } finally {

                if (sourceFile != null) {
                    try {
                        sourceFile.close();
                    } catch (FileSystemException e) {
                        log.error(Const.CONNECTOR_NAME
                                + ":Error while closing file object"
                                + sourcePath);
                    }
                }
                if (handler.getStatusOfConnection(Const.CONNECTOR_NAME, connectionName)) {
                    if (fileSystemHandlerConnection != null) {
                        handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
                    }
                }
            }
        }
    }


    /**
     * Copies srcFile, to destinationFile file.
     * If destination file does not exist, it is created. Its parent folder is also
     * created, if necessary. If this file destination exist, and overWrite = true,
     * it is deleted first, otherwise it returns false.
     *
     * @param srcFile         source file object
     * @param selector        The selector to use to select which files to copy
     * @param destinationFile destination file object
     * @return true if operation is done. False if overwrite is not allowed
     * @throws FileSystemException If this file is read-only,
     *                             or if the source file does
     *                             not exist, or on error copying the file.
     */
    private boolean copyFile(FileObject srcFile, FileSelector selector,
                             FileObject destinationFile, boolean overWrite) throws FileSystemException {

        if (!overWrite && destinationFile.exists()) {
            return false;
        } else {
            destinationFile.copyFrom(srcFile, selector);
            return true;
        }
    }

    /**
     * Copies src directory and all its descendants, , to destinationFile folder.
     * If destination file does not exist, it is created. Its parent folder is also
     * created, if necessary. If overWrite = false, operation checks first level of
     * children and if there is a match returns false (operation unsuccessful).
     * Otherwise it overwrites whole folder.
     *
     * @param srcFile         Source directory to copy
     * @param selector        Select files to copy based on this
     * @param destinationFile Destination directory
     * @param overWrite       true if allow to overwrite
     * @return True if copying is performed
     * @throws FileSystemException If this file is read-only,
     *                             or if the source file does
     *                             not exist, or on error copying the file.
     */
    private boolean copyFolder(FileObject srcFile, FileSelector selector,
                               FileObject destinationFile, boolean overWrite) throws FileSystemException {

        if (destinationFile.exists() && !overWrite) {

            //we check only one level
            FileObject[] sourceFileChildren = srcFile.getChildren();
            FileObject[] destinationFileChildren = destinationFile.getChildren();
            ArrayList<String> sourceChildrenNames = new ArrayList<>(sourceFileChildren.length);
            ArrayList<String> destinationChildrenNames = new ArrayList<>(destinationFileChildren.length);
            for (FileObject child : sourceFileChildren) {
                sourceChildrenNames.add(child.getName().getBaseName());
            }
            for (FileObject child : destinationFileChildren) {
                destinationChildrenNames.add(child.getName().getBaseName());
            }

            Collection commonFiles = CollectionUtils.intersection(sourceChildrenNames, destinationChildrenNames);
            if (!commonFiles.isEmpty()) {
                return false;
            }

        }

        destinationFile.copyFrom(srcFile, selector);
        return true;
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
