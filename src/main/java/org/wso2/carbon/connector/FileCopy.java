/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import org.apache.commons.vfs2.*;
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

public class FileCopy extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileCopy.class);

    public void connect(MessageContext messageContext) {
        boolean includeParentDirectory;
        boolean includeSubdirectories;
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
            includeSubdirectories = Boolean.parseBoolean(FileConstants.DEFAULT_INCLUDE_SUBDIRECTORIES);
        } else {
            includeSubdirectories = Boolean.parseBoolean(includeSubDirs);
        }

        boolean resultStatus = copyFile(source, destination, filePattern, messageContext, includeParentDirectory, includeSubdirectories);
        ResultPayloadCreate resultPayload = new ResultPayloadCreate();
        generateResults(messageContext, resultStatus, resultPayload);
    }

    /**
     * Generate the results
     *
     * @param messageContext The message context that is processed by a handler in the handle method
     * @param resultStatus   Result of the status (true/false)
     * @param resultPayload  result payload create
     */
    private void generateResults(MessageContext messageContext, boolean resultStatus,
                                 ResultPayloadCreate resultPayload) {
        String response = FileConstants.START_TAG + resultStatus + FileConstants.END_TAG;
        OMElement element;
        try {
            element = resultPayload.performSearchMessages(response);
            resultPayload.preparePayload(messageContext, element);
        } catch (XMLStreamException e) {
            log.error(e.getMessage());
            handleException(e.getMessage(), e, messageContext);
        } catch (IOException e) {
            log.error(e.getMessage());
            handleException(e.getMessage(), e, messageContext);
        } catch (JSONException e) {
            log.error(e.getMessage());
            handleException(e.getMessage(), e, messageContext);
        }
    }

    /**
     * Copy files
     *
     * @param source         Location of the file
     * @param destination    new file location
     * @param filePattern    pattern of the file
     * @param messageContext The message context that is generated for processing the file
     * @return return a resultStatus
     */
    private boolean copyFile(String source, String destination, String filePattern,
                             MessageContext messageContext, boolean includeParentDirectory, boolean includeSubdirectories) {
        boolean resultStatus = false;
        StandardFileSystemManager manager = null;
        try {
            manager = FileConnectorUtils.getManager();
            FileSystemOptions sourceFso = FileConnectorUtils.getFso(messageContext, source, manager);
            FileSystemOptions destinationFso = FileConnectorUtils.getFso(messageContext, destination, manager);

            FileObject souFile = manager.resolveFile(source, sourceFso);
            FileObject destFile = manager.resolveFile(destination, destinationFso);
            if (StringUtils.isNotEmpty(filePattern)) {
                FileObject[] children = souFile.getChildren();
                for (FileObject child : children) {
                    if (child.getType() == FileType.FILE) {
                        if (filePattern != null) {
                            FilePattenMatcher patternMatcher = new FilePattenMatcher(filePattern);
                            if (patternMatcher.validate(child.getName().getBaseName())) {
                                copy(child, destination, filePattern, sourceFso, destinationFso, messageContext);
                            }
                        } else {
                            copy(child, destination, filePattern, sourceFso, destinationFso, messageContext);
                        }
                    } else if (includeSubdirectories && child.getType() == FileType.FOLDER) {
                        String[] urlParts = source.split("\\?");
                        if (urlParts.length > 1) {
                            String urlWithoutParam = urlParts[0];
                            String param = urlParts[1];
                            String newSource = urlWithoutParam + child.getName().getBaseName() +
                                    FileConstants.QUERY_PARAM_SEPARATOR + param;
                            copyFile(newSource, destination, filePattern, messageContext, includeParentDirectory, includeSubdirectories);
                        } else {
                            String newSource = source + File.separator + child.getName().getBaseName();
                            copyFile(newSource, destination, filePattern, messageContext, includeParentDirectory, includeSubdirectories);
                        }
                    }
                }
                resultStatus = true;
            } else {
                if (souFile.exists()) {
                    if (souFile.getType() == FileType.FILE) {
                        try {
                            String name = souFile.getName().getBaseName();
                            FileObject outFile = manager.resolveFile(destination + File.separator
                                    + name, destinationFso);
                            outFile.copyFrom(souFile, Selectors.SELECT_ALL);
                            resultStatus = true;
                        } catch (FileSystemException e) {
                            log.error("Error while copying a file " + e.getMessage());
                        }
                    } else if (souFile.getType() == FileType.FOLDER) {
                        if (includeParentDirectory) {
                            destFile = manager.resolveFile(destination + File.separator +
                                    souFile.getName().getBaseName(), destinationFso);
                            destFile.createFolder();
                        }
                        destFile.copyFrom(souFile, Selectors.SELECT_ALL);
                        resultStatus = true;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("File copying completed from " + source + "to" + destination);
                    }
                } else {
                    log.error("The File Location does not exist.");
                    resultStatus = false;
                }
            }
            return resultStatus;
        } catch (IOException e) {
            handleException("Unable to copy a file/folder", e, messageContext);
        } finally {
            if (manager != null) {
                manager.close();
            }
        }
        return resultStatus;
    }

    /**
     * @param source      file location
     * @param destination target file location
     * @param filePattern pattern of the file
     * @param sourceFso   source file's FileSystemOptions
     * @param destinationFso destination file's FileSystemOptions
     * @param messageContext the message context that is generated for processing the file
     * @throws IOException
     */
    private void copy(FileObject source, String destination, String filePattern, FileSystemOptions sourceFso,
                      FileSystemOptions destinationFso, MessageContext messageContext)
            throws IOException {
        StandardFileSystemManager manager = FileConnectorUtils.getManager();
        FileObject souFile = manager.resolveFile(String.valueOf(source), sourceFso);
        FilePattenMatcher patternMatcher = new FilePattenMatcher(filePattern);
        try {
            if (patternMatcher.validate(source.getName().getBaseName())) {
                String name = source.getName().getBaseName();
                FileObject outFile = manager.resolveFile(destination + File.separator + name, destinationFso);
                outFile.copyFrom(souFile, Selectors.SELECT_ALL);
            }
        } catch (IOException e) {
            log.error("Error occurred while copying a file. " + e.getMessage(), e);
			handleException("Error occurred while copying a file.", e, messageContext);
        } finally {
            manager.close();
        }
    }
}