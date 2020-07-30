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
import org.wso2.carbon.connector.util.ResultPayloadCreate;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;

public class FileDelete extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileDelete.class);

    public void connect(MessageContext messageContext) {
        boolean includeSubDirectories;
        String source = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String includeSubDirs = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.INCLUDE_SUBDIRECTORIES);
        if (StringUtils.isEmpty(includeSubDirs)) {
            includeSubDirectories = Boolean.parseBoolean(FileConstants.DEFAULT_INCLUDE_SUBDIRECTORIES);
        } else {
            includeSubDirectories = Boolean.parseBoolean(includeSubDirs);
        }

        StandardFileSystemManager manager = FileConnectorUtils.getManager();
        FileSystemOptions sourceFso = FileConnectorUtils.getFso(messageContext, source, manager);
        boolean resultStatus = deleteFile(source, sourceFso, includeSubDirectories, manager, messageContext);
        try {
            FileObject remoteFile = manager.resolveFile(source, sourceFso);
            if (remoteFile.getType() == FileType.FOLDER && remoteFile.getChildren().length == 0) {
                remoteFile.delete(Selectors.SELECT_ALL);
            }
        } catch (IOException e) {
            handleException("Error occurs while deleting the root folder.", e, messageContext);
        } finally {
            if (manager != null) {
                manager.close();
            }
        }
        generateResults(messageContext, resultStatus);
    }

    /**
     * Generate the result
     *
     * @param messageContext The message context that is generated for processing the file
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
     * Delete the file
     *
     * @param source                Location of the file
     * @param sourceFso                   Source file's FileSystemOptions
     * @param includeSubDirectories Indicating whether the sub directories will be included or not
     * @param manager               Standard file system manager
     * @param messageContext        The message context that is generated for processing the file
     * @return                      Return the status
     */
    private boolean deleteFile(String source, FileSystemOptions sourceFso, boolean includeSubDirectories,
                               StandardFileSystemManager manager, MessageContext messageContext) {
        boolean resultStatus = false;
        try {
            // Create remote object
            FileObject remoteFile = manager.resolveFile(source, sourceFso);
            if (remoteFile.exists()) {
                if (remoteFile.getType() == FileType.FILE) {
                    //delete a file
                    remoteFile.delete();
                } else if (remoteFile.getType() == FileType.FOLDER) {
                    String filePattern = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                            FileConstants.FILE_PATTERN);
                    FileObject[] children = remoteFile.getChildren();
                    for (FileObject child : children) {
                        if (child.getType() == FileType.FILE) {
                            if (filePattern != null && !"".equals(filePattern) && !"*".equals(filePattern)) {
                                if (child.getName().getBaseName().matches(filePattern)) {
                                    child.delete();
                                }
                            } else {
                                child.delete(Selectors.SELECT_ALL);
                            }
                        } else if (child.getType() == FileType.FOLDER && includeSubDirectories) {
                            String newSource = source + File.separator + child.getName().getBaseName();
                            sourceFso = FileConnectorUtils.getFso(messageContext, newSource, manager);
                            deleteFile(newSource, sourceFso, includeSubDirectories, manager, messageContext);
                        }
                    }
                    if (remoteFile.getChildren().length == 0) {
                        remoteFile.delete(Selectors.SELECT_ALL);
                    }
                }
                resultStatus = true;
            } else {
                log.error("The file does not exist.");
                resultStatus = false;
            }
            if (log.isDebugEnabled()) {
                log.debug("File delete completed with. " + source);
            }
        } catch (IOException e) {
            handleException("Error occurs while deleting a file.", e, messageContext);
        }
        return resultStatus;
    }
}
