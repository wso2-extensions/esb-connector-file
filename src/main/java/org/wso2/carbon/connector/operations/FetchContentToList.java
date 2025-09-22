/*
 * Copyright (c) 2024, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import org.apache.axis2.AxisFault;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileFilter;
import org.apache.commons.vfs2.FileFilterSelector;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.integration.connector.core.AbstractConnector;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.SimpleFileFiler;
import org.wso2.carbon.connector.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

public class FetchContentToList extends AbstractConnector {

    private static final String MATCHING_PATTERN = "matchingPattern";
    private static final String RECURSIVE_PARAM = "recursive";
    private static final String SORT_ATTRIB_PARAM = "sortingAttribute";
    private static final String SORT_ORDER_PARAM = "sortingOrder";
    private static final String DEFAULT_SORT_ATTRIB = "Name";
    private static final String DEFAULT_SORT_ORDER = "Ascending";

    private static final String OPERATION_NAME = "listFiles";
    private static final String ERROR_MESSAGE = "Error while performing file:fetchContent for folder ";
    private static final String RESULT_PROPERTY_NAME = "resultPropertyName";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String folderPath = null;
        String fileMatchingPattern;
        boolean recursive;
        FileObject folder = null;
        String resultPropertyName = "";

        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;

        try {

            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);
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

            if (StringUtils.isEmpty(fileMatchingPattern)) {
                fileMatchingPattern = Const.MATCH_ALL_REGEX;
            }
            resultPropertyName = Utils.lookUpStringParam(messageContext, RESULT_PROPERTY_NAME, "");

            String recursiveStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, RECURSIVE_PARAM);
            recursive = Boolean.parseBoolean(recursiveStr);

            String sortingAttribute = Utils.lookUpStringParam(messageContext, SORT_ATTRIB_PARAM, DEFAULT_SORT_ATTRIB);
            String sortingOrder = Utils.lookUpStringParam(messageContext, SORT_ORDER_PARAM, DEFAULT_SORT_ORDER);

            folderPath = fileSystemHandlerConnection.getBaseDirectoryPath() + folderPath;
            folder = fileSystemHandlerConnection.resolveFileWithSuspension(folderPath);

            if (folder.exists()) {

                if (folder.isFolder()) {
                    List<String> streamList = listFilesInFolder(folder, fileMatchingPattern, recursive,
                            sortingAttribute, sortingOrder);
                    messageContext.setProperty(resultPropertyName, streamList);

                } else {
                    throw new FileOperationException("Folder is expected.");
                }

            } else {
                throw new IllegalPathException("Folder does not exist.");
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (FileSystemException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + folderPath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);
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

    /**
     * List all files in the directory. If recursive = true,
     * This method will recursively look into subdirectories.
     * Lists files adhering to sort attribute and order specified.
     *
     * @param folder           Folder to scan
     * @param pattern          Specific pattern of files to include in the listing
     * @param recursive        true, if to look into subdirectories
     * @param sortingAttribute Attribute to use for file sorting
     * @param sortOrder        Sorting order to use
     * @return OMElement with organized listing
     * @throws FileSystemException           In case of reading the directory
     * @throws InvalidConfigurationException In case issue of config issue
     */
    private List<String> listFilesInFolder(FileObject folder, String pattern, boolean recursive, String sortingAttribute,
                                                String sortOrder)
            throws FileSystemException, InvalidConfigurationException {
        List<String> streamList = new ArrayList<String>();
        return listFilesInFlatFormat(folder, pattern, recursive, sortingAttribute, sortOrder, streamList);
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
     * @throws FileSystemException In case of issue reading the directory
     */
    private List<String> listFilesInFlatFormat(FileObject folder, String pattern, boolean recursive,
                                                    String sortingAttribute, String sortOrder,
                                                    List<String> streamList) throws FileSystemException {

        FileObject[] filesOrFolders = getFilesAndFolders(folder, pattern);
        FileSorter fileSorter = new FileSorter(sortingAttribute, sortOrder);
        fileSorter.sort(filesOrFolders);
        for (FileObject fileOrFolder : filesOrFolders) {
            if (fileOrFolder.isFile()) {
                try {
                    streamList.add(Utils.readStream(fileOrFolder.getContent().getInputStream()));
                } catch (IOException e) {
                    throw new FileSystemException(e);
                }
            } else {
                if (recursive) {
                    listFilesInFlatFormat(fileOrFolder, pattern, recursive, sortingAttribute, sortOrder, streamList);
                }
            }
        }
        return streamList;
    }


    /**
     * Finds the set of matching descendants of this file.
     *
     * @param pattern pattern to match
     */
    private FileObject[] getFilesAndFolders(FileObject folder, String pattern) throws FileSystemException {

        FileFilter fileFilter = new SimpleFileFiler(pattern);
        FileFilterSelector fileFilterSelector = new FileFilterSelector(fileFilter);
        ArrayList<FileObject> matchingFilesAndFolders =
                new ArrayList<>(Arrays.asList(folder.findFiles(fileFilterSelector)));
        //when a pattern exists folder.findFiles does not return folders
        if (!StringUtils.isEmpty(pattern)) {
            FileObject[] children = folder.getChildren();
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
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        FileOperationResult result = new FileOperationResult(OPERATION_NAME, false, error, e.getMessage());
        JsonObject resultJSON = generateOperationResult(msgCtx, result);
        try {
            JsonUtil.getNewJsonPayload(((Axis2MessageContext)msgCtx).getAxis2MessageContext(), resultJSON.toString(),
                    false, false);
        } catch (AxisFault axisFault) {
            log.error("Error while setting the error payload", axisFault);
        }
        handleException(errorDetail, e, msgCtx);
    }

}
