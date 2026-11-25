/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.connector.operations;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.config.xml.TemplateMediatorFactory;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2SynapseEnvironment;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.Value;
import org.apache.synapse.mediators.eip.EIPUtils;
import org.apache.synapse.mediators.template.InvokeMediator;
import org.apache.synapse.mediators.template.TemplateContext;
import org.apache.synapse.mediators.template.TemplateMediator;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.util.synapse.expression.constants.ExpressionConstants;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;
import java.util.TreeMap;

/**
 * A write must name overwriteBody. The designer sets true for a new write, and a write with no overwriteBody is
 * refused. With true the result of the write replaces the message body, and with false the body stays as it came.
 * The engine decides the value from the parameters of the invoke alone and not from the template, so each row runs
 * once through the template and once through the invoke of the engine.
 */
public class WriteBodyDefaultTest {
    private static final String XML = "<order><id>7</id><item>tea</item></order>";

    @DataProvider(name = "overwrite")
    public Object[][] overwrite() {
        return new Object[][]{{false, "true", true}, {false, "false", false}, {true, "true", true},
                {true, "false", false}};
    }

    @Test(dataProvider = "overwrite")
    public void theBodyIsReplacedOnlyWhenOverwriteBodyIsTrue(boolean invoke, String overwriteBody, boolean replaced)
            throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            String before = message.getEnvelope().getBody().getFirstElement().toString();
            write(message, overwriteBody, invoke);
            String written = new String(Files.readAllBytes(directory.resolve("target")), StandardCharsets.UTF_8);
            Assert.assertTrue(written.contains(XML), written);
            Object payload = payload(message.getVariable("result"));
            if (replaced) {
                String body = JsonUtil.jsonPayloadToString(message.getAxis2MessageContext());
                Assert.assertTrue(body.contains("\"success\":true"), body);
                Assert.assertFalse(body.contains("tea"), body);
                Assert.assertNull(payload);
            } else {
                Assert.assertEquals(message.getEnvelope().getBody().getFirstElement().toString(), before);
                Assert.assertTrue(String.valueOf(payload).contains("\"success\":true"), String.valueOf(payload));
            }
        }
    }

    @DataProvider(name = "path")
    public Object[][] path() {
        return new Object[][]{{false}, {true}};
    }

    @Test(dataProvider = "path")
    public void aWriteWithNoOverwriteBodyIsRefused(boolean invoke) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            try {
                write(message, null, invoke);
                Assert.fail("A write with no overwriteBody must be refused");
            } catch (SynapseException expected) {
                // Through the invoke the class mediator of the engine wraps the refusal of the connector core.
                Throwable refusal = expected;
                while (refusal.getCause() != null && !"Parameter overwriteBody is not provided".equals(
                        refusal.getMessage())) {
                    refusal = refusal.getCause();
                }
                Assert.assertEquals(refusal.getMessage(), "Parameter overwriteBody is not provided");
            }
            Assert.assertFalse(Files.exists(directory.resolve("target")));
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        MessageContext axis = new MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(new AxisConfiguration()));
        axis.getConfigurationContext().getAxisConfiguration()
                .addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        SynapseConfiguration configuration = new SynapseConfiguration();
        Axis2MessageContext message = new Axis2MessageContext(axis, configuration,
                new Axis2SynapseEnvironment(axis.getConfigurationContext(), configuration));
        message.setProperty("name", files.name);
        OMElement element = new ApplicationXMLBuilder().processDocument(
                new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), "application/xml", axis);
        axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
        // A request comes with its transport headers and a built body. The invoke of the engine keeps a copy of both.
        axis.setProperty(MessageContext.TRANSPORT_HEADERS, new TreeMap<String, String>());
        message.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
        return message;
    }

    // Runs the write as a flow runs it. Through the template, the template takes the values of the flow and the
    // connector core reads responseVariable and overwriteBody from it. Through the invoke, the engine also keeps or
    // replaces the body around the template by the value it was given. An overwriteBody of null is a flow that does
    // not name it.
    private static void write(Axis2MessageContext message, String overwriteBody, boolean invoke) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/target");
        values.put("writeMode", "Overwrite");
        values.put("mimeType", "application/xml");
        values.put("responseVariable", "result");
        if (overwriteBody != null) {
            values.put("overwriteBody", overwriteBody);
        }
        TemplateMediator mediator;
        try (InputStream xml = WriteBodyDefaultTest.class.getClassLoader().getResourceAsStream("functions/write.xml")) {
            mediator = (TemplateMediator) new TemplateMediatorFactory().createMediator(
                    new StAXOMBuilder(xml).getDocumentElement(), new Properties());
        }
        if (invoke) {
            message.getConfiguration().addSequenceTemplate("file.write", mediator);
            InvokeMediator call = new InvokeMediator();
            call.setTargetTemplate("file.write");
            values.forEach((name, value) -> call.addExpressionForParamName(name, new Value(value)));
            call.mediate(message);
            return;
        }
        values.forEach((name, value) -> message.setProperty(
                EIPUtils.getTemplatePropertyMapping("file.write", name), new Value(value)));
        TemplateContext template = new TemplateContext("file.write", mediator.getParameters());
        template.setupParams(message);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().connect(message);
    }

    // The core leaves its response in the variable. After an invoke the engine puts a map there in its place.
    private static Object payload(Object variable) {
        if (variable instanceof ConnectorResponse) {
            return ((ConnectorResponse) variable).getPayload();
        }
        return ((Map<?, ?>) variable).get(ExpressionConstants.PAYLOAD);
    }
}
