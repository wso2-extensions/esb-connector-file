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
import org.apache.commons.vfs2.FileFilter;
import org.apache.commons.vfs2.FileFilterSelector;
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
import org.wso2.carbon.connector.utils.SimpleFileFiler;


/**
 * Implements File listing capability
 * in a directory.
 */
public class ListFiles extends AbstractConnector {

    private static final String MATCHING_PATTERN = "matchingPattern";
    private static final String RECURSIVE_PARAM = "recursive";
    private static final String OPERATION_NAME = "listFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:listFiles for folder ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String folderPath = null;
        String fileMatchingPattern;
        boolean recursive;
        FileObject folder = null;

        try {

            //get connection
            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();

            //read inputs
            folderPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FileConnectorConstants.DIRECTORY_PATH);
            fileMatchingPattern = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, MATCHING_PATTERN);
            String recursiveStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, RECURSIVE_PARAM);
            recursive = Boolean.parseBoolean(recursiveStr);

            folderPath = fileSystemHandler.getBaseDirectoryPath() + folderPath;
            folder = fsManager.resolveFile(folderPath, fso);

            if(folder.exists()) {

                if(folder.isFolder()) {

                    OMElement fileListEle = listFilesInFolder(folder, fileMatchingPattern, recursive);
                    FileOperationResult result = new FileOperationResult(
                            OPERATION_NAME,
                            true,
                            fileListEle);
                    FileConnectorUtils.setResultAsPayload(messageContext, result);

                } else {

                    String errorDetail = ERROR_MESSAGE + folderPath + ". Folder expected.";
                    FileOperationResult result = new FileOperationResult(
                            OPERATION_NAME,
                            false,
                            Error.ILLEGAL_PATH,
                            errorDetail);
                    FileConnectorUtils.setResultAsPayload(messageContext, result);
                    handleException(errorDetail, messageContext);
                }

            } else {
                String errorDetail = ERROR_MESSAGE + folderPath + ". Folder does not exist.";
                FileOperationResult result = new FileOperationResult(
                        OPERATION_NAME,
                        false,
                        Error.ILLEGAL_PATH,
                        errorDetail);
                FileConnectorUtils.setResultAsPayload(messageContext, result);
                handleException(errorDetail, messageContext);
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            FileOperationResult result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.INVALID_CONFIGURATION,
                    errorDetail);
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            FileOperationResult result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.OPERATION_ERROR,
                    errorDetail);
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (folder != null) {
                try {
                    folder.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + folder);
                }
            }
        }
    }

    /**
     * List all files in the directory. If recursive = true,
     * This method will recursively look into subdirectories.
     *
     * @param folder    Folder to scan
     * @param pattern   Specific pattern of files to include in the listing
     * @param recursive true, if to look into subdirectories
     * @return OMElement with organized listing.
     * @throws FileSystemException In case of reading the directory.
     */
    private OMElement listFilesInFolder(FileObject folder, String pattern, boolean recursive) throws FileSystemException {
        String containingFolderName = folder.getName().getBaseName();
        OMElement folderEle = FileConnectorUtils.createOMElement(containingFolderName, null);
        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern);
        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
                OMElement fileEle = FileConnectorUtils.createOMElement(FileConnectorConstants.FILE_ELEMENT,
                        fileOrFolder.getName().getBaseName());
                folderEle.addChild(fileEle);
            } else {
                if (recursive) {
                    OMElement subFolderEle = listFilesInFolder(fileOrFolder, pattern, recursive);
                    folderEle.addChild(subFolderEle);
                }
            }
        }
        return folderEle;
    }

    /**
     * Finds the set of matching descendants of this file.
     *
     * @param pattern pattern to match
     */
    private FileObject[] getFilesAndFolders(FileObject folder, String pattern) throws FileSystemException {
        FileFilter fileFilter = new SimpleFileFiler(pattern);
        FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
        return folder.findFiles(fileFilterSelector);
    }
}
