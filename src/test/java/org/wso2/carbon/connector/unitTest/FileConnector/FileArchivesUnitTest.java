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
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.FileArchives;

import java.nio.file.Paths;
import java.util.*;

/**
 * Unit test class for FileArchives method
 */
public class FileArchivesUnitTest {

    private FileArchives fileArchives;
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
        fileArchives = new FileArchives();
        ctx=createMessageContext();
    }

    @Test
    public void testFileArchivesEmptySourceAndInputContent() throws Exception {
        TemplateContext templateContext = new TemplateContext("fileConnector", null);
        templateContext.getMappedValues().put("source","");
        templateContext.getMappedValues().put("destination",getFilePath("in/test.zip"));
        templateContext.getMappedValues().put("inputContent","");
        templateContext.getMappedValues().put("fileName","");
        templateContext.getMappedValues().put("setTimeout","");
        templateContext.getMappedValues().put("setPassiveMode","");
        templateContext.getMappedValues().put("setUserDirIsRoot","");
        templateContext.getMappedValues().put("setSoTimeout","");
        templateContext.getMappedValues().put("setStrictHostKeyChecking","");

        Stack<TemplateContext> fileStack= new Stack<>();
        fileStack.push(templateContext);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK",fileStack);
        fileArchives.connect(ctx);

        Assert.assertEquals(ctx.getEnvelope().getBody().getFirstElement().getText(), "false");
    }

    @Test
    public void testFileArchivesEmpty() throws Exception {
        TemplateContext templateContext = new TemplateContext("fileConnector", null);
        templateContext.getMappedValues().put("source",getFilePath("in/no.txt"));
        templateContext.getMappedValues().put("destination",getFilePath("in/test.zip"));
        templateContext.getMappedValues().put("inputContent","");
        templateContext.getMappedValues().put("fileName","");
        templateContext.getMappedValues().put("setTimeout","");
        templateContext.getMappedValues().put("setPassiveMode","");
        templateContext.getMappedValues().put("setUserDirIsRoot","");
        templateContext.getMappedValues().put("setSoTimeout","");
        templateContext.getMappedValues().put("setStrictHostKeyChecking","");

        Stack<TemplateContext> fileStack= new Stack<>();
        fileStack.push(templateContext);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK",fileStack);
        fileArchives.connect(ctx);

        System.out.print(ctx);
        Assert.assertEquals(ctx.getEnvelope().getBody().getFirstElement().getText(), "false");
    }

    @Test
    public void testFileArchives() throws Exception {
        TemplateContext templateContext = new TemplateContext("fileConnector", null);
        templateContext.getMappedValues().put("source",getFilePath("in/test.txt"));
        templateContext.getMappedValues().put("destination",getFilePath("in/test.zip"));
        templateContext.getMappedValues().put("inputContent","It is created");
        templateContext.getMappedValues().put("fileName","");
        templateContext.getMappedValues().put("setTimeout","");
        templateContext.getMappedValues().put("setPassiveMode","");
        templateContext.getMappedValues().put("setUserDirIsRoot","");
        templateContext.getMappedValues().put("setSoTimeout","");
        templateContext.getMappedValues().put("setStrictHostKeyChecking","");

        Stack<TemplateContext> fileStack= new Stack<>();
        fileStack.push(templateContext);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK",fileStack);
        fileArchives.connect(ctx);

        Assert.assertEquals(ctx.getEnvelope().getBody().getFirstElement().getText(), "true");
    }

    @Test
    public void testFolderArchives() throws Exception {
        TemplateContext templateContext = new TemplateContext("fileConnector", null);
        templateContext.getMappedValues().put("source",getFilePath("in"));
        templateContext.getMappedValues().put("destination",getFilePath("in/folders.zip"));
        templateContext.getMappedValues().put("inputContent","");
        templateContext.getMappedValues().put("fileName","");
        templateContext.getMappedValues().put("setTimeout","");
        templateContext.getMappedValues().put("setPassiveMode","");
        templateContext.getMappedValues().put("setUserDirIsRoot","");
        templateContext.getMappedValues().put("setSoTimeout","");
        templateContext.getMappedValues().put("setStrictHostKeyChecking","");

        Stack<TemplateContext> fileStack= new Stack<>();
        fileStack.push(templateContext);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK",fileStack);
        fileArchives.connect(ctx);

        Assert.assertEquals(ctx.getEnvelope().getBody().getFirstElement().getText(), "true");
    }

    @Test
    public void testFileArchivesDifferentType() throws Exception {
        TemplateContext templateContext = new TemplateContext("fileConnector", null);
        templateContext.getMappedValues().put("source",getFilePath("in/wso2.jpg"));
        templateContext.getMappedValues().put("destination",getFilePath("in/folders1.zip"));
        templateContext.getMappedValues().put("inputContent","");
        templateContext.getMappedValues().put("fileName","nirthi");
        templateContext.getMappedValues().put("setTimeout","");
        templateContext.getMappedValues().put("setPassiveMode","");
        templateContext.getMappedValues().put("setUserDirIsRoot","");
        templateContext.getMappedValues().put("setSoTimeout","");
        templateContext.getMappedValues().put("setStrictHostKeyChecking","");

        Stack<TemplateContext> fileStack= new Stack<>();
        fileStack.push(templateContext);
        ctx.setProperty("_SYNAPSE_FUNCTION_STACK",fileStack);
        fileArchives.connect(ctx);

        Assert.assertEquals(ctx.getEnvelope().getBody().getFirstElement().getText(), "true");
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
