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

import java.io.IOException;
import javax.xml.stream.XMLStreamException;

import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.codehaus.jettison.json.JSONException;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.FileUnzipUtil;
import org.wso2.carbon.connector.util.ResultPayloadCreate;

/**
 * This class is used to decompress the file.
 * @since 2.0.9
 */
public class FileUnzip extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileUnzip.class);

    /**
     * Initiate the unzip method.
     *
     * @param messageContext The message context that is used in file unzip mediation flow.
     */
    public void connect(MessageContext messageContext) {
        String source = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String destination = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.NEW_FILE_LOCATION);
        boolean resultStatus = false;
        try {
            resultStatus = new FileUnzipUtil().unzip(source, destination, messageContext);
        } catch (Exception e) {
            handleException(e.getMessage(), messageContext);
        }
        generateResults(messageContext, resultStatus);
        if (log.isDebugEnabled()) {
            log.debug("File extracted to" + destination);
        }
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
}