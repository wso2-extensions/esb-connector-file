/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.connector;

import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.codehaus.jettison.json.JSONException;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.ResultPayloadCreate;

import javax.xml.stream.XMLStreamException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Merges multiple file chunks into to a single file.
 */
public class MergeFiles extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(MergeFiles.class);

    public void connect(MessageContext messageContext) {
        String fileLocation = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String destination = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.NEW_FILE_LOCATION);
        String filePattern = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_PATTERN);

        boolean status = mergeFiles(fileLocation, destination, filePattern, messageContext);
        generateOutput(messageContext, status);
    }

    /**
     * @param source Location of the source file.
     * @param filePattern Pattern of the files to process.
     * @param destination   Location of the destination to write the merged file.
     * @param options Init configuration options.
     * @param messageContext    Message context.
     * @return Status true/false.
     */
    private boolean mergeFiles(String source, String destination, String filePattern,
                               MessageContext messageContext) {
        FileObject sourceFileObj = null;
        FileObject outputFileObj = null;
        StandardFileSystemManager manager = null;
        OutputStream outputStream = null;
        BufferedOutputStream bufferedOutputStream = null;
        boolean status = false;
        byte[] fileBytes;
        try {
            manager = FileConnectorUtils.getManager();
            FileSystemOptions sourceFso = FileConnectorUtils.getFso(messageContext, source, manager);
            FileSystemOptions destinationFso = FileConnectorUtils.getFso(messageContext, destination, manager);

            sourceFileObj = manager.resolveFile(source, sourceFso);
            if (!sourceFileObj.exists()) {
                handleException("File/Folder does not exists in the location: " + source, messageContext);
            } else {
                if (sourceFileObj.getType() == FileType.FOLDER) {
                    FileObject[] children = sourceFileObj.getChildren();
                    if (children == null || children.length == 0) {
                        log.warn("Empty folder.");
                        handleException("Empty folder.", messageContext);
                    } else {
                        outputFileObj = manager.resolveFile(destination, destinationFso);
                        if (!outputFileObj.exists()) {
                            outputFileObj.createFile();
                        }
                        outputStream = outputFileObj.getContent().getOutputStream(true);
                        bufferedOutputStream = new BufferedOutputStream(outputStream);
                        for (FileObject child : children) {
                            if (filePattern != null && !filePattern.trim().equals("")) {
                                if (child.getName().getBaseName().matches(filePattern)) {
                                    fileBytes = new byte[(int) child.getContent().getSize()];
                                    child.getContent().getInputStream().read(fileBytes);
                                    bufferedOutputStream.write(fileBytes);
                                    bufferedOutputStream.flush();
                                    outputStream.flush();
                                }
                            } else {
                                fileBytes = new byte[(int) child.getContent().getSize()];
                                child.getContent().getInputStream().read(fileBytes);
                                bufferedOutputStream.write(fileBytes);
                                bufferedOutputStream.flush();
                                outputStream.flush();
                            }
                            try {
                                child.close();
                            } catch (IOException e) {
                                log.warn("Error while closing a file in the source folder: " + e.getMessage(), e);
                            }
                        }
                    }
                    status = true;
                } else if (sourceFileObj.getType() != FileType.FILE) {
                    handleException("File does not exists, or an empty folder.", messageContext);
                }
            }
        } catch (IOException e) {
            handleException("Error while processing the file", e, messageContext);
        } finally {
            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.close();
                } catch (IOException e) {
                    log.warn("Error while closing the BufferedOutputStream: " + e.getMessage(), e);
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.warn("Error while closing the OutputStream: " + e.getMessage(), e);
                }
            }
            if (outputFileObj != null) {
                try {
                    outputFileObj.close();
                } catch (FileSystemException e) {
                    log.warn("Error while closing the outputFileObj: " + e.getMessage(), e);
                }
            }
            if (sourceFileObj != null) {
                try {
                    sourceFileObj.close();
                } catch (FileSystemException e) {
                    log.warn("Error while closing the sourceFileObj: " + e.getMessage(), e);
                }
            }
            if (manager != null) {
                manager.close();
            }
        }
        return status;
    }
    /**
     * Generate the output payload
     *
     * @param messageContext The message context that is processed by a handler in the handle method
     * @param resultStatus   Result of the status (true/false)
     */
    private void generateOutput(MessageContext messageContext, boolean resultStatus) {
        ResultPayloadCreate resultPayload = new ResultPayloadCreate();
        String response = FileConstants.START_TAG + resultStatus + FileConstants.END_TAG;

        try {
            OMElement element = resultPayload.performSearchMessages(response);
            resultPayload.preparePayload(messageContext, element);
        } catch (XMLStreamException | IOException | JSONException e) {
            handleException(e.getMessage(), e, messageContext);
        }
    }

}