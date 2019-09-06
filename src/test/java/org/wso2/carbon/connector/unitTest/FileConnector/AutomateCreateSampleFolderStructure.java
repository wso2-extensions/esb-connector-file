/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.connector.unitTest.FileConnector;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.util.UUIDGenerator;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.FileCopy;
import org.wso2.carbon.connector.FileDelete;

import java.nio.file.Paths;
import java.util.Stack;

/**
 * Unit test class for FileArchives method
 */
public class AutomateCreateSampleFolderStructure {

    private FileDelete fileDelete;
    private FileCopy fileCopy;
    private SynapseConfiguration synapseConfig;
    private MessageContext ctx;
    private ConfigurationContext configContext;

    public static String getFilePath(String fileName) {
        if (StringUtils.isNotBlank(fileName)) {
            return Paths
                    .get(System.getProperty("framework.resource.location"), "sampleFolderStructure", fileName)
                    .toString();
        }
        return null;
    }

    @BeforeMethod
    public void setUp() throws Exception {
        fileDelete = new FileDelete();
        fileCopy = new FileCopy();
        ctx=createMessageContext();
    }

    @Test
    public void automateCreateSampleFolderStructure() throws Exception {
        TemplateContext templateContext1 = new TemplateContext("fileConnector", null);
        templateContext1.getMappedValues().put("source", getFilePath("in"));
        templateContext1.getMappedValues().put("filePattern", "");
        templateContext1.getMappedValues().put("setTimeout", "");
        templateContext1.getMappedValues().put("setPassiveMode", "");
        templateContext1.getMappedValues().put("setUserDirIsRoot", "");
        templateContext1.getMappedValues().put("setSoTimeout", "");
        templateContext1.getMappedValues().put("setStrictHostKeyChecking", "");

        Stack<TemplateContext> fileStack1 = new Stack<>();
        fileStack1.push(templateContext1);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK", fileStack1);
        fileDelete.connect(ctx);

        TemplateContext templateContext2 = new TemplateContext("fileConnector", null);
        templateContext2.getMappedValues().put("source", getFilePath("out"));
        templateContext2.getMappedValues().put("filePattern", "");
        templateContext2.getMappedValues().put("setTimeout", "");
        templateContext2.getMappedValues().put("setPassiveMode", "");
        templateContext2.getMappedValues().put("setUserDirIsRoot", "");
        templateContext2.getMappedValues().put("setSoTimeout", "");
        templateContext2.getMappedValues().put("setStrictHostKeyChecking", "");

        Stack<TemplateContext> fileStack2 = new Stack<>();
        fileStack2.push(templateContext2);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK", fileStack2);
        fileDelete.connect(ctx);

        TemplateContext templateContext3 = new TemplateContext("fileConnector", null);
        templateContext3.getMappedValues().put("source", getFilePath("temp"));
        templateContext3.getMappedValues().put("filePattern", "");
        templateContext3.getMappedValues().put("setTimeout", "");
        templateContext3.getMappedValues().put("setPassiveMode", "");
        templateContext3.getMappedValues().put("setUserDirIsRoot", "");
        templateContext3.getMappedValues().put("setSoTimeout", "");
        templateContext3.getMappedValues().put("setStrictHostKeyChecking", "");

        Stack<TemplateContext> fileStack3 = new Stack<>();
        fileStack3.push(templateContext3);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK", fileStack3);
        fileDelete.connect(ctx);

        TemplateContext templateContext4 = new TemplateContext("fileConnector", null);
        templateContext4.getMappedValues().put("source", getFilePath("tempResource/in"));
        templateContext4.getMappedValues().put("destination", getFilePath("/"));
        templateContext4.getMappedValues().put("filePattern", "");
        templateContext4.getMappedValues().put("includeParentDirectory", "true");
        templateContext4.getMappedValues().put("setTimeout", "");
        templateContext4.getMappedValues().put("setPassiveMode", "");
        templateContext4.getMappedValues().put("setUserDirIsRoot", "");
        templateContext4.getMappedValues().put("setSoTimeout", "");
        templateContext4.getMappedValues().put("setStrictHostKeyChecking", "");

        Stack<TemplateContext> fileStack4 = new Stack<>();
        fileStack4.push(templateContext4);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK", fileStack4);
        fileCopy.connect(ctx);

        TemplateContext templateContext5 = new TemplateContext("fileConnector", null);
        templateContext5.getMappedValues().put("source", getFilePath("tempResource/out"));
        templateContext5.getMappedValues().put("destination", getFilePath("/"));
        templateContext5.getMappedValues().put("filePattern", "");
        templateContext5.getMappedValues().put("includeParentDirectory", "true");
        templateContext5.getMappedValues().put("setTimeout", "");
        templateContext5.getMappedValues().put("setPassiveMode", "");
        templateContext5.getMappedValues().put("setUserDirIsRoot", "");
        templateContext5.getMappedValues().put("setSoTimeout", "");
        templateContext5.getMappedValues().put("setStrictHostKeyChecking", "");

        Stack<TemplateContext> fileStack5 = new Stack<>();
        fileStack5.push(templateContext5);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK", fileStack5);
        fileCopy.connect(ctx);

        TemplateContext templateContext6 = new TemplateContext("fileConnector", null);
        templateContext6.getMappedValues().put("source", getFilePath("tempResource/temp"));
        templateContext6.getMappedValues().put("destination", getFilePath("/"));
        templateContext6.getMappedValues().put("filePattern", "");
        templateContext6.getMappedValues().put("includeParentDirectory", "true");
        templateContext6.getMappedValues().put("setTimeout", "");
        templateContext6.getMappedValues().put("setPassiveMode", "");
        templateContext6.getMappedValues().put("setUserDirIsRoot", "");
        templateContext6.getMappedValues().put("setSoTimeout", "");
        templateContext6.getMappedValues().put("setStrictHostKeyChecking", "");

        Stack<TemplateContext> fileStack6 = new Stack<>();
        fileStack6.push(templateContext6);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK", fileStack6);
        fileCopy.connect(ctx);
    }

    private MessageContext createMessageContext() throws AxisFault {
        MessageContext msgCtx = createSynapseMessageContext();
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(UUIDGenerator.getUUID());
        return msgCtx;
    }

    private MessageContext createSynapseMessageContext() throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MC = new org.apache.axis2.context.MessageContext();
        axis2MC.setConfigurationContext(this.configContext);
        ServiceContext svcCtx = new ServiceContext();
        OperationContext opCtx = new OperationContext(new InOutAxisOperation(), svcCtx);
        axis2MC.setServiceContext(svcCtx);
        axis2MC.setOperationContext(opCtx);
        Axis2MessageContext mc = new Axis2MessageContext(axis2MC, this.synapseConfig, null);
        mc.setMessageID(UIDGenerator.generateURNString());

        mc.setEnvelope(OMAbstractFactory.getSOAP12Factory().createSOAPEnvelope());
        mc.getEnvelope().addChild(OMAbstractFactory.getSOAP12Factory().createSOAPBody());

        return mc;
    }
}
