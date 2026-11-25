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
import org.apache.axis2.Constants;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonStreamFormatter;
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
import org.wso2.carbon.relay.BinaryRelayBuilder;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A read marks the message with the charset that a later write of the body takes. An encoding of the read marks it.
 * With no encoding, the charset of the content type marks it, and UTF-8 when the content type names none.
 */
public class ReadCharsetMarkTest {

    // Two lines with no line end at the close, so a read of the first two lines gives the whole text.
    private static final String TEXT = "Grüße\ncafé";
    private static final String XML = "<r>Grüße, café</r>";
    private static final String LATIN = "ISO-8859-1";
    private static final String JSON = "{\"text\":\"Grüße, café\"}";

    private final AtomicInteger names = new AtomicInteger();
    private Path directory;
    private FilePoolFixture files;

    @BeforeClass
    public void start() throws Exception {
        directory = WriteFileTest.directory();
    }

    @BeforeMethod
    public void open() throws Exception {
        files = new FilePoolFixture("LOCAL", "workingDir", directory.toString());
    }

    @AfterMethod
    public void close() throws Exception {
        files.close();
    }

    @DataProvider(name = "modes")
    public Object[][] modes() {
        return new Object[][]{{"complete"}, {"lines"}, {"streamed"}};
    }

    // A text file in ISO-8859-1, read into the body with that charset in the content type and no encoding, is
    // written by the write of the body as the same bytes.
    @Test(dataProvider = "modes")
    public void charsetOfTheTypeMarksTheBodyForTheWrite(String mode) throws Exception {
        byte[] source = TEXT.getBytes(StandardCharsets.ISO_8859_1);
        Axis2MessageContext message = readIntoTheBody(source, ".txt", "text/plain; charset=" + LATIN, null, mode);
        Object mark = mark(message);
        byte[] written = write(message);
        Assert.assertEquals(hex(written), hex(source), "mark " + mark);
        Assert.assertEquals(mark, LATIN);
        Assert.assertEquals(text(message), TEXT);
    }

    // An encoding of the read marks the message before the charset of the content type. The text builder still
    // decodes by the charset of the content type. So the text is read right and written in the encoding.
    @Test(dataProvider = "modes")
    public void encodingOfTheReadMarksBeforeTheCharsetOfTheType(String mode) throws Exception {
        Axis2MessageContext message = readIntoTheBody(TEXT.getBytes(StandardCharsets.ISO_8859_1), ".txt",
                "text/plain; charset=" + LATIN, "UTF-16BE", mode);
        Object mark = mark(message);
        byte[] written = write(message);
        Assert.assertEquals(mark, "UTF-16BE");
        Assert.assertEquals(hex(written), hex(TEXT.getBytes(StandardCharsets.UTF_16BE)));
        Assert.assertEquals(text(message), TEXT);
    }

    // An empty encoding is no encoding, as the designer leaves the field by default. The charset of the content type
    // marks the message.
    @Test(dataProvider = "modes")
    public void emptyEncodingCountsAsNoEncoding(String mode) throws Exception {
        byte[] source = TEXT.getBytes(StandardCharsets.ISO_8859_1);
        Axis2MessageContext message = readIntoTheBody(source, ".txt", "text/plain; charset=" + LATIN, "", mode);
        Object mark = mark(message);
        byte[] written = write(message);
        Assert.assertEquals(hex(written), hex(source), "mark " + mark);
        Assert.assertEquals(mark, LATIN);
    }

