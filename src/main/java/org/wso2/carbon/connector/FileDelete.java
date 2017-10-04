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

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
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

/**
 * This class is used to delete an existing file/folder.
 * @since 2.0.9
 */
public class FileDelete extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileDelete.class);

    /**
     * Initiate the deleteFile method.
     *
     * @param messageContext The message context that is used in file delete mediation flow.
     */
    public void connect(MessageContext messageContext) {
        String source = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        boolean resultStatus = deleteFile(source, messageContext);
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
     * Delete an existing file/folder.
     *
     * @param source         Location of the file
     * @param messageContext The message context that is generated for processing the file
     * @return true, if the file/folder is successfully deleted.
     */
    private boolean deleteFile(String source, MessageContext messageContext) {
        boolean resultStatus = false;
        StandardFileSystemManager manager = null;
        try {
            manager = FileConnectorUtils.getManager();
            // Create remote object
            FileObject remoteFile = manager.resolveFile(source
                    , FileConnectorUtils.init(messageContext));
            if (remoteFile.exists()) {
                if (remoteFile.getType() == FileType.FILE) {
                    //delete a file
                    remoteFile.delete();
                } else if (remoteFile.getType() == FileType.FOLDER) {
                    String filePattern = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                            FileConstants.FILE_PATTERN);
                    
                    if(filePattern != null && !"".equals(filePattern) && !"*".equals(filePattern)) {
                    	FileObject[] children = remoteFile.getChildren();
                    	for(FileObject child : children) {
                    		if (child.getName().getBaseName().matches(filePattern)) {
                    			child.delete();
                    		}
                    	}
                    } else {
	                    //delete folder
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
        } finally {
            if (manager != null) {
                manager.close();
            }
        }
        return resultStatus;
    }
}
