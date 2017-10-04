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
import org.apache.commons.vfs2.FileType;
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

/**
 * This class is used to move file/folder to target directory.
 * @since 2.0.9
 */
public class FileMove extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileMove.class);

    /**
     * Initiate the move method.
     *
     * @param messageContext The message context that is used in file move mediation flow.
     */
    public void connect(MessageContext messageContext) {
        boolean includeParentDirectory;
        String source = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String destination = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.NEW_FILE_LOCATION);
        String filePattern = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_PATTERN);
        String includeParentDir = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.INCLUDE_PARENT_DIRECTORY);
        if (StringUtils.isEmpty(includeParentDir)) {
            includeParentDirectory = Boolean.parseBoolean(FileConstants.DEFAULT_INCLUDE_PARENT_DIRECTORY);
        } else {
            includeParentDirectory = Boolean.parseBoolean(includeParentDir);
        }
        boolean resultStatus = moveFile(source, destination, messageContext, includeParentDirectory, filePattern);
        generateResults(messageContext, resultStatus);
    }

    /**
     * Generate the result is used to display the result(true/false) after file operations complete.
     *
     * @param messageContext The message context that is generated for processing the file.
     * @param resultStatus   Boolean value of the result to display.
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
     * Move the file/folder from source to destination directory.
     *
     * @param source      Location of the file.
     * @param destination Destination of the file.
     * @return true, if the file/folder is successfully moved.
     */
    private boolean moveFile(String source, String destination, MessageContext messageContext,
                             boolean includeParentDirectory, String filePattern) {
        boolean resultStatus = false;
        StandardFileSystemManager manager = null;
        try {
            manager = FileConnectorUtils.getManager();
            // Create remote object
            FileObject remoteFile = manager.resolveFile(source, FileConnectorUtils.init(messageContext));
            if (remoteFile.exists()) {
                if (remoteFile.getType() == FileType.FILE) {
                    fileMove(destination, remoteFile, messageContext, manager);
                } else {
                    folderMove(source, destination, filePattern, includeParentDirectory, messageContext, manager);
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
     * Move the file to the target directory.
     *
     * @param destination    New location of the folder.
     * @param remoteFile     Location of the file.
     * @param messageContext The message context that is processed by a handler in the handle method.
     * @param manager        Standard file system manager.
     */
    private void fileMove(String destination, FileObject remoteFile, MessageContext messageContext,
                          StandardFileSystemManager manager) throws IOException {
        FileObject file = manager.resolveFile(destination, FileConnectorUtils.init(messageContext));
        if (FileConnectorUtils.isFolder(file)) {
            if (!file.exists()) {
                file.createFolder();
            }
            file = manager.resolveFile(destination + File.separator + remoteFile.getName().getBaseName(),
                    FileConnectorUtils.init(messageContext));
        } else if (!file.exists()) {
            file.createFile();
        }
        remoteFile.moveTo(file);
    }

    /**
     * Move the folder to the target directory.
     *
     * @param destination            New location of the folder.
     * @param source                 Location of the folder.
     * @param messageContext         The message context that is processed by a handler in the handle method.
     * @param includeParentDirectory Boolean type to include the parent directory.
     * @param manager                Standard file system manager.
     */
    private void folderMove(String source, String destination, String filePattern, boolean includeParentDirectory,
                            MessageContext messageContext, StandardFileSystemManager manager) throws IOException {
        FileObject remoteFile = manager.resolveFile(source, FileConnectorUtils.init(messageContext));
        FileObject file = manager.resolveFile(destination, FileConnectorUtils.init(messageContext));
        if (StringUtils.isNotEmpty(filePattern)) {
            FileObject[] children = remoteFile.getChildren();
            for (FileObject child : children) {
                if (child.getType() == FileType.FILE) {
                    moveFileWithPattern(child, destination, filePattern, manager, messageContext);
                } else if (child.getType() == FileType.FOLDER) {
                    String newSource = source + File.separator + child.getName().getBaseName();
                    folderMove(newSource, destination, filePattern, includeParentDirectory, messageContext, manager);
                }
            }
        } else if (includeParentDirectory) {
            file = manager.resolveFile(destination + File.separator + remoteFile.getName().getBaseName(),
                    FileConnectorUtils.init(messageContext));
            file.createFolder();
            remoteFile.moveTo(file);
        } else {
            if (!file.exists()) {
                file.createFolder();
            }
            remoteFile.moveTo(file);
            remoteFile.createFolder();
        }
    }

    /**
     * Move the file for given pattern.
     *
     * @param remoteFile     Location of the file.
     * @param destination    New file location.
     * @param filePattern    Pattern of the file.
     * @param manager        Standard file system manager.
     * @param messageContext The message context that is generated for processing the file.
     * @throws IOException
     */
    private void moveFileWithPattern(FileObject remoteFile, String destination, String filePattern,
                                     StandardFileSystemManager manager, MessageContext messageContext)
            throws IOException {
        FilePattenMatcher patternMatcher = new FilePattenMatcher(filePattern);
        try {
            if (patternMatcher.validate(remoteFile.getName().getBaseName())) {
                FileObject file = manager.resolveFile(destination, FileConnectorUtils.init(messageContext));
                if (!file.exists()) {
                    file.createFolder();
                }
                file = manager.resolveFile(destination + File.separator + remoteFile.getName().getBaseName(),
                        FileConnectorUtils.init(messageContext));
                remoteFile.moveTo(file);
            }
        } catch (IOException e) {
            log.error("Error occurred while moving a file. " + e.getMessage(), e);
        }
    }
}