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
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileAlreadyExistsException;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Implements move files operation.
 */
public class MoveFiles extends AbstractConnector {

    private static final String SOURCE_PATH_PARAM = "sourcePath";
    private static final String TARGET_PATH_PARAM = "targetPath";
    private static final String CREATE_PARENT_DIRECTORIES_PARAM = "createParentDirectories";
    private static final String INCLUDE_PARENT_PARAM = "includeParent";
    private static final String OVERWRITE_PARAM = "overwrite";
    private static final String RENAME_TO_PARAM = "renameTo";
    private static final String OPERATION_NAME = "moveFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:move for file/folder ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String sourcePath = null;
        String targetPath = null;
        boolean createNonExistingParents;
        boolean includeParent;
        boolean overwrite;
        String renameTo;
        FileObject sourceFile = null;

        try {

            //get connection
            String connectionName = Utils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();

            //read inputs
            sourcePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_PATH_PARAM);
            targetPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_PATH_PARAM);
            createNonExistingParents = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, CREATE_PARENT_DIRECTORIES_PARAM));
            includeParent = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, INCLUDE_PARENT_PARAM));
            overwrite = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, OVERWRITE_PARAM));
            renameTo = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, RENAME_TO_PARAM);

            sourcePath = fileSystemHandler.getBaseDirectoryPath() + sourcePath;
            targetPath = fileSystemHandler.getBaseDirectoryPath() + targetPath;

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
                    boolean success = moveFile(sourceFile, createNonExistingParents, targetFile, overwrite);
                    FileOperationResult result;
                    if (success) {
                        result = new FileOperationResult(
                                OPERATION_NAME,
                                true);
                        Utils.setResultAsPayload(messageContext, result);
                    } else {
                        throw new FileAlreadyExistsException("Destination file already exists and overwrite not allowed");
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

                    boolean success = moveFolder(sourceFile, createNonExistingParents, targetFile, overwrite);
                    FileOperationResult result;
                    if (success) {
                        result = new FileOperationResult(
                                OPERATION_NAME,
                                true);
                        Utils.setResultAsPayload(messageContext, result);
                    } else {
                        throw new FileAlreadyExistsException("Folder or one or more sub-directories "
                                + "already exists and overwrite not allowed");
                    }
                }

            } else {
                throw new FileOperationException("File/Folder does not exist.");
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (FileAlreadyExistsException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.FILE_ALREADY_EXISTS, errorDetail);

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
        }
    }


    /**
     * Moves srcFile, to destinationFile file.
     * If destination folder does not exist, it is created if
     * createNonExistingParents=true.  If this file destination
     * exist, and overWrite = true, it is deleted first,
     * otherwise this method returns false.
     *
     * @param srcFile                  source file object
     * @param createNonExistingParents true if to create parent directories when they do not exist
     * @param destinationFile          destination file object
     * @return True if operation is done. False if overwrite is not allowed
     * @throws FileSystemException If this file is read-only,
     *                             or if the source file does
     *                             not exist, or on error copying the file.
     */
    private boolean moveFile(FileObject srcFile, boolean createNonExistingParents,
                             FileObject destinationFile, boolean overWrite) throws FileSystemException {

        if (!overWrite && destinationFile.exists()) {
            return false;
        } else {
            if (createNonExistingParents) {
                destinationFile.createFolder();
            } else {
                log.error("Destination file does not exist and not configured to create");
            }
            srcFile.moveTo(destinationFile);
            return true;
        }
    }

    /**
     * Moves src directory and all its descendants, , to destinationFile folder.
     * If destination folder does not exist, it is created if
     * createNonExistingParents=true. If it exists, operation scans only
     * first level of children to determine move can be done without overwriting
     * any file.
     *
     * @param srcFile                  Src directory to move
     * @param createNonExistingParents True if to create directories along the path if not exist
     * @param destinationFile          Destination folder
     * @param overWrite                True if to overwrite any existing file when moving
     * @return True if operation is performed fine
     * @throws FileSystemException In case of I/O error
     */
    private boolean moveFolder(FileObject srcFile, boolean createNonExistingParents,
                               FileObject destinationFile, boolean overWrite) throws FileSystemException {

        if (destinationFile.exists()) {
            if (!overWrite) {
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
        } else {
            if (createNonExistingParents) {
                destinationFile.createFolder();
            } else {
                log.error("Destination folder does not exist and not configured to create");
            }
        }

        srcFile.moveTo(destinationFile);
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
        Utils.setError(OPERATION_NAME, msgCtx, e, error, errorDetail);
        handleException(errorDetail, e, msgCtx);
    }

}
