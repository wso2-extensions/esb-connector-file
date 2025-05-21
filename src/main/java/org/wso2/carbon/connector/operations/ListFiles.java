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
import org.apache.commons.vfs2.FileFilter;
import org.apache.commons.vfs2.FileFilterSelector;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.local.LocalFileName;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.SimpleFileFiler;

import java.util.ArrayList;
import java.util.Arrays;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements File listing capability
 * in a directory.
 */
public class ListFiles extends AbstractConnectorOperation {

    private static final String MATCHING_PATTERN = "matchingPattern";
    private static final String RECURSIVE_PARAM = "recursive";
    private static final String RESPONSE_FORMAT_PARAM = "responseFormat";
    private static final String SORT_ATTRIB_PARAM = "sortingAttribute";
    private static final String SORT_ORDER_PARAM = "sortingOrder";
    private static final String DEFAULT_SORT_ATTRIB = "Name";
    private static final String DEFAULT_SORT_ORDER = "Ascending";
    private static final String DIRECTORY_ELE_NAME = "directory";
    private static final String FILES_ELE_NAME = "files";
    private static final String NAME_ATTRIBUTE = "name";
    private static final String FILE_NAME_ELE_NAME = "name";
    private static final String FILE_PATH_ELE_NAME = "path";

    private static final String HIERARCHICAL_FORMAT = "Hierarchical";
    private static final String FLAT_FORMAT = "Flat";


