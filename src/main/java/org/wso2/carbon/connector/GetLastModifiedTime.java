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
import java.io.IOException;
import java.text.SimpleDateFormat;

/**
 * Returns the last updated time of a file.
 */
public class GetLastModifiedTime extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(GetLastModifiedTime.class);

    public void connect(MessageContext messageContext) {

        String fileLocation = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        FileObject fileObj = null;
        StandardFileSystemManager manager = null;
        try {
            manager = FileConnectorUtils.getManager();
            FileSystemOptions fso = FileConnectorUtils.getFso(messageContext, fileLocation, manager);
            fileObj = manager.resolveFile(fileLocation, fso);
            if (!fileObj.exists()) {
                handleException("File/Folder does not exists in the location: " + fileLocation, messageContext);
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
                generateResults(messageContext, sdf.format(fileObj.getContent().getLastModifiedTime()));
            }
        } catch (FileSystemException e) {
            handleException("Error while processing the file/folder", e, messageContext);
        } finally {
            if (fileObj != null) {
                try {
                    fileObj.close();
                } catch (FileSystemException e) {
                    log.warn("Error while closing the sourceFileObj: " + e.getMessage(), e);
                }
            }
            if (manager != null) {
                manager.close();
            }
        }
    }

    /**
     * Generate the result
     *
     * @param messageContext The message context that is generated for processing the file
     * @param lastModifiedTime    last modified time of the file.
     */
    private void generateResults(MessageContext messageContext, String lastModifiedTime) {
        ResultPayloadCreate resultPayload = new ResultPayloadCreate();
        String response = FileConstants.LAST_MODIFIED_TIME_START_TAG + lastModifiedTime +
                FileConstants.LAST_MODIFIED_TIME_END_TAG;
        OMElement element;
        try {
            element = resultPayload.performSearchMessages(response);
            resultPayload.preparePayload(messageContext, element);
        } catch (XMLStreamException | IOException | JSONException e) {
            handleException(e.getMessage(), e, messageContext);
        }
    }
}
