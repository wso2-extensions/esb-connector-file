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
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileContentInfo;
import org.wso2.org.apache.commons.vfs2.FileFilter;
import org.wso2.org.apache.commons.vfs2.FileFilterSelector;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;
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
import org.wso2.carbon.connector.utils.AdvancedFileFilter;

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
    private static final String FILE_FILTER_TYPE = "fileFilterType";
    private static final String INCLUDE_FILES = "includeFiles";
    private static final String EXCLUDE_FILES = "excludeFiles";
    private static final String MAX_FILE_AGE = "maxFileAge";
    private static final String SUB_DIRECTORY_MAX_DEPTH = "subDirectoryMaxDepth";
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
    private static final String APPEND_ATTRIBUTES = "appendFileAttributes";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String folderPath = null;
        String fileMatchingPattern;
        String responseFormat;
        boolean recursive;
        FileObject folder = null;
        boolean appendFileAttributes = false;

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

                String appendFileAttributesStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, APPEND_ATTRIBUTES);
                appendFileAttributes = Boolean.parseBoolean(appendFileAttributesStr);

                String sortingAttribute = Utils.lookUpStringParam(messageContext, SORT_ATTRIB_PARAM, DEFAULT_SORT_ATTRIB);
                String sortingOrder = Utils.lookUpStringParam(messageContext, SORT_ORDER_PARAM, DEFAULT_SORT_ORDER);
                
                // Read new filtering parameters
                String fileFilterType = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, FILE_FILTER_TYPE);
                String includeFiles = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, INCLUDE_FILES);
                String excludeFiles = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, EXCLUDE_FILES);
                String maxFileAge = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, MAX_FILE_AGE);
                String subDirectoryMaxDepth = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, SUB_DIRECTORY_MAX_DEPTH);

                folderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + folderPath;
                folder = fileSystemHandlerConnection.resolveFileWithSuspension(folderPath);

                if (folder.exists()) {

                    if (folder.isFolder()) {
                        //Added debug logs to track the flow and identify bottlenecks
                        JsonObject fileListJson = listFilesInFolder(folder, fileMatchingPattern,
                            recursive, responseFormat, sortingAttribute, sortingOrder,
                            fileFilterType, includeFiles, excludeFiles, maxFileAge,
                            subDirectoryMaxDepth, appendFileAttributes);
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
     * @param folder                Folder to scan
     * @param pattern               Specific pattern of files to include in the listing
     * @param recursive             true, if to look into subdirectories
     * @param responseFormat        JSON format is determined by this
     * @param sortingAttribute      Attribute to use for file sorting
     * @param sortOrder             Sorting order to use
     * @param appendFileAttributes  true, if append file attributes to the response
     * @return JsonObject with organized listing
     * @throws FileSystemException           In case of reading the directory
     * @throws InvalidConfigurationException In case issue of config issue
     */
    private JsonObject listFilesInFolder(FileObject folder, String pattern, boolean recursive,
        String responseFormat, String sortingAttribute, String sortOrder, String filterType,
        String includeFiles, String excludeFiles, String maxFileAge, String subDirectoryMaxDepth,
        boolean appendFileAttributes) throws FileSystemException, InvalidConfigurationException {

        // Parse depth limit for recursive operations
        int maxDepth = -1; // -1 means unlimited
        if (!StringUtils.isEmpty(subDirectoryMaxDepth)) {
            try {
                maxDepth = Integer.parseInt(subDirectoryMaxDepth);
            } catch (NumberFormatException e) {
                maxDepth = -1; // Invalid depth, use unlimited
            }
        }

        if (responseFormat.equals(HIERARCHICAL_FORMAT)) {
            return listFilesInHierarchicalFormat(folder, pattern, recursive, sortingAttribute,
                sortOrder, filterType, includeFiles, excludeFiles, maxFileAge, maxDepth, 0,
                appendFileAttributes);
        } else if (responseFormat.equals(FLAT_FORMAT)) {
            JsonObject filesObj = new JsonObject();
            JsonArray filesArray = new JsonArray();
            filesObj.add(FILES_ELE_NAME, filesArray);
            listFilesInFlatFormat(folder, pattern, recursive, sortingAttribute, sortOrder,
                filesArray, filterType, includeFiles, excludeFiles, maxFileAge, maxDepth, 0,
                appendFileAttributes);
            return filesObj;
        } else {
            throw new InvalidConfigurationException("Unknown responseFormat found " + responseFormat);
        }
    }

    /**
     * List all files in the directory in hierarchical manner. If recursive = true, This method will
     * recursively look into subdirectories. Lists files adhering to sort attribute and order
     * specified.
     *
     * @param folder               Folder to scan
     * @param pattern              Specific pattern of files to include in the listing
     * @param recursive            true, if to look into subdirectories
     * @param sortingAttribute     Attribute to use for file sorting
     * @param sortOrder            Sorting order to use
     * @param appendFileAttributes true, if append file attributes to the response
     * @return JsonObject with organized listing
     * @throws FileSystemException In case of issue reading the directory
     */
    private JsonObject listFilesInHierarchicalFormat(FileObject folder, String pattern,
        boolean recursive, String sortingAttribute,
        String sortOrder, String filterType, String includeFiles,
        String excludeFiles, String maxFileAge, int maxDepth,
        int currentDepth, boolean appendFileAttributes) throws FileSystemException {

        String containingFolderName = folder.getName().getBaseName();
        JsonObject folderObj = new JsonObject();
        folderObj.addProperty(NAME_ATTRIBUTE, containingFolderName);

        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern, filterType, includeFiles,
            excludeFiles, maxFileAge);
        FileSorter fileSorter = new FileSorter(sortingAttribute, sortOrder);
        fileSorter.sort(filesOrFolders);

        JsonArray filesArray = new JsonArray();
        JsonArray directoriesArray = new JsonArray();

        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
//                JsonObject fileObj = new JsonObject();
//                fileObj.addProperty(FILE_NAME_ELE_NAME, fileOrFolder.getName().getBaseName());
                String fileName = fileOrFolder.getName().getBaseName();
                if (appendFileAttributes) {
                    JsonObject fileObj = new JsonObject();
                    fileObj.addProperty(FILE_NAME_ELE_NAME, fileName);
                    if (fileOrFolder.getContent() != null) {
                        FileContent content = fileOrFolder.getContent();
                        fileObj.addProperty(Const.SIZE_ELEMENT, content.getSize());
                        fileObj.addProperty(Const.LAST_MODIFIED_TIME_ELEMENT,
                            content.getLastModifiedTime());
                        if (content.getContentInfo() != null) {
                            FileContentInfo contentInfo = content.getContentInfo();
                            fileObj.addProperty(Const.CONTENT_TYPE_ELEMENT,
                                contentInfo.getContentType());
                            fileObj.addProperty(Const.CONTENT_ENCODING_ELEMENT,
                                contentInfo.getContentEncoding());
                        }
                    }
                    filesArray.add(fileObj);
                } else {
                    filesArray.add(fileName);
                }
            } else {
                if (recursive && (maxDepth == -1 || currentDepth < maxDepth)) {
                    JsonObject subFolderObj = listFilesInHierarchicalFormat(fileOrFolder, pattern,
                        recursive, sortingAttribute, sortOrder, filterType, includeFiles,
                        excludeFiles, maxFileAge, maxDepth, currentDepth + 1,
                        appendFileAttributes);
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
     * List all files in the directory in flat manner. If recursive = true, This method will
     * recursively look into subdirectories. Lists files adhering to sort attribute and order
     * specified.
     *
     * @param folder               Folder to scan
     * @param pattern              Specific pattern of files to include in the listing
     * @param recursive            true, if to look into subdirectories
     * @param sortingAttribute     Attribute to use for file sorting
     * @param sortOrder            Sorting order to use
     * @param filesArray           JsonArray to include found files information in
     * @param appendFileAttributes true, if append file attributes to the response
     * @throws FileSystemException In case of issue reading the directory
     */
    private void listFilesInFlatFormat(FileObject folder, String pattern,
        boolean recursive, String sortingAttribute, String sortOrder, JsonArray filesArray,
        String filterType, String includeFiles, String excludeFiles, String maxFileAge,
        int maxDepth, int currentDepth, boolean appendFileAttributes) throws FileSystemException {

        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern, filterType, includeFiles,
            excludeFiles, maxFileAge);
        FileSorter fileSorter = new FileSorter(sortingAttribute, sortOrder);
        fileSorter.sort(filesOrFolders);
        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
                JsonObject fileObj = new JsonObject();
                fileObj.addProperty(FILE_NAME_ELE_NAME, fileOrFolder.getName().getBaseName());
                fileObj.addProperty(FILE_PATH_ELE_NAME, getFilePath(fileOrFolder.getName()));
                filesArray.add(fileObj);
                if (appendFileAttributes && fileOrFolder.getContent() != null) {
                    FileContent content = fileOrFolder.getContent();
                    fileObj.addProperty(Const.SIZE_ELEMENT, content.getSize());
                    fileObj.addProperty(Const.LAST_MODIFIED_TIME_ELEMENT,
                        content.getLastModifiedTime());
                    if (content.getContentInfo() != null) {
                        FileContentInfo contentInfo = content.getContentInfo();
                        fileObj.addProperty(Const.CONTENT_TYPE_ELEMENT,
                            contentInfo.getContentType());
                        fileObj.addProperty(Const.CONTENT_ENCODING_ELEMENT,
                            contentInfo.getContentEncoding());
                    }
                }
            } else {
                if (recursive && (maxDepth == -1 || currentDepth < maxDepth)) {
                    listFilesInFlatFormat(fileOrFolder, pattern, recursive,
                        sortingAttribute, sortOrder, filesArray, filterType, includeFiles,
                        excludeFiles, maxFileAge, maxDepth, currentDepth + 1,
                        appendFileAttributes);
                }
            }
        }
    }


    /**
     * Finds the set of matching descendants of this file.
     *
     * @param pattern pattern to match
     */
    private FileObject[] getFilesAndFolders(FileObject folder, String pattern, String filterType,
                                            String includeFiles, String excludeFiles, String maxFileAge) throws FileSystemException {

        ArrayList<FileObject> matchingFilesAndFolders;
        log.debug(Const.CONNECTOR_NAME + ": Start listing matching files and folders.");
        
        // Determine which filter to use based on available parameters
        FileFilter fileFilter = null;
        boolean useAdvancedFilter = !StringUtils.isEmpty(includeFiles) || !StringUtils.isEmpty(excludeFiles) || !StringUtils.isEmpty(maxFileAge);
        
        if (useAdvancedFilter) {
            // Use new advanced filter for include/exclude/age filtering
            fileFilter = new AdvancedFileFilter(filterType, includeFiles, excludeFiles, maxFileAge);
        } else if (!StringUtils.isEmpty(pattern)) {
            // Use legacy simple filter for backward compatibility
            fileFilter = new SimpleFileFiler(pattern);
        }
        
        if (fileFilter != null) {
            FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
            matchingFilesAndFolders = new ArrayList<>(Arrays.asList(folder.findFiles(fileFilterSelector)));
            log.debug(Const.CONNECTOR_NAME + ": Using filter, matched children count: " + matchingFilesAndFolders.size());
            
            // Add folders that aren't filtered (folders always included for recursion)
            FileObject[] children = folder.getChildren();
            for (FileObject child : children) {
                if (child.isFolder() && !matchingFilesAndFolders.contains(child)) {
                    matchingFilesAndFolders.add(child);
                }
            }
        } else {
            // No filtering, return all children
            matchingFilesAndFolders = new ArrayList<>(Arrays.asList(folder.getChildren()));
            log.debug(Const.CONNECTOR_NAME + ": No filter, all children count: " + matchingFilesAndFolders.size());
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