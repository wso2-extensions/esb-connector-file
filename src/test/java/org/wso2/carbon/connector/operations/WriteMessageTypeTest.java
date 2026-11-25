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
import org.apache.axiom.attachments.ByteArrayDataSource;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMText;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.BinaryBuilder;
import org.apache.axis2.format.BinaryFormatter;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonStreamFormatter;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.relay.BinaryRelayBuilder;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Stack;
import javax.activation.DataHandler;

/**
 * The formatter of a body write comes from the message type, as the transport sender takes it, and never from the
 * shape of the body.
 */
public class WriteMessageTypeTest {

    private static final String PAYLOAD = "http://ws.apache.org/commons/ns/payload";
    private static final byte[] CONTENT = new byte[4096];
    // A PNG image of one pixel.
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
            + "AAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    static {
        for (int i = 0; i < CONTENT.length; i++) {
            CONTENT[i] = (byte) (i * 7);
        }
    }

    @DataProvider(name = "replace")
    public Object[][] replace() {
        return new Object[][]{{false}, {true}};
    }

    @DataProvider(name = "relay")
    public Object[][] relay() {
        return new Object[][]{{false, false, false}, {false, false, true}, {false, true, false}, {false, true, true},
                {true, false, false}, {true, false, true}, {true, true, false}, {true, true, true}};
    }

    // A file read by the relay builder of application/octet-stream, which nothing read before the write, is written
    // as its bytes, with the type set by the write and with the type of the flow, kept and replaced, streamed or not.
    // A kept body that was not streamed is written a second time in the same bytes.
    @Test(dataProvider = "relay")
    public void relaySourceUnderOctetStreamIsWrittenAsItsBytes(boolean replace, boolean streamed, boolean mimeSet)
            throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            MessageContext axis = message.getAxis2MessageContext();
            axis.setEnvelope(TransportUtils.createSOAPEnvelope(new BinaryRelayBuilder().processDocument(
                    new ByteArrayInputStream(CONTENT), "application/octet-stream", axis)));
            String mime = type(axis, "application/octet-stream", mimeSet);
            write(message, "target", mime, streamed, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")), CONTENT);
            assertResult(message, replace);
            if (!replace && !streamed) {
                write(message, "second", mime, false, false);
                Assert.assertEquals(Files.readAllBytes(directory.resolve("second")), CONTENT);
            }
        }
    }

    // An empty file, as the relay builder of application/octet-stream or the binary builder of application/binary
    // reads it, holds a binary node with no bytes. The formatter of the type writes an empty file.
    @Test(dataProvider = "replace")
    public void emptyFileFromTheBuilderIsAnEmptyFile(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            for (String type : new String[]{"application/octet-stream", "application/binary"}) {
                Axis2MessageContext message = message(files);
                MessageContext axis = message.getAxis2MessageContext();
                InputStream empty = new ByteArrayInputStream(new byte[0]);
                OMElement body = "application/binary".equals(type)
                        ? new BinaryBuilder().processDocument(empty, type, axis)
                        : new BinaryRelayBuilder().processDocument(empty, type, axis);
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(body));
                write(message, type.replace('/', '-'), type, false, replace);
                Assert.assertEquals(Files.readAllBytes(directory.resolve(type.replace('/', '-'))).length, 0, type);
                assertResult(message, replace);
            }
        }
    }

    // A text body, as a payload factory makes it, under application/binary. The binary formatter writes only the
    // content of a binary wrapper, so the file is empty, as the transport sender writes it.
    @Test(dataProvider = "replace")
    public void textBodyUnderApplicationBinaryIsAnEmptyFile(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = text(files);
            write(message, "target", "application/binary", false, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")).length, 0);
            assertResult(message, replace);
        }
    }

    // A text body, as a payload factory makes it, under application/octet-stream. The relay formatter finds no binary
    // wrapper and writes the envelope, as the transport sender writes it.
    @Test(dataProvider = "replace")
    public void textBodyUnderOctetStreamIsTheEnvelope(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = text(files);
            write(message, "target", "application/octet-stream", false, replace);
            Assert.assertEquals(new String(Files.readAllBytes(directory.resolve("target")), StandardCharsets.UTF_8),
                    "<?xml version='1.0' encoding='UTF-8'?><soapenv:Envelope xmlns:soapenv="
                            + "\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><ns:text xmlns:ns=\""
                            + PAYLOAD + "\">hello text</ns:text></soapenv:Body></soapenv:Envelope>");
            assertResult(message, replace);
        }
    }

    // An empty binary wrapper under application/binary gives an empty file. Under application/octet-stream the relay
    // formatter fails on it (WriteBinaryWrapperTextTest).
    @Test(dataProvider = "replace")
    public void emptyWrapperUnderApplicationBinaryIsAnEmptyFile(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            message.getAxis2MessageContext().getEnvelope().getBody().addChild(
                    OMAbstractFactory.getOMFactory().createOMElement(BaseConstants.DEFAULT_BINARY_WRAPPER));
            write(message, "target", "application/binary", false, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")).length, 0);
            assertResult(message, replace);
        }
    }

    // A binary body under text/plain. The text formatter writes only a text wrapper, so the file is empty, as the
    // transport sender writes it.
    @Test(dataProvider = "replace")
    public void binaryBodyUnderTextPlainIsAnEmptyFile(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            OMFactory factory = OMAbstractFactory.getOMFactory();
            OMElement wrapper = factory.createOMElement(BaseConstants.DEFAULT_BINARY_WRAPPER);
            OMText binary = factory.createOMText(new DataHandler(
                    new ByteArrayDataSource(CONTENT, "application/octet-stream")), true);
            wrapper.addChild(binary);
            message.getAxis2MessageContext().getEnvelope().getBody().addChild(wrapper);
            write(message, "target", "text/plain", false, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")).length, 0);
            assertResult(message, replace);
        }
    }

    @DataProvider(name = "reply")
    public Object[][] reply() {
        return new Object[][]{{"png", null, "application/octet-stream"}, {"random", null, "application/octet-stream"},
                {"png", "image/png", "application/octet-stream"}, {"random", "application/pdf", "application/pdf"}};
    }

    // A reply of type BYTES as the Solace connector 2.0.1 puts it on the body. With no content type on the reply it
    // is application/octet-stream: the relay builder of that type holds the bytes, the message type and ContentType
    // name it, setCharacterEncoding is false and the charset is removed. A declared type with no builder of its own,
    // as image/png, keeps the message type application/octet-stream and ContentType names the declared type. A
    // declared type with the relay pair of the pack, as application/pdf, is the message type itself. A write with
    // mime Automatic, the body kept and Overwrite gives the bytes, and a second write of the kept body as well.
    @Test(dataProvider = "reply")
    public void bytesReplyIsWrittenAsItsBytes(String content, String declared, String messageType) throws Exception {
        byte[] bytes = PNG;
        if ("random".equals(content)) {
            bytes = new byte[100 * 1024];
            new Random(7).nextBytes(bytes);
        }
        String type = declared == null ? "application/octet-stream" : declared;
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            MessageContext axis = message.getAxis2MessageContext();
            // The flow came with JSON before the reply replaced the body.
            axis.setProperty(Constants.Configuration.MESSAGE_TYPE, "application/json");
            axis.setEnvelope(TransportUtils.createSOAPEnvelope(new BinaryRelayBuilder().processDocument(
                    new ByteArrayInputStream(bytes), type, axis)));
            axis.setProperty(Constants.Configuration.MESSAGE_TYPE, messageType);
            axis.setProperty(Constants.Configuration.CONTENT_TYPE, type);
            axis.setProperty(Constants.Configuration.SET_CONTENT_TYPE_CHARACTER_ENCODING, "false");
            axis.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
            write(message, "target", "Automatic", false, false);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")), bytes);
            write(message, "second", "Automatic", false, false);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("second")), bytes);
        }
    }

    private static final String[][] FAMILIES = {
            {"binary relay", "application/octet-stream"}, {"binary", "application/binary"},
            {"xml", "application/xml"}, {"soap 1.1", "text/xml"}, {"soap 1.2", "application/soap+xml"},
            {"json", "application/json"}};

    @DataProvider(name = "families")
    public Object[][] families() {
        List<Object[]> cases = new ArrayList<>();
        for (String[] family : FAMILIES) {
            for (boolean replace : new boolean[]{false, true}) {
                for (boolean streamed : new boolean[]{false, true}) {
                    for (boolean mimeSet : new boolean[]{false, true}) {
                        cases.add(new Object[]{family[0], family[1], replace, streamed, mimeSet});
                    }
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    // A body of each family under its own type, as its builder made it: binary under application/octet-stream and
    // application/binary, XML under application/xml, SOAP under text/xml and application/soap+xml, and JSON under
    // application/json. Kept and replaced, streaming on and off, with the type set by the write and with the type of
    // the flow. The file holds the bytes the formatter of the type gives with preserve false, as the transport
    // sender writes them. A binary body is its bytes. A kept body that was not streamed is written a second time in
    // the same bytes. Only the relay body under application/octet-stream is streamed.
    @Test(dataProvider = "families")
    public void bodyOfEachFamilyIsWrittenByTheFormatterOfItsType(String family, String type, boolean replace,
                                                                 boolean streamed, boolean mimeSet) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext sender = family(files, family, type);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            MessageContext reference = sender.getAxis2MessageContext();
            reference.getConfigurationContext().getAxisConfiguration().getMessageFormatter(type)
                    .writeTo(reference, BaseUtils.getOMOutputFormat(reference), expected, false);
            if (family.startsWith("binary")) {
                Assert.assertEquals(expected.toByteArray(), CONTENT);
            }
            Axis2MessageContext message = family(files, family, type);
            String mime = type(message.getAxis2MessageContext(), type, mimeSet);
            write(message, "target", mime, streamed, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")), expected.toByteArray(),
                    new String(Files.readAllBytes(directory.resolve("target")), StandardCharsets.UTF_8));
            assertResult(message, replace);
            boolean spent = streamed && "application/octet-stream".equals(type);
            if (!replace && !spent) {
                write(message, "second", mime, false, false);
                Assert.assertEquals(Files.readAllBytes(directory.resolve("second")), expected.toByteArray());
            }
        }
    }

    @DataProvider(name = "streamedUnderAnotherType")
    public Object[][] streamedUnderAnotherType() {
        List<Object[]> cases = new ArrayList<>();
        for (String type : new String[]{"text/plain", "application/xml", "application/json", "text/xml",
                "application/x-unknown"}) {
            for (boolean replace : new boolean[]{false, true}) {
                cases.add(new Object[]{type, replace});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    // A relay body from the inbound written with streaming on and a stated type whose formatter is not the relay
    // formatter. It is not streamed. The formatter of the stated type writes it, as the transport sender writes it:
    // nothing under text/plain, and the content as base64 text under an XML, JSON or unknown type.
    @Test(dataProvider = "streamedUnderAnotherType")
    public void relayBodyStreamedUnderAnotherTypeIsWrittenByThatType(String type, boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext twin = family(files, "binary relay", "application/octet-stream");
            MessageContext reference = twin.getAxis2MessageContext();
            reference.setProperty(Constants.Configuration.MESSAGE_TYPE, type);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            MessageProcessorSelector.getMessageFormatter(reference)
                    .writeTo(reference, BaseUtils.getOMOutputFormat(reference), expected, false);
            if ("text/plain".equals(type)) {
                Assert.assertEquals(expected.size(), 0);
            }
            Axis2MessageContext message = family(files, "binary relay", "application/octet-stream");
            write(message, "target", type, true, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")), expected.toByteArray(),
                    new String(Files.readAllBytes(directory.resolve("target")), StandardCharsets.UTF_8));
            assertResult(message, replace);
            if (!replace) {
                write(message, "second", type, true, false);
                Assert.assertEquals(Files.readAllBytes(directory.resolve("second")), expected.toByteArray());
            }
        }
    }

    private static Axis2MessageContext family(FilePoolFixture files, String family, String type) throws Exception {
        Axis2MessageContext message = message(files);
        MessageContext axis = message.getAxis2MessageContext();
        OMElement element;
        switch (family) {
            case "binary relay":
                element = new BinaryRelayBuilder().processDocument(new ByteArrayInputStream(CONTENT), type, axis);
                break;
            case "binary":
                element = new BinaryBuilder().processDocument(new ByteArrayInputStream(CONTENT), type, axis);
                break;
            case "xml":
                byte[] xml = "<order><id>7</id><note>caf\u00e9</note></order>".getBytes(StandardCharsets.UTF_8);
                element = new ApplicationXMLBuilder().processDocument(new ByteArrayInputStream(xml), type, axis);
                break;
            case "json":
                byte[] json = "{\"order\":{\"id\":7,\"note\":\"caf\u00e9\"}}".getBytes(StandardCharsets.UTF_8);
                element = new JsonStreamBuilder().processDocument(new ByteArrayInputStream(json), type, axis);
                break;
            default:
                String namespace = "soap 1.1".equals(family) ? "http://schemas.xmlsoap.org/soap/envelope/"
                        : "http://www.w3.org/2003/05/soap-envelope";
                element = new SOAPBuilder().processDocument(new ByteArrayInputStream(("<s:Envelope xmlns:s=\""
                        + namespace + "\"><s:Body><order><id>7</id></order></s:Body></s:Envelope>")
                        .getBytes(StandardCharsets.UTF_8)), type, axis);
        }
        axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
        return message;
    }

    // The message type of the flow when the write sets none, else the type of the write.
    private static String type(MessageContext axis, String type, boolean mimeSet) {
        if (mimeSet) {
            return type;
        }
        axis.setProperty(Constants.Configuration.MESSAGE_TYPE, type);
        return "Automatic";
    }

    private static void assertResult(Axis2MessageContext message, boolean replace) {
        if (replace) {
            JsonObject result = JsonParser.parseString(JsonUtil.jsonPayloadToString(
                    message.getAxis2MessageContext())).getAsJsonObject();
            Assert.assertTrue(result.get("success").getAsBoolean(), result.toString());
        }
    }

    private static Axis2MessageContext text(FilePoolFixture files) throws Exception {
        Axis2MessageContext message = message(files);
        OMFactory factory = OMAbstractFactory.getOMFactory();
        OMElement text = factory.createOMElement("text", factory.createOMNamespace(PAYLOAD, "ns"));
        text.setText("hello text");
        message.getAxis2MessageContext().getEnvelope().getBody().addChild(text);
        return message;
    }

    // The formatters of the pack, as it writes conf/axis2/axis2.xml at start.
    private static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        MessageContext axis = message.getAxis2MessageContext();
        AxisConfiguration configuration = axis.getConfigurationContext().getAxisConfiguration();
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("application/soap+xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("text/plain", new PlainTextFormatter());
        configuration.addMessageFormatter("application/json", new JsonStreamFormatter());
        configuration.addMessageFormatter("application/octet-stream", new ExpandingMessageFormatter());
        configuration.addMessageFormatter("application/binary", new BinaryFormatter());
        configuration.addMessageFormatter("application/pdf", new ExpandingMessageFormatter());
        axis.setOperationContext(new OperationContext());
        return message;
    }

    private static void write(Axis2MessageContext message, String name, String type, boolean streamed,
                              boolean replace) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/" + name);
        values.put("writeMode", "Overwrite");
        values.put("mimeType", type);
        values.put("enableStreaming", Boolean.toString(streamed));
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        try {
            new WriteFile().execute(message, "result", replace);
        } catch (SynapseException failure) {
            Assert.fail("The write failed: " + failure.getMessage());
        }
    }
}
