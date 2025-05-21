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

import com.google.gson.JsonObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileAlreadyExistsException;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FilePatternMatcher;
import org.wso2.carbon.connector.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements move files operation.
 */
public class MoveFiles extends AbstractConnectorOperation {

    private static final String SOURCE_PATH_PARAM = "sourcePath";
    private static final String TARGET_PATH_PARAM = "targetPath";
    private static final String CREATE_PARENT_DIRECTORIES_PARAM = "createParentDirectories";
    private static final String INCLUDE_PARENT_PARAM = "includeParent";
    private static final String OVERWRITE_PARAM = "overwrite";
    private static final String RENAME_TO_PARAM = "renameTo";
    private static final String FILE_PATTERN_PARAM = "filePattern";
    private static final String OPERATION_NAME = "moveFiles";
    private static final String IS_SOURCE_MOUNTED = "isSourceMounted";
    private static final String IS_TARGET_MOUNTED = "isTargetMounted";

    private static final String ERROR_MESSAGE = "Error while performing file:move for file/folder ";

    private FileSystemOptions fso;
    private FileSystemManager fsManager;

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String sourcePath = null;
        String targetPath = null;
        boolean createNonExistingParents;
        boolean includeParent;
        boolean overwrite;
        String renameTo;
        String filePattern;
        boolean isSourceMounted = false;
        boolean isTargetMounted = false;
        FileObject sourceFile = null;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {
            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);
            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            fsManager = fileSystemHandlerConnection.getFsManager();
            fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);

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
            filePattern = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FILE_PATTERN_PARAM);
            sourcePath = fileSystemHandlerConnection.getBaseDirectoryPath() + sourcePath;
            targetPath = fileSystemHandlerConnection.getBaseDirectoryPath() + targetPath;

            // check if the source and target files are mounted.
            isSourceMounted = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, IS_SOURCE_MOUNTED));
            isTargetMounted = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, IS_TARGET_MOUNTED));

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

                    // Set the isMounted flag to to avoid errors in mounted volumes.
                    try {
                        targetFile.setIsMounted(isTargetMounted);
                        sourceFile.setIsMounted(isSourceMounted);
                    } catch (NoSuchMethodError e) {
                        log.debug("The method setIsMounted is not available in the current version of VFS library. "
                                + "Please upgrade the VFS library");
                    }

                    boolean success = moveFile(sourceFile, createNonExistingParents, targetFile, overwrite);
                    if (success) {
                        JsonObject resultJSON = generateOperationResult(messageContext,
                                new FileOperationResult(OPERATION_NAME, true));
                        handleConnectorResponse(messageContext, responseVariable, overwriteBody,
                                resultJSON, null, null);
                    } else {
                        throw new FileAlreadyExistsException("Destination file already exists and overwrite not allowed");
                    }

                } else {
                    //in case of folder
                    FileObject targetFile = fsManager.resolveFile(targetPath, fso);

                    if (!targetFile.exists() && !createNonExistingParents) {
                        throw new FileOperationException("Target folder does not exist and not configured to create");
                    }

                    if (includeParent) {
                        if (StringUtils.isNotEmpty(renameTo)) {
                            targetPath = targetPath + Const.FILE_SEPARATOR + renameTo;
                        } else {
                            String sourceParentFolderName = sourceFile.getName().getBaseName();
                            targetPath = targetPath + Const.FILE_SEPARATOR + sourceParentFolderName;
                        }
                        targetFile = fsManager.resolveFile(targetPath, fso);
                    }

                    boolean success = moveFolder(sourceFile, targetFile, overwrite, filePattern, true);
                    if (success) {
                        JsonObject resultJSON = generateOperationResult(messageContext,
                                new FileOperationResult(OPERATION_NAME, true));
                        handleConnectorResponse(messageContext, responseVariable, overwriteBody,
                                resultJSON, null, null);
                    } else {
                        throw new FileOperationException("Error occurred while moving one or more File(s)/Folder.");
                    }
                }

            } else {
                throw new FileOperationException("File/Folder does not exist.");
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (FileSystemException | FileOperationException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

        } catch (FileAlreadyExistsException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.FILE_ALREADY_EXISTS, errorDetail, responseVariable, overwriteBody);

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
                    Utils.addMaxAccessMaskToFSO(fileSystemHandlerConnection.getFsOptions());
                    handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
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
                if (log.isDebugEnabled()) {
                    log.debug("Parent directory creation is skipped.");
                }
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
     * @param destinationFile          Destination folder
     * @param overWrite                True if to overwrite any existing file when moving
     * @param filePattern              The pattern (regex) of the files to be moved
     * @param isSuccessful             Flag to keep track of something goes wrong while moving a file.
     * @return True if operation is performed fine
     * @throws FileSystemException In case of I/O error
     */
    private boolean moveFolder(FileObject srcFile, FileObject destinationFile, boolean overWrite,
                               String filePattern, boolean isSuccessful) throws FileSystemException {

        if (destinationFile.exists()) {
            if (!overWrite) {
                //we check only one level
                FileObject[] sourceFileChildren = srcFile.getChildren();
                FileObject[] destinationFileChildren = destinationFile.getChildren();
                ArrayList<String> sourceChildrenNames = new ArrayList<>(sourceFileChildren.length);
                ArrayList<String> destinationChildrenNames = new ArrayList<>(destinationFileChildren.length);
                for (FileObject child : sourceFileChildren) {
                    if (child.getType() == FileType.FILE) {
                        sourceChildrenNames.add(child.getName().getBaseName());
                    }
                }
                for (FileObject child : destinationFileChildren) {
                    if (child.getType() == FileType.FILE) {
                        destinationChildrenNames.add(child.getName().getBaseName());
                    }
                }

                Collection commonFiles = CollectionUtils.intersection(sourceChildrenNames, destinationChildrenNames);
                if (!commonFiles.isEmpty()) {
                    log.error("Folder or one or more sub-directories already exists and overwrite not allowed");
                    return false;
                }
            }
        }

        if (StringUtils.isNotEmpty(filePattern)) {
            FileObject[] children = srcFile.getChildren();
            for (FileObject child : children) {
                boolean result;
                if (child.getType() == FileType.FILE) {
                    result = moveFileWithPattern(child, destinationFile, filePattern);
                } else if (child.getType() == FileType.FOLDER) {
                    String newDestination = destinationFile.getPublicURIString() + Const.FILE_SEPARATOR
                            + child.getName().getBaseName();
                    result = moveFolder(child, fsManager.resolveFile(newDestination, fso),
                            overWrite, filePattern, isSuccessful);
                } else {
                    log.error("Could not move the file: " + child.getName() + "Unsupported file type: "
                            + child.getType() + " for move operation.");
                    result = false;
                }

                if (!result) {
                    // if any error happens while moving a file/folder, mark the whole move operation as a failure.
                    // But keep moving whatever the remaining files/folders.
                    isSuccessful = false;
                }
            }
        } else {
            srcFile.moveTo(destinationFile);
        }
        return isSuccessful;
    }

    /**
     * @param remoteFile  Location of the file
     * @param target      New file location
     * @param filePattern Pattern of the file
     * @return True if operation is performed fine
     */
    private boolean moveFileWithPattern(FileObject remoteFile, FileObject target, String filePattern) {
        FilePatternMatcher patternMatcher = new FilePatternMatcher(filePattern);
        try {
            if (patternMatcher.validate(remoteFile.getName().getBaseName())) {
                if (!target.exists()) {
                    target.createFolder();
                }
                String newTarget = target + Const.FILE_SEPARATOR + remoteFile.getName().getBaseName();
                remoteFile.moveTo(fsManager.resolveFile(newTarget, fso));
            }
        } catch (IOException e) {
            log.error("Error occurred while moving a file. " + e.getMessage(), e);
            return false;
        }
        return true;
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