    // A flow made before keeps its encoding UTF-8 in its XML, so its read marks UTF-8 and the write goes out in UTF-8,
    // as before.
    @Test(dataProvider = "modes")
    public void aFlowThatNamesUtf8KeepsUtf8(String mode) throws Exception {
        Axis2MessageContext message = readIntoTheBody(TEXT.getBytes(StandardCharsets.ISO_8859_1), ".txt",
                "text/plain; charset=" + LATIN, "UTF-8", mode);
        Object mark = mark(message);
        byte[] written = write(message);
        Assert.assertEquals(mark, "UTF-8");
        Assert.assertEquals(hex(written), hex(TEXT.getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(text(message), TEXT);
    }

    // A JSON file in ISO-8859-1 with that charset in the content type and no encoding, read into the body and
    // written again, keeps the mark UTF-8 and its bytes. Under the mark ISO-8859-1 the JSON formatter wrote a question
    // mark for each letter that is not ASCII.
    @Test
    public void jsonFileKeepsTheMarkUtf8AndItsBytes() throws Exception {
        byte[] source = JSON.getBytes(StandardCharsets.ISO_8859_1);
        Axis2MessageContext message = readIntoTheBody(source, ".json", "application/json; charset=" + LATIN, null,
                "complete");
        Object mark = mark(message);
        byte[] written = write(message);
        Assert.assertEquals(mark, "UTF-8");
        Assert.assertEquals(hex(written), hex(source));
    }

    // With no charset in the content type and no encoding, UTF-8 marks the message. Also with no content type, where
    // the read takes the type from the name of the file, or reads the bytes when the name gives no type.
    @DataProvider(name = "untyped")
    public Object[][] untyped() {
        return new Object[][]{{"text/plain", ".txt", "complete"}, {"text/plain", ".txt", "streamed"},
                {null, ".txt", "complete"}, {null, ".qzx", "complete"}};
    }

    @Test(dataProvider = "untyped")
    public void noCharsetAndNoEncodingMarksUtf8(String type, String extension, String mode) throws Exception {
        byte[] source = TEXT.getBytes(StandardCharsets.UTF_8);
        Axis2MessageContext message = readIntoTheBody(source, extension, type, null, mode);
        Object mark = mark(message);
        byte[] written = write(message);
        Assert.assertEquals(mark, "UTF-8");
        Assert.assertEquals(hex(written), hex(source));
    }

    // The XML builder decodes by the charset the message is marked with. With no encoding, the charset of the
    // content type decodes an XML file with no declaration. An encoding of the read decodes it before the charset of
    // the content type. A read into a variable leaves the message with no mark, as it came.
    @DataProvider(name = "xml")
    public Object[][] xml() {
        List<Object[]> cases = new ArrayList<>();
        for (String encoding : new String[]{null, "UTF-8"}) {
            for (boolean replace : new boolean[]{true, false}) {
                cases.add(new Object[]{encoding, replace});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "xml")
    public void markDecodesAnXmlFile(String encoding, boolean replace) throws Exception {
        String charset = encoding == null ? LATIN : encoding;
        String source = "x" + names.incrementAndGet();
        Files.write(directory.resolve(source), XML.getBytes(Charset.forName(charset)));
        Axis2MessageContext message = message();
        Map<String, String> values = new HashMap<>();
        values.put("contentType", "application/xml; charset=" + LATIN);
        if (encoding != null) {
            values.put("encoding", encoding);
        }
        read(message, source, values, replace);
        if (replace) {
            Assert.assertEquals(message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().toString(),
                    XML);
            Assert.assertEquals(mark(message), charset);
        } else {
            String payload = String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload());
            Assert.assertTrue(payload.contains(XML), payload);
            Assert.assertNull(mark(message));
        }
    }

    private Axis2MessageContext readIntoTheBody(byte[] content, String extension, String type, String encoding,
                                                String mode) throws Exception {
        String source = "s" + names.incrementAndGet() + extension;
        Files.write(directory.resolve(source), content);
        Axis2MessageContext message = message();
        Map<String, String> values = new HashMap<>();
        if (type != null) {
            values.put("contentType", type);
        }
        if (encoding != null) {
            values.put("encoding", encoding);
        }
        if ("lines".equals(mode)) {
            values.put("readMode", "Up To Line");
            values.put("endLineNum", "2");
        }
        values.put("enableStreaming", Boolean.toString("streamed".equals(mode)));
        read(message, source, values, true);
        return message;
    }

    // The builders and the formatters of the pack, as it writes conf/axis2/axis2.xml at start.
    private Axis2MessageContext message() throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
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
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private void read(Axis2MessageContext message, String path, Map<String, String> values, boolean replace)
            throws Exception {
        values.put("path", "/" + path);
        values.putIfAbsent("readMode", "Complete File");
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new ReadFile().execute(message, "content", replace);
    }

    private byte[] write(Axis2MessageContext message) throws Exception {
        String name = "t" + names.incrementAndGet();
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/" + name);
        values.put("mimeType", "Automatic");
        values.put("writeMode", "Overwrite");
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().execute(message, "result", false);
        return Files.readAllBytes(directory.resolve(name));
    }

    private static Object mark(Axis2MessageContext message) {
        return message.getAxis2MessageContext().getProperty(Constants.Configuration.CHARACTER_SET_ENCODING);
    }

    private static String text(Axis2MessageContext message) {
        return message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().getText();
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