    private static final String OPERATION_NAME = "listFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:listFiles for folder ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String folderPath = null;
        String fileMatchingPattern;
        String responseFormat;
        boolean recursive;
        FileObject folder = null;

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        int maxRetries;
        int retryDelay;
        int attempt = 0;
        boolean successOperation = false;
        String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                (messageContext, Const.DISK_SHARE_ACCESS_MASK);
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
                Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);

                //read inputs
                folderPath = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, Const.DIRECTORY_PATH);
                fileMatchingPattern = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, MATCHING_PATTERN);
                responseFormat = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, RESPONSE_FORMAT_PARAM);
                if (StringUtils.isEmpty(responseFormat)) {
                    responseFormat = HIERARCHICAL_FORMAT;
                }

                String recursiveStr = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, RECURSIVE_PARAM);
                recursive = Boolean.parseBoolean(recursiveStr);

                String sortingAttribute = Utils.lookUpStringParam(messageContext, SORT_ATTRIB_PARAM, DEFAULT_SORT_ATTRIB);
                String sortingOrder = Utils.lookUpStringParam(messageContext, SORT_ORDER_PARAM, DEFAULT_SORT_ORDER);

                folderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + folderPath;
                folder = fsManager.resolveFile(folderPath, fso);

                if (folder.exists()) {

                    if (folder.isFolder()) {
                        //Added debug logs to track the flow and identify bottlenecks
                        JsonObject fileListJson = listFilesInFolder(folder, fileMatchingPattern,
                                recursive, responseFormat, sortingAttribute, sortingOrder);
                        log.debug(Const.CONNECTOR_NAME + ": " + OPERATION_NAME + " operation completed.");

                        JsonObject resultJSON = generateOperationResult(messageContext,
                                new FileOperationResult(OPERATION_NAME, true));
                        if (responseFormat.equals(FLAT_FORMAT)) {
                            resultJSON.add(FILES_ELE_NAME, fileListJson);
                        } else if (responseFormat.equals(HIERARCHICAL_FORMAT)) {
                            resultJSON.add(DIRECTORY_ELE_NAME, fileListJson);
                        }

                        successOperation = true;
                        handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON,
                                null, null);
                    } else {
                        throw new FileOperationException("Folder is expected.");
                    }

                } else {
                    throw new IllegalPathException("Folder does not exist.");
                }

            } catch (InvalidConfigurationException e) {

                String errorDetail = ERROR_MESSAGE + folderPath;
                handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail,
                        responseVariable, overwriteBody);

            } catch (IllegalPathException e) {

                String errorDetail = ERROR_MESSAGE + folderPath;
                handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

            } catch (Exception e) {

                String errorDetail = ERROR_MESSAGE + folderPath;
                log.error(errorDetail, e);
                Utils.closeFileSystem(folder);
                if (attempt >= maxRetries - 1) {
                    handleError(messageContext, e, Error.RETRY_EXHAUSTED, errorDetail, responseVariable, overwriteBody);
                }
                // Log the retry attempt
                log.warn(Const.CONNECTOR_NAME + ":Error while write "
                        + folderPath + ". Retrying after " + retryDelay + " milliseconds retry attempt " + (attempt + 1)
                        + " out of " + maxRetries);
                attempt++;
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    handleError(messageContext, ie, Error.OPERATION_ERROR, ERROR_MESSAGE + folderPath,
                            responseVariable, overwriteBody);
                }
            } finally {

                if (folder != null) {
                    try {
                        folder.close();
                    } catch (FileSystemException e) {
                        log.error(Const.CONNECTOR_NAME
                                + ":Error while closing file object while creating directory "
                                + folder);
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
    }

    /**
     * List all files in the directory. If recursive = true,
     * This method will recursively look into subdirectories.
     * Lists files adhering to sort attribute and order specified.
     *
     * @param folder           Folder to scan
     * @param pattern          Specific pattern of files to include in the listing
     * @param recursive        true, if to look into subdirectories
     * @param responseFormat   JSON format is determined by this
     * @param sortingAttribute Attribute to use for file sorting
     * @param sortOrder        Sorting order to use
     * @return JsonObject with organized listing
     * @throws FileSystemException           In case of reading the directory
     * @throws InvalidConfigurationException In case issue of config issue
     */
    private JsonObject listFilesInFolder(FileObject folder, String pattern, boolean recursive,
                                         String responseFormat, String sortingAttribute, String sortOrder)
            throws FileSystemException, InvalidConfigurationException {

        if (responseFormat.equals(HIERARCHICAL_FORMAT)) {
            return listFilesInHierarchicalFormat(folder, pattern, recursive, sortingAttribute, sortOrder);
        } else if (responseFormat.equals(FLAT_FORMAT)) {
            JsonObject filesObj = new JsonObject();
            JsonArray filesArray = new JsonArray();
            filesObj.add(FILES_ELE_NAME, filesArray);
            listFilesInFlatFormat(folder, pattern, recursive, sortingAttribute, sortOrder, filesArray);
            return filesObj;
        } else {
            throw new InvalidConfigurationException("Unknown responseFormat found " + responseFormat);
        }
    }

    /**
     * List all files in the directory in hierarchical manner. If recursive = true,
     * This method will recursively look into subdirectories.
     * Lists files adhering to sort attribute and order specified.
     *
     * @param folder           Folder to scan
     * @param pattern          Specific pattern of files to include in the listing
     * @param recursive        true, if to look into subdirectories
     * @param sortingAttribute Attribute to use for file sorting
     * @param sortOrder        Sorting order to use
     * @return JsonObject with organized listing
     * @throws FileSystemException In case of issue reading the directory
     */
    private JsonObject listFilesInHierarchicalFormat(FileObject folder, String pattern,
                                                     boolean recursive, String sortingAttribute,
                                                     String sortOrder) throws FileSystemException {

        String containingFolderName = folder.getName().getBaseName();
        JsonObject folderObj = new JsonObject();
        folderObj.addProperty(NAME_ATTRIBUTE, containingFolderName);

        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern);
        FileSorter fileSorter = new FileSorter(sortingAttribute, sortOrder);
        fileSorter.sort(filesOrFolders);

        JsonArray filesArray = new JsonArray();
        JsonArray directoriesArray = new JsonArray();

        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
//                JsonObject fileObj = new JsonObject();
//                fileObj.addProperty(FILE_NAME_ELE_NAME, fileOrFolder.getName().getBaseName());
                filesArray.add(fileOrFolder.getName().getBaseName());
            } else {
                if (recursive) {
                    JsonObject subFolderObj = listFilesInHierarchicalFormat(fileOrFolder, pattern, recursive,
                            sortingAttribute, sortOrder);
                    directoriesArray.add(subFolderObj);
                }
            }
        }

        if (filesArray.size() > 0) {
            folderObj.add(Const.FILE_ELEMENT, filesArray);
        }

        if (directoriesArray.size() > 0) {
            folderObj.add(DIRECTORY_ELE_NAME, directoriesArray);
        }

        return folderObj;
    }

    /**
     * List all files in the directory in flat manner. If recursive = true,
     * This method will recursively look into subdirectories.
     * Lists files adhering to sort attribute and order specified.
     *
     * @param folder           Folder to scan
     * @param pattern          Specific pattern of files to include in the listing
     * @param recursive        true, if to look into subdirectories
     * @param sortingAttribute Attribute to use for file sorting
     * @param sortOrder        Sorting order to use
     * @param filesArray       JsonArray to include found files information in
     * @throws FileSystemException In case of issue reading the directory
     */
    private void listFilesInFlatFormat(FileObject folder, String pattern,
                                       boolean recursive, String sortingAttribute,
                                       String sortOrder, JsonArray filesArray) throws FileSystemException {

        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern);
        FileSorter fileSorter = new FileSorter(sortingAttribute, sortOrder);
        fileSorter.sort(filesOrFolders);
        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
                JsonObject fileObj = new JsonObject();
                fileObj.addProperty(FILE_NAME_ELE_NAME, fileOrFolder.getName().getBaseName());
                fileObj.addProperty(FILE_PATH_ELE_NAME, getFilePath(fileOrFolder.getName()));
                filesArray.add(fileObj);
            } else {
                if (recursive) {
                    listFilesInFlatFormat(fileOrFolder, pattern, recursive,
                            sortingAttribute, sortOrder, filesArray);
                }
            }
        }
    }


    /**
     * Finds the set of matching descendants of this file.
     *
     * @param pattern pattern to match
     */
    private FileObject[] getFilesAndFolders(FileObject folder, String pattern) throws FileSystemException {

        ArrayList<FileObject> matchingFilesAndFolders;
        log.debug(Const.CONNECTOR_NAME + ": Start listing matching files and folders.");
        if (StringUtils.isEmpty(pattern)) {
            matchingFilesAndFolders = new ArrayList<>(Arrays.asList(folder.getChildren()));
            log.debug(Const.CONNECTOR_NAME + ": Pattern is empty , Children count : "
                    + matchingFilesAndFolders.size());
        } else {
            FileFilter fileFilter = new SimpleFileFiler(pattern);
            FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
            matchingFilesAndFolders =
                    new ArrayList<>(Arrays.asList(folder.findFiles(fileFilterSelector)));
            log.debug(Const.CONNECTOR_NAME + ": Pattern is : " + pattern + " : children count : "
                    + matchingFilesAndFolders.size());
        }
        //when a pattern exists folder.findFiles does not return folders
        if (!StringUtils.isEmpty(pattern)) {
            FileObject[] children = folder.getChildren();
            log.debug(Const.CONNECTOR_NAME + ": Start traversing to add folders, children count : "
                    + children.length );
            for (FileObject child : children) {
                if (child.isFolder() && !matchingFilesAndFolders.contains(child)) {
                    matchingFilesAndFolders.add(child);
                }
            }
        }
        FileObject[] filesWithFolders = new FileObject[matchingFilesAndFolders.size()];
        return matchingFilesAndFolders.toArray(filesWithFolders);
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

    /**
     * Get the file path of a file including the drive letter of windows files.
     *
     * @param fileName Name of the file
     * @return File path
     */
    private String getFilePath(FileName fileName) {
        if (fileName instanceof LocalFileName) {
            LocalFileName localFileName = (LocalFileName) fileName;
            return localFileName.getRootFile() + localFileName.getPath();
        } else {
            return fileName.getPath();
        }
    }
}