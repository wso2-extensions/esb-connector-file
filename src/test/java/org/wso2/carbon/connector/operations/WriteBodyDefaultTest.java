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
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.config.xml.TemplateMediatorFactory;
import org.apache.synapse.mediators.Value;
import org.apache.synapse.mediators.eip.EIPUtils;
import org.apache.synapse.mediators.template.TemplateContext;
import org.apache.synapse.mediators.template.TemplateMediator;
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

/**
 * A write with no overwriteBody replaces the message body with its result, so the body is not held in memory after
 * the write. The template of the write gives that default. An overwriteBody of false keeps the body as it came, and
 * true replaces it as before.
 */
public class WriteBodyDefaultTest {
    private static final String XML = "<order><id>7</id><item>tea</item></order>";

    @DataProvider(name = "overwrite")
    public Object[][] overwrite() {
        return new Object[][]{{null, true}, {"true", true}, {"false", false}};
    }

    @Test(dataProvider = "overwrite")
    public void theBodyIsReplacedUnlessOverwriteBodyIsFalse(String overwriteBody, boolean replaced) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            String before = message.getEnvelope().getBody().getFirstElement().toString();
            write(message, overwriteBody);
            String written = new String(Files.readAllBytes(directory.resolve("target")), StandardCharsets.UTF_8);
            Assert.assertTrue(written.contains(XML), written);
            ConnectorResponse response = (ConnectorResponse) message.getVariable("result");
            if (replaced) {
                String body = JsonUtil.jsonPayloadToString(message.getAxis2MessageContext());
                Assert.assertTrue(body.contains("\"success\":true"), body);
                Assert.assertFalse(body.contains("tea"), body);
                Assert.assertNull(response.getPayload());
            } else {
                Assert.assertEquals(message.getEnvelope().getBody().getFirstElement().toString(), before);
                Assert.assertTrue(response.getPayload().toString().contains("\"success\":true"),
                        String.valueOf(response.getPayload()));
            }
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        MessageContext axis = message.getAxis2MessageContext();
        axis.getConfigurationContext().getAxisConfiguration()
                .addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        OMElement element = new ApplicationXMLBuilder().processDocument(
                new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), "application/xml", axis);
        axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
        return message;
    }

    // Runs the write as a flow runs it: the template of the write takes the values of the flow and its own defaults,
    // and the connector core reads responseVariable and overwriteBody from it. An overwriteBody of null is a flow
    // that does not name it.
    private static void write(Axis2MessageContext message, String overwriteBody) throws Exception {
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
        values.forEach((name, value) -> message.setProperty(
                EIPUtils.getTemplatePropertyMapping("file.write", name), new Value(value)));
        TemplateContext template = new TemplateContext("file.write", mediator.getParameters());
        template.setupParams(message);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().connect(message);
    }
}
