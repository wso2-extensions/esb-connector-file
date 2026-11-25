/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.connector.operations;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.TransportUtils;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.integration.connector.core.pool.CircuitBreaker;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * A JSON file is read to its end with no conversion to XML, so a file with a long array is read whole, into the body
 * and into a variable, and can be read again. A file that is not JSON stays a content error of the read.
 */
public class ReadJsonArrayTest {

    private static String array(int size) {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < size; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"n").append(i)
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    // The flow before the read holds no body, an XML body, or a JSON body.
    @DataProvider(name = "arrays")
    public Object[][] arrays() {
        return new Object[][]{{5000, true, "none"}, {5000, false, "none"}, {20000, true, "none"},
                {20000, false, "none"}, {5000, true, "xml"}, {5000, false, "xml"}, {5000, true, "json"},
                {5000, false, "json"}};
    }

    @Test(dataProvider = "arrays")
    public void longArrayIsReadWhole(int size, boolean replace, String flow) throws Exception {
        String json = array(size);
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.json"), json.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            for (int round = 1; round <= 2; round++) {
                Axis2MessageContext message = message(files, flow);
                read(message, "application/json", replace, 1);
                String read = replace ? JsonUtil.jsonPayloadToString(message.getAxis2MessageContext())
                        : String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload());
                Assert.assertEquals(read, json, "round " + round);
            }
        }
    }

    // What the read gives for files at the edge of JSON. Only the two lenient cases differ from before.
    @DataProvider(name = "edges")
    public Object[][] edges() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '"', 'a', '"', ':', '1', '}'};
        return new Object[][]{
                {"empty", new byte[0], null},
                {"white space", "  \n\t ".getBytes(StandardCharsets.UTF_8), "700108"},
                {"a number", "42".getBytes(StandardCharsets.UTF_8), null},
                {"a string", "\"text\"".getBytes(StandardCharsets.UTF_8), null},
                {"a byte order mark", bom, "700108"},
                {"a cut in an array", "{\"a\":[1,2,".getBytes(StandardCharsets.UTF_8), "700108"},
                {"a cut in a string", "{\"a\":\"tex".getBytes(StandardCharsets.UTF_8), "700108"},
                // The lenient pass lets these through, where the read refused them before.
                {"single quotes", "{'a':'b'}".getBytes(StandardCharsets.UTF_8), null},
                {"a comment", "{\"a\":1 /* note */}".getBytes(StandardCharsets.UTF_8), null},
        };
    }

    @Test(dataProvider = "edges")
    public void edgeOfJson(String name, byte[] content, String code) throws Exception {
        for (boolean replace : new boolean[]{true, false}) {
            Path directory = WriteFileTest.directory();
            Files.write(directory.resolve("in.json"), content);
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
                Axis2MessageContext message = message(files, "none");
                try {
                    read(message, "application/json", replace, 1);
                    Assert.assertNull(code, name + " was read");
                } catch (SynapseException failed) {
                    Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), code, name);
                }
            }
        }
    }

    // A builder that runs out of stack fails the read alone, with a reply, and the connection goes on.
    @Test
    public void builderThatRunsOutOfStackFailsTheReadAlone() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.json"), "{}".getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Axis2MessageContext message = message(files, "none");
            message.getAxis2MessageContext().getConfigurationContext().getAxisConfiguration().addMessageBuilder(
                    "application/x-deep", (in, type, context) -> {
                        throw new StackOverflowError();
                    });
            int before = pool.attempts.size();
            try {
                read(message, "application/x-deep", true, 1);
                Assert.fail("The read passed");
            } catch (SynapseException expected) {
                Assert.assertTrue(expected.getMessage().startsWith(
                        "The file content could not be read as application/x-deep"), expected.getMessage());
            }
            Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), "700108");
            Assert.assertEquals(pool.attempts.size() - before, 1, pool.attempts.toString());
            Assert.assertEquals(pool.getBreaker().getMode(), CircuitBreaker.Mode.CLOSED);
            Axis2MessageContext next = message(files, "none");
            read(next, "application/json", true, 1);
            Assert.assertEquals(JsonUtil.jsonPayloadToString(next.getAxis2MessageContext()), "{}");
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files, String flow) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("application/json", new JsonStreamBuilder());
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(configuration));
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        if ("xml".equals(flow)) {
            OMElement body = new ApplicationXMLBuilder().processDocument(new ByteArrayInputStream(
                    "<flow><a>1</a></flow>".getBytes(StandardCharsets.UTF_8)), "application/xml", axis);
            axis.setEnvelope(TransportUtils.createSOAPEnvelope(body));
        } else if ("json".equals(flow)) {
            OMElement body = new JsonStreamBuilder().processDocument(new ByteArrayInputStream(
                    "{\"flow\":[1,2]}".getBytes(StandardCharsets.UTF_8)), "application/json", axis);
            axis.setEnvelope(TransportUtils.createSOAPEnvelope(body));
        }
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private static void read(Axis2MessageContext message, String type, boolean replace, int retries)
            throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("path", "/in.json");
        values.put("contentType", type);
        values.put("maxRetries", Integer.toString(retries));
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new ReadFile().execute(message, "content", replace);
    }
}
