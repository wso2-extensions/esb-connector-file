/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.connector;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.Selectors;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.synapse.MessageContext;
import org.codehaus.jettison.json.JSONException;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.FilePattenMatcher;
import org.wso2.carbon.connector.util.ResultPayloadCreate;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;

public class FileMove extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileMove.class);

    public void connect(MessageContext messageContext) {
        boolean includeParentDirectory;
        boolean includeSubDirectories;
        String source = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String destination = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.NEW_FILE_LOCATION);
        String filePattern = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_PATTERN);
        String includeParentDir = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.INCLUDE_PARENT_DIRECTORY);
        String includeSubDirs = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.INCLUDE_SUBDIRECTORIES);
        if (StringUtils.isEmpty(includeParentDir)) {
            includeParentDirectory = Boolean.parseBoolean(FileConstants.DEFAULT_INCLUDE_PARENT_DIRECTORY);
        } else {
            includeParentDirectory = Boolean.parseBoolean(includeParentDir);
        }
        if (StringUtils.isEmpty(includeSubDirs)) {
            includeSubDirectories = Boolean.parseBoolean(FileConstants.DEFAULT_INCLUDE_SUBDIRECTORIES);
        } else {
            includeSubDirectories = Boolean.parseBoolean(includeSubDirs);
        }
        boolean resultStatus = moveFile(source, destination, messageContext, includeParentDirectory, filePattern,
                includeSubDirectories);
        generateResults(messageContext, resultStatus);
    }

    /**
     * Generate the result
     *
     * @param messageContext The message context that is processed by a handler in the handle method
     * @param resultStatus   Result of the status (true/false)
     */
    private void generateResults(MessageContext messageContext, boolean resultStatus) {
        ResultPayloadCreate resultPayload = new ResultPayloadCreate();
        String response = FileConstants.START_TAG + resultStatus + FileConstants.END_TAG;
        try {
            OMElement element = resultPayload.performSearchMessages(response);
            resultPayload.preparePayload(messageContext, element);
        } catch (XMLStreamException e) {
            handleException(e.getMessage(), e, messageContext);
        } catch (IOException e) {
            handleException(e.getMessage(), e, messageContext);
        } catch (JSONException e) {
            handleException(e.getMessage(), e, messageContext);
        }
    }

    /**
     * Move the files
     *
     * @param source                 Location of the file
     * @param destination            Destination of the file
     * @param messageContext         The message context that is processed by a handler in the handle method
     * @param includeParentDirectory Indicating whether the parent directory will be included or not
     * @param filePattern            Pattern of the file
     * @param includeSubDirectories  Indicating whether the sub directories will be included or not
     * @return                       return a resultStatus
     */
    private boolean moveFile(String source, String destination, MessageContext messageContext,
                             boolean includeParentDirectory, String filePattern, boolean includeSubDirectories) {
        boolean resultStatus = false;
        StandardFileSystemManager manager = null;
        try {
            manager = FileConnectorUtils.getManager();
            FileSystemOptions sourceFso = FileConnectorUtils.getFso(messageContext, source, manager);
            // Create remote object
            FileObject remoteFile = manager.resolveFile(source, sourceFso);
            if (remoteFile.exists()) {
                FileSystemOptions destinationFso = FileConnectorUtils.getFso(messageContext, destination, manager);
                if (includeParentDirectory) {
                    destination = createParentDirectory(remoteFile, destination, manager, messageContext);
                    destinationFso = FileConnectorUtils.getFso(messageContext, destination, manager);
                }
                if (remoteFile.getType() == FileType.FILE) {
                    fileMove(destination, destinationFso, remoteFile, manager);
                } else if (remoteFile.getType() == FileType.FOLDER) {
                    folderMove(source, sourceFso, destination, destinationFso, filePattern, includeParentDirectory,
                            includeSubDirectories, messageContext, manager);
                    if (remoteFile.getChildren().length == 0 && includeParentDirectory) {
                        remoteFile.delete(Selectors.SELECT_ALL);
                    }
                }
                resultStatus = true;
                if (log.isDebugEnabled()) {
                    log.debug("File move completed from " + source + " to " + destination);
                }
            } else {
                log.error("The file/folder location does not exist.");
                resultStatus = false;
            }
        } catch (IOException e) {
            handleException("Unable to move a file/folder.", e, messageContext);
        } finally {
            if (manager != null) {
                manager.close();
            }
        }
        return resultStatus;
    }

    /**
     * Move the file
     *
     * @param destination    New location of the folder
     * @param destinationFso            Destination's FileSystemOptions
     * @param remoteFile     Location of the file
     * @param manager        Standard file system manager
     */
    private void fileMove(String destination, FileSystemOptions destinationFso, FileObject remoteFile,
                          StandardFileSystemManager manager) throws IOException {
        FileObject file = manager.resolveFile(destination, destinationFso);
        if (FileConnectorUtils.isFolder(file)) {
            if (!file.exists()) {
                file.createFolder();
            }
            file = manager.resolveFile(destination + File.separator + remoteFile.getName().getBaseName(),
                    destinationFso);
        }
        remoteFile.moveTo(file);
    }

    /**
     * Move the folder
     *
     * @param source                 Location of the folder
     * @param sourceFso              Source file's FileSystemOptions
     * @param destination            New location of the folder
     * @param destinationFso         Destination's FileSystemOptions
     * @param filePattern            Pattern of the file
     * @param includeParentDirectory Indicating whether the parent directory will be included or not
     * @param includeSubDirectories  Indicating whether the sub directories will be included or not
     * @param messageContext         The message context that is processed by a handler in the handle method
     * @param manager                Standard file system manager
     * @throws IOException
     */
    private void folderMove(String source, FileSystemOptions sourceFso, String destination,
                            FileSystemOptions destinationFso, String filePattern, boolean includeParentDirectory,
                            boolean includeSubDirectories, MessageContext messageContext,
                            StandardFileSystemManager manager) throws IOException {

        FileObject remoteFile = manager.resolveFile(source, sourceFso);
        FileObject destinationFile = manager.resolveFile(destination, destinationFso);
        if (!destinationFile.exists()) {
            destinationFile.createFolder();
        }
        FileObject[] children = remoteFile.getChildren();
        for (FileObject child : children) {
            if (child.getType() == FileType.FILE) {
                if (StringUtils.isNotEmpty(filePattern)) {
                    moveFileWithPattern(child, destination, destinationFso, filePattern, manager);
                } else {
                    destinationFile = manager.resolveFile(destination + File.separator
                            + child.getName().getBaseName(), destinationFso);
                    child.moveTo(destinationFile);
                }
            } else if (child.getType() == FileType.FOLDER && includeSubDirectories) {
                source += File.separator + child.getName().getBaseName();
                sourceFso = FileConnectorUtils.getFso(messageContext, source, manager);

                String newDestination = destination;
                if (includeParentDirectory) {
                    newDestination += File.separator + child.getName().getBaseName();
                    destinationFso = FileConnectorUtils.getFso(messageContext, newDestination, manager);
                }
                folderMove(source, sourceFso, newDestination, destinationFso, filePattern, includeParentDirectory,
                        includeSubDirectories, messageContext, manager);
                FileObject sourceFile = manager.resolveFile(source, sourceFso);
                if (sourceFile.getChildren().length == 0 && includeParentDirectory) {
                    sourceFile.delete(Selectors.SELECT_ALL);
                }
            }
        }
    }

    /**
     * @param remoteFile     Location of the file
     * @param destination    New file location
     * @param destinationFso Destination's FileSystemOptions
     * @param filePattern    Pattern of the file
     * @param manager        Standard file system manager
     * @throws IOException
     */
    private void moveFileWithPattern(FileObject remoteFile, String destination, FileSystemOptions destinationFso,
                                     String filePattern, StandardFileSystemManager manager) throws IOException {
        FilePattenMatcher patternMatcher = new FilePattenMatcher(filePattern);
        try {
            if (patternMatcher.validate(remoteFile.getName().getBaseName())) {
                FileObject file = manager.resolveFile(destination, destinationFso);
                if (!file.exists()) {
                    file.createFolder();
                }
                file = manager.resolveFile(destination + File.separator + remoteFile.getName().getBaseName(),
                        destinationFso);
                remoteFile.moveTo(file);
            }
        } catch (IOException e) {
            log.error("Error occurred while moving a file. " + e.getMessage(), e);
        }
    }

    /**
     *
     * @param souFile         The source file object
     * @param destination     The path of destination
     * @param manager         Standard file system manager
     * @param messageContext  The message context that is processed by a handler in the handle method
     * @return                The path of new destination
     */
    private String createParentDirectory(FileObject souFile, String destination,
                                         StandardFileSystemManager manager, MessageContext messageContext) {
        try {
            FileSystemOptions destinationFso = FileConnectorUtils.getFso(messageContext, destination, manager);
            destination += File.separator + souFile.getName().getBaseName();
            FileObject destFile = manager.resolveFile(destination, destinationFso);
            if (!destFile.exists()) {
                destFile.createFolder();
            }
        } catch (IOException e) {
            handleException("Unable to create parent directory", e, messageContext);
        }
        return destination;
    }
}
