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
import org.apache.axiom.om.OMText;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import javax.activation.DataHandler;

/**
 * The binary wrapper with a text node that is not binary, as a payload factory or an XML parser makes it, is plain
 * XML. A write puts it into the file with the formatter of its message type, as the transport sender does, in every
 * form of the write. A wrapper with binary content and an empty wrapper are written as before.
 */
public class WriteBinaryWrapperTextTest {
    private static final String TEXT =
            Base64.getEncoder().encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
    private static final String WRAPPER =
            "<ns:binary xmlns:ns=\"http://ws.apache.org/commons/ns/payload\">" + TEXT + "</ns:binary>";
    private static final String OLD = "old content\n";

    private static Object[][] cases(int dimensions) {
        List<Object[]> cases = new ArrayList<>();
        for (int i = 0; i < 1 << dimensions; i++) {
            Object[] row = new Object[dimensions];
            for (int bit = 0; bit < dimensions; bit++) {
                row[bit] = (i & (1 << bit)) != 0;
            }
            cases.add(row);
        }
        return cases.toArray(new Object[0][]);
    }

    @DataProvider(name = "text")
    public Object[][] text() {
        return cases(2);
    }

    @Test(dataProvider = "text")
    public void textWrapperIsWrittenAsTheEnvelope(boolean parsed, boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files, parsed, WRAPPER);
            write(message, "text/xml", "Overwrite", true, false, replace);
            String written = new String(Files.readAllBytes(directory.resolve("target")), StandardCharsets.UTF_8);
            Assert.assertEquals(written, envelope(parsed));
            if (replace) {
                JsonObject result = JsonParser.parseString(JsonUtil.jsonPayloadToString(
                        message.getAxis2MessageContext())).getAsJsonObject();
                Assert.assertTrue(result.get("success").getAsBoolean(), result.toString());
            }
        }
    }

    @DataProvider(name = "streamed")
    public Object[][] streamed() {
        return cases(3);
    }

    // With streaming on, a write straight into the target and an append put the same bytes as the atomic write.
    @Test(dataProvider = "streamed")
    public void textWrapperStreamedIsWrittenInTheSameBytes(boolean parsed, boolean replace, boolean append)
            throws Exception {
        Path directory = WriteFileTest.directory();
        Path target = directory.resolve("target");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            write(message(files, parsed, WRAPPER), "text/xml", "Overwrite", true, false, replace);
            byte[] expected = Files.readAllBytes(target);
            Files.delete(target);
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            if (append) {
                Files.write(target, OLD.getBytes(StandardCharsets.UTF_8));
                file.write(OLD.getBytes(StandardCharsets.UTF_8));
            }
            file.write(expected);
            write(message(files, parsed, WRAPPER), "text/xml", append ? "Append" : "Overwrite", false, true,
                    replace);
            Assert.assertEquals(new String(Files.readAllBytes(target), StandardCharsets.UTF_8),
                    file.toString("UTF-8"));
        }
    }

    // The relay formatter of application/octet-stream asks the text node for binary content and fails, as the
    // transport sender does with the same formatter. The write fails as a content failure and writes nothing.
    @Test(dataProvider = "streamed")
    public void textWrapperUnderOctetStreamFailsAsBefore(boolean parsed, boolean replace, boolean streamed)
            throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files, parsed, WRAPPER);
            try {
                write(message, "application/octet-stream", "Overwrite", true, streamed, replace);
                Assert.fail("The text wrapper was written under application/octet-stream");
            } catch (SynapseException failure) {
                Assert.assertTrue(failure.getMessage().contains("The message body could not be read"),
                        failure.getMessage());
            }
            Assert.assertFalse(Files.exists(directory.resolve("target")));
            if (replace) {
                JsonObject result = JsonParser.parseString(JsonUtil.jsonPayloadToString(
                        message.getAxis2MessageContext())).getAsJsonObject();
                Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), "700108");
            }
        }
    }

    @DataProvider(name = "binary")
    public Object[][] binary() {
        return cases(4);
    }

    // Under application/octet-stream a wrapper with binary content is written as that content, in every form of the
    // write, and streamed when streaming is on. Under text/xml the SOAP formatter writes the envelope with the content
    // as base64 text, as the transport sender does, also when streaming is on.
    @Test(dataProvider = "binary")
    public void binaryContentIsWrittenByTheFormatterOfItsType(boolean parsed, boolean replace, boolean streamed,
                                                               boolean soap)
            throws Exception {
        byte[] content = new byte[300];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 13);
        }
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            for (boolean atomic : new boolean[]{true, false}) {
                OMText binary = OMAbstractFactory.getOMFactory().createOMText(
                        new DataHandler(new ByteArrayDataSource(content, "application/octet-stream")), true);
                Axis2MessageContext message = wrapped(files, parsed, binary);
                Files.deleteIfExists(directory.resolve("target"));
                write(message, soap ? "text/xml" : "application/octet-stream", "Overwrite", atomic, streamed,
                        replace);
                byte[] written = Files.readAllBytes(directory.resolve("target"));
                if (soap) {
                    String envelope = new String(written, StandardCharsets.UTF_8);
                    Assert.assertTrue(envelope.startsWith("<?xml") && envelope.contains("Envelope"), envelope);
                    Assert.assertTrue(envelope.contains(">" + Base64.getEncoder().encodeToString(content) + "</"),
                            envelope);
                } else {
                    Assert.assertEquals(written, content, "atomic " + atomic);
                }
            }
        }
    }

    @DataProvider(name = "empty")
    public Object[][] empty() {
        return cases(2);
    }

    // The relay formatter of application/octet-stream finds no content in an empty wrapper and fails, as the
    // transport sender fails with the same formatter. The write fails as a content failure and writes nothing.
    @Test(dataProvider = "empty")
    public void emptyWrapperUnderOctetStreamFailsAsTheTransport(boolean parsed, boolean streamed) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            try {
                write(wrapped(files, parsed, null), "application/octet-stream", "Overwrite", true, streamed, false);
                Assert.fail("The empty wrapper was written under application/octet-stream");
            } catch (SynapseException failure) {
                Assert.assertTrue(failure.getMessage().contains("The message body could not be read"),
                        failure.getMessage());
            }
            Assert.assertFalse(Files.exists(directory.resolve("target")));
        }
    }

    private static String envelope(boolean parsed) {
        return "<?xml version='1.0' encoding='UTF-8'?>" + (parsed
                ? "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Header /><s:Body>" + WRAPPER
                + "</s:Body></s:Envelope>"
                : "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + WRAPPER + "</soapenv:Body></soapenv:Envelope>");
    }

    // A parsed body comes from the SOAP builder, as a request that came in as SOAP. The other is put into the body
    // of a new envelope from a string, as a payload factory does.
    private static Axis2MessageContext message(FilePoolFixture files, boolean parsed, String body) throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        MessageContext axis = message.getAxis2MessageContext();
        AxisConfiguration configuration = axis.getConfigurationContext().getAxisConfiguration();
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("application/octet-stream", new ExpandingMessageFormatter());
        axis.setOperationContext(new OperationContext());
        if (parsed) {
            String envelope = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Header/><s:Body>"
                    + body + "</s:Body></s:Envelope>";
            ByteArrayInputStream content = new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8));
            axis.setEnvelope(TransportUtils.createSOAPEnvelope(
                    new SOAPBuilder().processDocument(content, "text/xml", axis)));
        } else {
            axis.getEnvelope().getBody().addChild(AXIOMUtil.stringToOM(body));
        }
        return message;
    }

    // The binary wrapper made in the flow, with the given content or empty.
    private static Axis2MessageContext wrapped(FilePoolFixture files, boolean parsed, OMText content)
            throws Exception {
        Axis2MessageContext message = message(files, parsed, "<order/>");
        OMElement body = message.getAxis2MessageContext().getEnvelope().getBody();
        body.getFirstElement().detach();
        OMElement wrapper = OMAbstractFactory.getOMFactory().createOMElement(BaseConstants.DEFAULT_BINARY_WRAPPER);
        if (content != null) {
            wrapper.addChild(content);
        }
        body.addChild(wrapper);
        return message;
    }

    private static void write(Axis2MessageContext message, String type, String mode, boolean atomic,
                              boolean streamed, boolean replace) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/target");
        values.put("writeMode", mode);
        values.put("mimeType", type);
        values.put("atomicWrite", Boolean.toString(atomic));
        values.put("enableStreaming", Boolean.toString(streamed));
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().execute(message, "result", replace);
    }
}
