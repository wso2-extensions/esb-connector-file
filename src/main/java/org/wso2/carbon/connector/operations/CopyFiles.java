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
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;
import org.wso2.carbon.connector.utils.SimpleFileSelector;

import java.io.File;
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

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String sourcePath = null;
        String targetPath = null;
        String sourceFilePattern;
        boolean includeParent;
        boolean overwrite;
        String renameTo;
        FileObject sourceFile = null;
        FileSelector fileSelector;

        try {

            //get connection
            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();

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

            sourcePath = fileSystemHandler.getBaseDirectoryPath() + sourcePath;
            targetPath = fileSystemHandler.getBaseDirectoryPath() + targetPath;

            if(StringUtils.isNotEmpty(sourceFilePattern)) {
                fileSelector = new SimpleFileSelector(sourceFilePattern);
            } else {
                fileSelector =  Selectors.SELECT_ALL;
            }

            //execute copy
            sourceFile = fsManager.resolveFile(sourcePath, fso);

            if(sourceFile.exists()) {

                if(sourceFile.isFile()) {

                    String targetFilePath;

                    //check if we have given a new name
                    if(StringUtils.isNotEmpty(renameTo)) {
                        targetFilePath = targetPath + FileConnectorConstants.FILE_SEPARATOR + renameTo;
                    } else {
                        targetFilePath = targetPath + FileConnectorConstants.FILE_SEPARATOR + sourceFile.getName().getBaseName();
                    }

                    FileObject targetFile = fsManager.resolveFile(targetFilePath, fso);
                    boolean success = copyFile(sourceFile, fileSelector, targetFile, overwrite);
                    FileOperationResult result;
                    if(success) {
                         result = new FileOperationResult(
                                 OPERATION_NAME,
                                true);
                    } else {
                         result = new FileOperationResult(
                                 OPERATION_NAME,
                                false,
                                 Error.FILE_ALREADY_EXISTS,
                                "Destination file already exists and overwrite not allowed");
                    }
                    FileConnectorUtils.setResultAsPayload(messageContext, result);

                } else {
                    //in case of folder
                    if(includeParent) {
                        if(StringUtils.isNotEmpty(renameTo)) {
                            targetPath = targetPath + FileConnectorConstants.FILE_SEPARATOR + renameTo;
                        } else {
                            String sourceParentFolderName = sourceFile.getName().getBaseName();
                            targetPath = targetPath + FileConnectorConstants.FILE_SEPARATOR + sourceParentFolderName;
                        }
                    }

                    FileObject targetFile = fsManager.resolveFile(targetPath, fso);

                    boolean success = copyFolder(sourceFile, fileSelector, targetFile, overwrite);
                    FileOperationResult result;
                    if(success) {
                        result = new FileOperationResult(
                                OPERATION_NAME,
                                true);
                    } else {
                        result = new FileOperationResult(
                                OPERATION_NAME,
                                false,
                                Error.FILE_ALREADY_EXISTS,
                                "Folder or one or more sub-directories already exists and overwrite not allowed");
                    }
                    FileConnectorUtils.setResultAsPayload(messageContext, result);
                }

            } else {
                String errorDetail = ERROR_MESSAGE + sourcePath + ". File/Folder does not exist.";
                FileOperationResult result = new FileOperationResult(
                        OPERATION_NAME,
                        false,
                        Error.ILLEGAL_PATH,
                        errorDetail);
                FileConnectorUtils.setResultAsPayload(messageContext, result);
                handleException(errorDetail, messageContext);
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            FileOperationResult result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.INVALID_CONFIGURATION,
                    errorDetail);
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            FileOperationResult result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.OPERATION_ERROR,
                    errorDetail);
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (sourceFile != null) {
                try {
                    sourceFile.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing file object"
                            + sourcePath);
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

        if(!overWrite && destinationFile.exists()) {
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
            //TODO: if not to include parent
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
            //TODO: takes some time to execute
            Collection commonFiles = CollectionUtils.intersection(sourceChildrenNames, destinationChildrenNames);
            if (!commonFiles.isEmpty()) {
                return false;
            }

        }

        destinationFile.copyFrom(srcFile, selector);
        return true;
    }

}
