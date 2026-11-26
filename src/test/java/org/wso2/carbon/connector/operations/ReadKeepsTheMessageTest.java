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

import com.google.gson.JsonParser;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMText;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.commons.io.IOUtils;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonStreamFormatter;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.relay.BinaryRelayBuilder;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import javax.activation.DataHandler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A read whose result goes to a variable leaves the message as it was: a later write of the body gives the bytes it
 * gave before the read, whatever the type and the encoding of the file that was read. A read that replaces the body
 * gives the content of the file and sets its charset on the message, as before.
 */
public class ReadKeepsTheMessageTest {
    private static final String XML = "<a:order xmlns:a=\"urn:a\">\n  <item>caf\u00e9 \u00fc</item>\n</a:order>";
    private static final String JSON = "{\"order\":{\"item\":\"caf\u00e9 \u00fc\",\"q\":[1,2]}}";
    private static final String TEXT = "line one caf\u00e9 \u00fc\nline two\n";
    private static final byte[] BINARY = new byte[600];
    private static final String[] BODIES = {"application/xml", "application/json", "text/plain",
            "application/octet-stream"};
    private static final String[] FILES = BODIES;
    private static final String[] ENCODINGS = {"ISO-8859-1", "UTF-16"};

    static {
        for (int i = 0; i < BINARY.length; i++) {
            BINARY[i] = (byte) (i * 7);
        }
    }

    private final AtomicInteger names = new AtomicInteger();
    private Path directory;
    // A pool for each row, and one for the read and one for the write, so that a content the read can not decode
    // does not suspend the connection of the write.
    private FilePoolFixture files;
    private FilePoolFixture writer;

    @BeforeClass
    public void start() throws Exception {
        directory = WriteFileTest.directory();
    }

    @BeforeMethod
    public void open() throws Exception {
        files = new FilePoolFixture("LOCAL", "workingDir", directory.toString());
        writer = new FilePoolFixture("LOCAL", "workingDir", directory.toString());
    }

    @AfterMethod
    public void close() throws Exception {
        files.close();
        writer.close();
    }

