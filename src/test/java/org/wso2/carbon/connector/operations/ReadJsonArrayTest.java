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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMSourcedElement;
import org.apache.axiom.om.impl.llom.OMSourcedElementImpl;
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * A JSON file is read into memory to its end and then turned into XML as before, so every file that was read before
 * gives the same result. Only when that conversion runs out of stack, as a long JSON array does on the runtime, does
 * a read into a variable keep the JSON form, checked by a reader that walks with its own stack. A read into the body
 * then fails with 700108.
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

    // What the read gives for files at the edge of JSON, the same as before.
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
                {"single quotes", "{'a':'b'}".getBytes(StandardCharsets.UTF_8), "700108"},
                {"a comment", "{\"a\":1 /* note */}".getBytes(StandardCharsets.UTF_8), "700108"},
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

    // A JSON file read into the body and written as XML gives the XML form of the JSON, as before.
    @DataProvider(name = "xmlWrites")
    public Object[][] xmlWrites() {
        return new Object[][]{{"text/xml", "<?xml version='1.0' encoding='UTF-8'?><soapenv:Envelope xmlns:soapenv="
                + "\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>" + XML
                + "</soapenv:Body></soapenv:Envelope>"}, {"application/xml", XML}};
    }

    @Test(dataProvider = "xmlWrites")
    public void jsonBodyWrittenAsXml(String type, String expected) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.json"), SMALL.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files, "none");
            read(message, "application/json", true, 1);
            Map<String, String> values = new HashMap<>();
            values.put("filePath", "/out");
            values.put("mimeType", type);
            TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
            template.setMappedValues(values);
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            new WriteFile().execute(message, "result", false);
            String written = new String(Files.readAllBytes(directory.resolve("out")), StandardCharsets.UTF_8);
            Assert.assertEquals(written, expected);
        }
    }

    // A JSON file read into a variable under a content type with a charset gives what it gave before.
    @Test
    public void variableUnderJsonWithCharset() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.json"), SMALL.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files, "none");
            read(message, "application/json; charset=UTF-8", false, 1);
            Object payload = ((ConnectorResponse) message.getVariable("content")).getPayload();
            Assert.assertEquals(payload, XML);
        }
    }

    // A conversion that runs out of stack once, before the element is expanded or after it is expanded in part. A read
    // into a variable passes with the JSON of the file, the body and its properties stay, and a second read works.
    @DataProvider(name = "overflows")
    public Object[][] overflows() {
        return new Object[][]{{false, "application/json"}, {true, "application/json"},
                {false, "application/json; charset=UTF-8"}, {true, "application/json; charset=UTF-8"}};
    }

    @Test(dataProvider = "overflows")
    public void conversionThatRunsOutOfStack(boolean expanded, String type) throws Exception {
        String json = array(5000);
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.json"), json.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files, "xml");
            message.getAxis2MessageContext().getConfigurationContext().getAxisConfiguration()
                    .addMessageBuilder("application/json", new DeepJsonBuilder(expanded));
            String before = state(message);
            read(message, type, false, 1);
            Assert.assertEquals(String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload()),
                    json);
            Assert.assertEquals(state(message), before);
            Axis2MessageContext next = message(files, "none");
            read(next, "application/json", true, 1);
            Assert.assertEquals(JsonUtil.jsonPayloadToString(next.getAxis2MessageContext()), json);
        }
    }

    // The same conversion in a read into the body fails that read with 700108, with a reply. The message is left as
    // the read of a cut JSON file leaves it, and a second read works.
    @Test(dataProvider = "overflows")
    public void conversionThatRunsOutOfStackFailsTheReadIntoTheBody(boolean expanded, String type) throws Exception {
        String json = array(5000);
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.json"), json.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("cut.json"), "{\"a\":[1,2,".getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext cut = message(files, "xml");
            try {
                read(cut, "/cut.json", type, true, 1);
                Assert.fail("The cut file was read");
            } catch (SynapseException expected) {
                // The state it leaves is compared below.
            }
            Axis2MessageContext message = message(files, "xml");
            message.getAxis2MessageContext().getConfigurationContext().getAxisConfiguration()
                    .addMessageBuilder("application/json", new DeepJsonBuilder(expanded));
            try {
                read(message, type, true, 1);
                Assert.fail("The read into the body passed");
            } catch (SynapseException expected) {
                Assert.assertTrue(expected.getMessage().startsWith("The file content could not be read as " + type),
                        expected.getMessage());
            }
            Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), "700108");
            JsonObject error = JsonParser.parseString(JsonUtil.jsonPayloadToString(message.getAxis2MessageContext()))
                    .getAsJsonObject().getAsJsonObject("error");
            Assert.assertEquals(error.get("detail").getAsString(), "The file content could not be read as " + type
                    + " caused by Error while building message from Stream");
            Assert.assertEquals(state(message), state(cut));
            Axis2MessageContext next = message(files, "none");
            read(next, "application/json", true, 1);
            Assert.assertEquals(JsonUtil.jsonPayloadToString(next.getAxis2MessageContext()), json);
        }
    }

    // The body, the JSON stream with its kind and the properties that a read and its builder set. The cause in the
    // detail of a failure is left out.
    private static String state(Axis2MessageContext message) throws Exception {
        org.apache.axis2.context.MessageContext axis = message.getAxis2MessageContext();
        StringBuilder state = new StringBuilder(String.valueOf(message.getProperty("ERROR_CODE")));
        OMElement body = axis.getEnvelope().getBody().getFirstElement();
        state.append('|').append(body instanceof OMSourcedElement && ((OMSourcedElement) body).isExpanded());
        InputStream json = (InputStream) axis.getProperty(
                org.apache.synapse.commons.json.Constants.ORG_APACHE_SYNAPSE_COMMONS_JSON_JSON_INPUT_STREAM);
        state.append('|').append(json != null).append('|').append(JsonUtil.hasAJsonPayload(axis)
                ? JsonUtil.jsonPayloadToString(axis).replaceAll(" caused by [^\"]*", "") : body.toString());
        for (String name : new String[]{"org.apache.synapse.commons.json.JsonInputStream.IsJsonObject",
                "messageType", "ContentType", "CHARACTER_SET_ENCODING", "setCharacterEncoding", "NO_ENTITY_BODY"}) {
            state.append('|').append(name).append('=').append(axis.getProperty(name));
        }
        return state.toString();
    }

    // The stream of the file fails while the JSON file is read into memory. That is a fault of the connection, as
    // before.
    @Test
    public void streamThatFailsWhileTheFileIsReadIsTheConnection() throws Exception {
        Path directory = WriteFileTest.directory();
        // The hook breaks the reads of the file named in.
        Files.write(directory.resolve("in"), array(5000).getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            ReadContentErrorTest.hookFileReads(files, stream -> new FilterInputStream(stream) {
                private int read;

                @Override
                public int read() throws IOException {
                    if (++read > 20000) {
                        throw new IOException("The connection was lost in the middle of the read");
                    }
                    return super.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int next = read();
                    if (next < 0) {
                        return -1;
                    }
                    bytes[offset] = (byte) next;
                    return 1;
                }
            });
            Axis2MessageContext message = message(files, "none");
            try {
                read(message, "/in", "application/json", true, 0);
                Assert.fail("The read of a lost stream passed");
            } catch (SynapseException expected) {
                Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), "700101");
            }
        }
    }

    private static final String SMALL = "{\"a\":[1,2],\"b\":{\"c\":\"x\"}}";
    private static final String XML = "<jsonObject><a>1</a><a>2</a><b><c>x</c></b></jsonObject>";

    // A JSON builder whose element runs out of stack once when it is turned into XML. With expanded, the element is
    // expanded in part first, as when the stack runs out while the tree is built.
    private static final class DeepJsonBuilder implements Builder {
        private final boolean expanded;

        DeepJsonBuilder(boolean expanded) {
            this.expanded = expanded;
        }

        @Override
        public OMElement processDocument(InputStream in, String type, org.apache.axis2.context.MessageContext context)
                throws AxisFault {
            OMSourcedElement json = (OMSourcedElement) new JsonStreamBuilder().processDocument(in, type, context);
            return new OMSourcedElementImpl(json.getQName(), json.getOMFactory(), json.getDataSource()) {
                private boolean thrown;

                @Override
                public String toString() {
                    if (!thrown) {
                        thrown = true;
                        if (expanded) {
                            getFirstOMChild();
                        }
                        throw new StackOverflowError();
                    }
                    return super.toString();
                }
            };
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files, String flow) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("application/json", new JsonStreamBuilder());
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
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
        read(message, "/in.json", type, replace, retries);
    }

    private static void read(Axis2MessageContext message, String path, String type, boolean replace, int retries)
            throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("path", path);
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
