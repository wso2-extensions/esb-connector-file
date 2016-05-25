
/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.format.BinaryFormatter;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseTransportException;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.codehaus.jettison.json.JSONException;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.ResultPayloadCreate;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;

public class FileSend extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileSend.class);

    public void connect(MessageContext messageContext) throws ConnectException {
        boolean append = false;
        String address = (String) ConnectorUtils.lookupTemplateParamater(messageContext, FileConstants.ADDRESS);
        String strAppend = (String) ConnectorUtils.lookupTemplateParamater(messageContext, FileConstants.APPEND);
        if (strAppend != null) {
            append = Boolean.parseBoolean(strAppend);
        }
        boolean resultStatus = sendResponseFile(address, messageContext, append);
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

    private MessageFormatter getMessageFormatter(org.apache.axis2.context.MessageContext msgContext) {
        OMElement firstChild = msgContext.getEnvelope().getBody().getFirstElement();
        if (firstChild != null) {
            if (BaseConstants.DEFAULT_BINARY_WRAPPER.equals(firstChild.getQName())) {
                return new BinaryFormatter();
            } else if (BaseConstants.DEFAULT_TEXT_WRAPPER.equals(firstChild.getQName())) {
                return new PlainTextFormatter();
            }
        }
        try {
            return MessageProcessorSelector.getMessageFormatter(msgContext);
        } catch (Exception e) {
            throw new BaseTransportException("Unable to get the message formatter to use");
        }
    }

    /**
     * Send the files
     *
     * @param address Location for send the file
     * @param append  If the response should be appended to the response file or not
     * @return return a resultStatus
     */
    private boolean sendResponseFile(String address, MessageContext messageContext, boolean append) {
        boolean resultStatus = false;
        FileObject fileObj;
        StandardFileSystemManager manager = null;
        CountingOutputStream os = null;
        if (log.isDebugEnabled()) {
            log.debug("File sending started to" + address);
        }
        try {
            manager = FileConnectorUtils.getManager();
            org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                    getAxis2MessageContext();
            fileObj = manager.resolveFile(address, FileConnectorUtils.init(messageContext));
            if (fileObj.getType() == FileType.FOLDER) {
                address = address.concat(FileConstants.DEFAULT_RESPONSE_FILE);
                fileObj = manager.resolveFile(address, FileConnectorUtils.init(messageContext));
            }
            // Get the message formatter.
            MessageFormatter messageFormatter = getMessageFormatter(axis2MessageContext);
            OMOutputFormat format = BaseUtils.getOMOutputFormat(axis2MessageContext);
            // Creating output stream and give the content to that.
            os = new CountingOutputStream(fileObj.getContent().getOutputStream(append));
            if (format != null && os != null && messageContext != null) {
                messageFormatter.writeTo(axis2MessageContext, format, os, true);
                resultStatus = true;
                if (log.isDebugEnabled()) {
                    log.debug("File send completed to " + address);
                }
            } else {
                log.error("Can not send the file to specific address");
            }
        } catch (IOException e) {
            handleException("Unable to send a file/folder.", e, messageContext);
        } finally {
            try {
                os.close();
            } catch (IOException e) {
                log.warn("Can not close the output stream");
            }
            if (manager != null) {
                manager.close();
            }
        }
        return resultStatus;
    }
}