    @DataProvider(name = "cases")
    public Object[][] cases() {
        List<Object[]> cases = new ArrayList<>();
        for (String body : BODIES) {
            for (String file : FILES) {
                for (String encoding : ENCODINGS) {
                    for (String preset : new String[]{null, "UTF-8"}) {
                        cases.add(new Object[]{body, file, encoding, preset});
                    }
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "cases")
    public void readIntoAVariableLeavesTheBodyAsItWas(String body, String file, String encoding, String preset)
            throws Exception {
        String source = "r" + names.incrementAndGet();
        Files.write(directory.resolve(source), content(file, encoding));

        Axis2MessageContext before = message(body, preset);
        String expected = writeBody(before, body);

        Axis2MessageContext message = message(body, preset);
        Map<String, Object> own = buildProperties(message);
        try {
            read(message, source, file, encoding, false);
        } catch (SynapseException failed) {
            // A content that the builder can not read fails the read. The message must still be as it was.
        }
        Assert.assertEquals(buildProperties(message), own, "what the read set on the message");
        Assert.assertEquals(writeBody(message, body), expected, "bytes of the body written after the read");
    }

    // A read in UTF-8, and a text read that takes its charset from the content type, give the file in the variable.
    @DataProvider(name = "variables")
    public Object[][] variables() {
        List<Object[]> cases = new ArrayList<>();
        for (String body : BODIES) {
            for (String file : FILES) {
                cases.add(new Object[]{body, file, "UTF-8", file});
            }
            cases.add(new Object[]{body, "text/plain", "ISO-8859-1", "text/plain; charset=ISO-8859-1"});
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "variables")
    public void readIntoAVariableGivesTheFile(String body, String file, String encoding, String type)
            throws Exception {
        String source = "v" + names.incrementAndGet();
        Files.write(directory.resolve(source), content(file, encoding));
        Axis2MessageContext before = message(body, null);
        String expected = writeBody(before, body);
        Axis2MessageContext message = message(body, null);
        Map<String, Object> own = buildProperties(message);
        read(message, source, type, null, false);
        Object payload = ((ConnectorResponse) message.getVariable("content")).getPayload();
        switch (file) {
            case "application/xml":
                // The XML builder gives the envelope, so the variable holds the file inside it, as before.
                Assert.assertTrue(String.valueOf(payload).contains(XML), String.valueOf(payload));
                break;
            case "application/json":
                Assert.assertEquals(payload, JsonParser.parseString(JSON));
                break;
            case "text/plain":
                Assert.assertTrue(String.valueOf(payload).contains(TEXT), String.valueOf(payload));
                break;
            default:
                Assert.assertTrue(String.valueOf(payload).contains(Base64.getEncoder().encodeToString(BINARY)),
                        String.valueOf(payload));
        }
        Assert.assertEquals(buildProperties(message), own, "what the read set on the message");
        Assert.assertEquals(writeBody(message, body), expected, "bytes of the body written after the read");
    }

    // A read that fails leaves the message as it was, so the fault sequence sees the body with its own type.
    @Test
    public void failedReadLeavesTheMessageTypeOfTheBody() throws Exception {
        String source = "x" + names.incrementAndGet();
        Files.write(directory.resolve(source), new byte[]{'<', 'r', '>', (byte) 0xE9, '<', '/', 'r', '>'});
        Axis2MessageContext message = message("application/json", null);
        message.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, "application/json");
        Map<String, Object> own = buildProperties(message);
        try {
            read(message, source, "application/xml", "UTF-8", false);
            Assert.fail("The read of a byte that is not UTF-8 passed");
        } catch (SynapseException expected) {
            // The read fails.
        }
        Assert.assertEquals(message.getAxis2MessageContext().getLocalProperty(Constants.Configuration.MESSAGE_TYPE),
                "application/json");
        Assert.assertEquals(buildProperties(message), own);
    }

    // A streamed read into a variable is refused before it sets anything, also with a charset on the message.
    @Test(dataProvider = "presets")
    public void refusedStreamedReadLeavesTheMessage(String preset) throws Exception {
        String source = "s" + names.incrementAndGet();
        Files.write(directory.resolve(source), content("text/plain", "ISO-8859-1"));
        Axis2MessageContext message = message("application/xml", preset);
        message.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, "application/xml");
        Map<String, Object> own = buildProperties(message);
        Map<String, String> values = new HashMap<>();
        values.put("path", "/" + source);
        values.put("contentType", "text/plain");
        values.put("encoding", "ISO-8859-1");
        values.put("enableStreaming", "true");
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        message.setProperty("name", files.name);
        try {
            new ReadFile().execute(message, "content", false);
            Assert.fail("A streamed read into a variable passed");
        } catch (SynapseException expected) {
            Assert.assertTrue(expected.getMessage().contains("while streaming is enabled"), expected.getMessage());
        }
        Assert.assertEquals(buildProperties(message), own);
    }

    @DataProvider(name = "presets")
    public Object[][] presets() {
        return new Object[][]{{null}, {"ISO-8859-1"}};
    }

    private static Map<String, Object> buildProperties(Axis2MessageContext message) {
        MessageContext axis = message.getAxis2MessageContext();
        Map<String, Object> properties = new HashMap<>();
        for (String name : new String[]{Constants.Configuration.CHARACTER_SET_ENCODING, Const.SET_CHARACTER_ENCODING,
                Constants.Configuration.MESSAGE_TYPE, Constants.Configuration.XML_DECLARATION_ENCODING,
                Constants.Configuration.XML_PROCESSING_INSTRUCTION_ELEMENTS,
                "org.apache.synapse.commons.json.JsonInputStream",
                "org.apache.synapse.commons.json.JsonInputStream.IsJsonObject"}) {
            properties.put(name, axis.getLocalProperty(name));
        }
        return properties;
    }

    // The text and the JSON builder decode by the charset of the content type, and UTF-8 when it names none, so
    // these rows read those files in UTF-8. The XML builder reads the declaration of the file.
    @DataProvider(name = "replace")
    public Object[][] replace() {
        List<Object[]> cases = new ArrayList<>();
        for (String file : FILES) {
            boolean declared = "application/xml".equals(file) || "application/octet-stream".equals(file);
            for (String encoding : declared ? ENCODINGS : new String[]{"UTF-8"}) {
                cases.add(new Object[]{file, encoding});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "replace")
    public void readThatReplacesTheBodyGivesTheFileAndItsCharset(String file, String encoding) throws Exception {
        String source = "o" + names.incrementAndGet();
        Files.write(directory.resolve(source), content(file, encoding));
        Axis2MessageContext message = message("application/xml", null);
        read(message, source, file, encoding, true);
        MessageContext axis = message.getAxis2MessageContext();
        Assert.assertEquals(axis.getLocalProperty(Constants.Configuration.CHARACTER_SET_ENCODING), encoding);
        Assert.assertEquals(axis.getLocalProperty(Const.SET_CHARACTER_ENCODING), "true");
        OMElement first = axis.getEnvelope().getBody().getFirstElement();
        switch (file) {
            case "application/xml":
                Assert.assertEquals(first.toString(), XML);
                break;
            case "text/plain":
                Assert.assertEquals(first.getText(), TEXT);
                break;
            case "application/octet-stream":
                Assert.assertEquals(bytes(first), content(file, encoding));
                break;
            default:
                Assert.assertEquals(JsonUtil.jsonPayloadToString(axis), new String(content(file, encoding),
                        Charset.forName(encoding)));
        }
    }

    private static byte[] content(String type, String encoding) {
        Charset charset = Charset.forName(encoding);
        switch (type) {
            case "application/xml":
                return ("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>" + XML).getBytes(charset);
            case "application/json":
                return JSON.getBytes(charset);
            case "text/plain":
                return TEXT.getBytes(charset);
            default:
                return BINARY;
        }
    }

    private Axis2MessageContext message(String body, String preset) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        // The builders and the formatters of the pack (conf/axis2/axis2.xml) for these types.
        configuration.addMessageBuilder("application/xml", new ApplicationXMLBuilder());
        configuration.addMessageBuilder("text/plain", new PlainTextBuilder());
        configuration.addMessageBuilder("application/octet-stream", new BinaryRelayBuilder());
        configuration.addMessageBuilder("application/json", new JsonStreamBuilder());
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        configuration.addMessageFormatter("text/plain", new PlainTextFormatter());
        configuration.addMessageFormatter("application/octet-stream", new ExpandingMessageFormatter());
        configuration.addMessageFormatter("application/json", new JsonStreamFormatter());
        MessageContext axis = new MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(configuration));
        axis.setOperationContext(new OperationContext());
        InputStream in = new ByteArrayInputStream(content(body, "UTF-8"));
        OMElement element;
        switch (body) {
            case "application/xml":
                element = new ApplicationXMLBuilder().processDocument(in, body, axis);
                break;
            case "application/json":
                element = new JsonStreamBuilder().processDocument(in, body, axis);
                break;
            case "text/plain":
                element = new PlainTextBuilder().processDocument(in, body, axis);
                break;
            default:
                element = new BinaryRelayBuilder().processDocument(in, body, axis);
        }
        axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
        if (preset != null) {
            axis.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, preset);
        }
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private void read(Axis2MessageContext message, String path, String type, String encoding, boolean replace)
            throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("path", "/" + path);
        values.put("contentType", type);
        if (encoding != null) {
            values.put("encoding", encoding);
        }
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        message.setProperty("name", files.name);
        new ReadFile().execute(message, "content", replace);
    }

    // Writes the body as a flow would write it next and gives the digest of the file.
    private String writeBody(Axis2MessageContext message, String type) throws Exception {
        String name = "w" + names.incrementAndGet();
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/" + name);
        values.put("mimeType", type);
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        message.setProperty("name", writer.name);
        new WriteFile().execute(message, "result", false);
        return digest(Files.readAllBytes(directory.resolve(name)));
    }

    private static byte[] bytes(OMElement wrapper) throws Exception {
        OMText text = (OMText) wrapper.getFirstOMChild();
        try (InputStream in = ((DataHandler) text.getDataHandler()).getInputStream()) {
            return IOUtils.toByteArray(in);
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
