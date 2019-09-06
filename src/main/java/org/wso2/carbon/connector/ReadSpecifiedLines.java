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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.ResultPayloadCreate;

/**
 * Reads the file between specific lines.
 */
public class ReadSpecifiedLines extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(ReadSpecifiedLines.class);

    public void connect(MessageContext messageContext) {

        String fileLocation = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String contentType = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.CONTENT_TYPE);
        String from = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.START);
        String to = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.END);
        FileObject fileObj = null;
        StandardFileSystemManager manager = null;
        try {
            manager = FileConnectorUtils.getManager();
            FileSystemOptions fso = FileConnectorUtils.getFso(messageContext, fileLocation, manager);
            fileObj = manager.resolveFile(fileLocation, fso);
            if (!fileObj.exists() || fileObj.getType() != FileType.FILE) {
                handleException("File does not exists, or source is not a file in the location: " + fileLocation,
                        messageContext);
            } else {
                ResultPayloadCreate.readSpecificLines(fileObj, messageContext, contentType, from, to);
            }
        } catch (FileSystemException e) {
            throw new SynapseException("Error while processing the file/folder", e);
        } finally {
            if (fileObj != null) {
                try {
                    fileObj.close();
                } catch (FileSystemException e) {
                    log.warn("Error while closing the fileObj", e);
                }
            }
            if (manager != null) {
                manager.close();
            }
        }
    }
}
