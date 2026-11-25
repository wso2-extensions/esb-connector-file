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
import org.apache.axiom.om.OMText;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.BinaryFormatter;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.axis2.transport.http.MultipartFormDataFormatter;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.IOUtils;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.formatters.XFormURLEncodedFormatter;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonStreamFormatter;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.relay.BinaryRelayBuilder;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import javax.activation.DataHandler;
import javax.mail.util.ByteArrayDataSource;
import javax.xml.stream.XMLInputFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every content type the pack registers a formatter for, and one type with no formatter, against every body state a
 * flow can hand to the write. The bytes of the file must be the bytes the formatter wrote before the body was kept,
 * that is with preserve false. A body that is kept must read as before after a write that is not streamed. A
 * streamed write must leave the body as it did before. A write that replaces the body must not fail on a body it
 * has spent.
 */
public class WriteBodyCrossTest {
    private static final String XML = "<a:order xmlns:a=\"urn:a\" xmlns=\"urn:d\" id=\"7\" note=\"x &amp; y\">\n"
            + "  <item a:n=\"1\">caf\u00e9 \u4e2d</item>\n"
            + "  <a:empty/><!-- a comment -->\n"
            + "  <data><![CDATA[<raw> & text]]></data>\n"
            + "  <text>1 &lt; 2 &amp;&amp; 3 &gt; 2</text>\n"
            + "  <inner xmlns:a=\"urn:a\"><a:same/></inner>\n"
            + "  <other xmlns=\"\"><plain/></other>\n"
            + "  <space>   </space>\n"
            + "</a:order>";
    private static final String SOAP = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soapenv:Header><h:trace xmlns:h=\"urn:h\">1</h:trace></soapenv:Header>"
            + "<soapenv:Body>" + XML + "</soapenv:Body></soapenv:Envelope>";
    private static final String JSON = "{\"order\":{\"id\":42,\"items\":[{\"n\":\"caf\u00e9\",\"q\":1},"
            + "{\"n\":\"b\",\"q\":2}],\"note\":null,\"flag\":true}}";
    private static final String TEXT = "line one caf\u00e9\nline two & <three>\n";
    private static final byte[] BINARY = new byte[1000];
    // XML that a parser and a tree write in the same bytes only when the serializer gets every case right.
    private static final String HARD = "<a:r xmlns:a=\"urn:a\" xmlns=\"urn:d\" one='single \"q\"' two=\"double 'q'\">\n"
            + "  mixed <b>bold</b> text &#169; &#x4e2d; &amp; &lt;&gt; &quot;\n"
            + "  <e1></e1><e2/><a:e3 xmlns:a=\"urn:a\"/><x:e4 xmlns:x=\"urn:a\"/>\n"
            + "  <c><![CDATA[a]]>b<![CDATA[]]]]><![CDATA[>]]></c><!--c1--><!---->\n"
            + "  <n xmlns=\"\"><m/></n><a:k xmlns:a=\"urn:other\">t</a:k>\n"
            + "</a:r>";
    private static final String[] TYPES = {"application/xml", "text/xml", "application/soap+xml", "application/json",
            "text/plain", "application/octet-stream", "application/x-www-form-urlencoded", "multipart/form-data",
            "text/csv"};
    private static final String[] XML_TYPES = {"application/xml", "text/xml", "application/soap+xml",
            "application/octet-stream", "text/csv"};
    private static final String[] MODES = {"Overwrite", "Create New", "Append"};

    static {
        for (int i = 0; i < BINARY.length; i++) {
            BINARY[i] = (byte) (i * 31);
        }
    }

    enum Body {
        LAZY_XML, TOUCHED_XML, BUILT_XML, LAZY_SOAP, FACTORY_XML, FACTORY_TEXT, JSON_STREAM, JSON_EXPANDED, TEXT,
        TEXT_SOURCE, BINARY, EMPTY;

        boolean xml() {
            return this == LAZY_XML || this == TOUCHED_XML || this == BUILT_XML || this == LAZY_SOAP
                    || this == FACTORY_XML;
        }
    }

    private final AtomicInteger names = new AtomicInteger();
    private Path directory;
    private FilePoolFixture files;

    @BeforeClass
    public void start() throws Exception {
        directory = WriteFileTest.directory();
        files = new FilePoolFixture("LOCAL", "workingDir", directory.toString());
    }

    @AfterClass
    public void stop() throws Exception {
        files.close();
    }

    @DataProvider(name = "cases")
    public Object[][] cases() {
        List<Object[]> cases = new ArrayList<>();
        for (String type : TYPES) {
            for (Body body : Body.values()) {
                for (boolean replace : new boolean[]{false, true}) {
                    for (String mode : MODES) {
                        for (boolean streamed : new boolean[]{false, true}) {
                            cases.add(new Object[]{type, body, replace, mode, streamed});
                        }
                    }
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "cases")
    public void writeKeepsTheBytesAndTheBody(String type, Body body, boolean replace, String mode, boolean streamed)
            throws Exception {
        String name = "f" + names.incrementAndGet();
        Path target = directory.resolve(name);
        byte[] prior = new byte[0];
        if ("Overwrite".equals(mode)) {
            Files.write(target, "old content\n".getBytes(StandardCharsets.UTF_8));
        } else if ("Append".equals(mode)) {
            prior = "prior line\n".getBytes(StandardCharsets.UTF_8);
            Files.write(target, prior);
        }

        Axis2MessageContext before = message(body);
        before.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, type);
        byte[] expected = null;
        Exception beforeFailure = null;
        try {
            expected = writeAsBefore(before.getAxis2MessageContext(), streamed);
        } catch (Exception failure) {
            beforeFailure = failure;
        }
        Axis2MessageContext untouched = message(body);
        Axis2MessageContext message = message(body);
        try {
            write(message, name, type, mode, streamed, replace, true);
        } catch (SynapseException failure) {
            Assert.assertNotNull(beforeFailure, "The write failed where the formatter passed before: " + text(failure));
            return;
        }
        Assert.assertNull(beforeFailure, "The write passed where the formatter failed before");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(prior);
        file.write(expected);
        Assert.assertEquals(digest(Files.readAllBytes(target)), digest(file.toByteArray()), "bytes of the file");
        if (replace) {
            // The whole envelope reads after the write: the header as it was, and the result alone in the body.
            MessageContext axis = message.getAxis2MessageContext();
            Assert.assertNotNull(axis.getEnvelope().toString());
            Iterator<?> children = axis.getEnvelope().getBody().getChildElements();
            Assert.assertEquals(((OMElement) children.next()).getLocalName(), "jsonObject");
            Assert.assertFalse(children.hasNext());
            Assert.assertTrue(JsonUtil.jsonPayloadToString(axis).contains("\"success\":true"));
            Assert.assertEquals(String.valueOf(axis.getEnvelope().getHeader()),
                    String.valueOf(untouched.getAxis2MessageContext().getEnvelope().getHeader()));
        } else if (streamed) {
            Assert.assertEquals(read(message, body), read(before, body), "body after a streamed write");
        } else {
            Assert.assertEquals(read(message, body), read(untouched, body), "body after the write");
        }
    }

    @DataProvider(name = "hard")
    public Object[][] hard() {
        String latin = HARD.replace("&#x4e2d;", "\u00e9\u00fc");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf16Mark = {(byte) 0xFF, (byte) 0xFE};
        Object[][] fixtures = {
                {"declaration", ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n  " + HARD
                        + "\n  <!--after-->\n").getBytes(StandardCharsets.UTF_8), null, false},
                {"byte order mark", join(bom, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + HARD)
                        .getBytes(StandardCharsets.UTF_8)), null, false},
                {"ISO-8859-1", ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + latin)
                        .getBytes(StandardCharsets.ISO_8859_1), "ISO-8859-1", false},
                {"ISO-8859-1 by declaration", ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + latin)
                        .getBytes(StandardCharsets.ISO_8859_1), null, false},
                {"UTF-16", ("<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + HARD).getBytes(StandardCharsets.UTF_16),
                        "UTF-16", false},
                {"UTF-16 with no declaration", join(utf16Mark, HARD.getBytes(StandardCharsets.UTF_16LE)), null,
                        false},
                {"SOAP", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope "
                        + "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Header><h:t xmlns:h=\"urn:h\" "
                        + "s:mustUnderstand=\"0\">1</h:t></s:Header><s:Body>" + HARD + "</s:Body></s:Envelope>")
                        .getBytes(StandardCharsets.UTF_8), null, true},
                {"namespaces of the envelope", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope "
                        + "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:m=\"urn:m\" "
                        + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                        + "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns=\"urn:d\"><soapenv:Header><m:h>1</m:h>"
                        + "</soapenv:Header><soapenv:Body xmlns:b=\"urn:b\"><m:order m:id=\"1\" plain=\"p\">"
                        + "<m:item xsi:type=\"m:ItemType\">a</m:item><value xsi:type=\"xs:string\">v</value>"
                        + "<b:x b:a=\"1\"/><d>default</d><xsi:nil/><m:e xsi:nil=\"true\"/></m:order></soapenv:Body>"
                        + "</soapenv:Envelope>").getBytes(StandardCharsets.UTF_8), null, true},
                {"DOCTYPE", ("<!DOCTYPE a:r [<!ENTITY ent \"entity\">]>" + HARD.replace("t</a:k>", "&ent;</a:k>"))
                        .getBytes(StandardCharsets.UTF_8), null, false},
        };
        List<Object[]> cases = new ArrayList<>();
        for (Object[] fixture : fixtures) {
            for (String type : XML_TYPES) {
                cases.add(new Object[]{fixture[0], fixture[1], fixture[2], fixture[3], type});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "hard")
    public void hardXmlKeepsItsBytes(String fixture, byte[] content, String charset, boolean soap, String type)
            throws Exception {
        String name = "h" + names.incrementAndGet();
        Axis2MessageContext before = message(null);
        try {
            install(before.getAxis2MessageContext(), content, charset, soap);
        } catch (Exception refused) {
            // The builder of the pack refuses a DOCTYPE before any step can write the body.
            Assert.assertTrue(text(refused).contains("DOCTYPE is not allowed"), text(refused));
            return;
        }
        before.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, type);
        byte[] expected = null;
        Exception beforeFailure = null;
        try {
            expected = writeAsBefore(before.getAxis2MessageContext(), false);
        } catch (Exception failure) {
            beforeFailure = failure;
        }
        Axis2MessageContext untouched = message(null);
        install(untouched.getAxis2MessageContext(), content, charset, soap);
        Axis2MessageContext message = message(null);
        install(message.getAxis2MessageContext(), content, charset, soap);
        try {
            write(message, name, type, "Create New", false, false, true);
        } catch (SynapseException failure) {
            Assert.assertNotNull(beforeFailure, "The write failed where the formatter passed before: " + text(failure));
            return;
        }
        Assert.assertNull(beforeFailure, "The write passed where the formatter failed before");
        Assert.assertEquals(Files.readAllBytes(directory.resolve(name)), expected, fixture);
        Assert.assertEquals(message.getAxis2MessageContext().getEnvelope().toString(),
                untouched.getAxis2MessageContext().getEnvelope().toString(), fixture);
    }

    // A processing instruction in a body that is not built is written from the tree, so one space follows the
    // target. A parser reads the same target and data. A body built before the write is written so today.
    @Test
    public void processingInstructionOfAKeptBodyGetsOneSpaceAfterTheTarget() throws Exception {
        byte[] content = "<r><?keep this?><?t?></r>".getBytes(StandardCharsets.UTF_8);
        Axis2MessageContext before = message(null);
        install(before.getAxis2MessageContext(), content, null, false);
        before.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, "application/xml");
        Assert.assertEquals(new String(writeAsBefore(before.getAxis2MessageContext(), false), StandardCharsets.UTF_8),
                "<r><?keep this?><?t?></r>");
        Axis2MessageContext message = message(null);
        install(message.getAxis2MessageContext(), content, null, false);
        String name = "p" + names.incrementAndGet();
        write(message, name, "application/xml", "Create New", false, false, true);
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(name)), StandardCharsets.UTF_8),
                "<r><?keep  this?><?t ?></r>");
        Assert.assertEquals(message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().toString(),
                "<r><?keep  this?><?t ?></r>");
    }

    @DataProvider(name = "atomic")
    public Object[][] atomic() {
        return new Object[][]{{true}, {false}};
    }

    // A SOAP envelope that is not built and holds a processing instruction in its body is not valid SOAP. The
    // write that keeps the body builds it and fails, where the write before spent the body and wrote the bytes. The
    // target is left as a body that breaks late leaves it.
    @Test(dataProvider = "atomic")
    public void processingInstructionInASoapBodyFailsTheKeptWrite(boolean atomic) throws Exception {
        byte[] soap = SOAP.replace("<item ", "<?keep this?><item ").getBytes(StandardCharsets.UTF_8);
        String pi = "p" + names.incrementAndGet();
        Files.write(directory.resolve(pi), "old content\n".getBytes(StandardCharsets.UTF_8));
        Axis2MessageContext message = message(null);
        install(message.getAxis2MessageContext(), soap, null, true);
        try {
            write(message, pi, "text/xml", "Overwrite", false, false, atomic);
            Assert.fail("The SOAP body with a processing instruction was written");
        } catch (SynapseException failure) {
            Assert.assertTrue(text(failure).contains("SOAP message MUST NOT contain Processing Instructions(PI)"),
                    text(failure));
        }
        String broken = "b" + names.incrementAndGet();
        Files.write(directory.resolve(broken), "old content\n".getBytes(StandardCharsets.UTF_8));
        Axis2MessageContext late = message(null);
        install(late.getAxis2MessageContext(), SOAP.replace("<space>", "<space><open>")
                .getBytes(StandardCharsets.UTF_8), null, true);
        try {
            write(late, broken, "text/xml", "Overwrite", false, false, atomic);
            Assert.fail("The body that breaks late was written");
        } catch (SynapseException expected) {
            // A body that breaks late fails the write too.
        }
        String left = new String(Files.readAllBytes(directory.resolve(pi)), StandardCharsets.UTF_8);
        String leftByBreak = new String(Files.readAllBytes(directory.resolve(broken)), StandardCharsets.UTF_8);
        if (atomic) {
            Assert.assertEquals(left, "old content\n");
            Assert.assertEquals(leftByBreak, "old content\n");
        } else {
            // The writer closes the open elements when the write fails, so the part that stays reads as a whole
            // envelope that ends where the body broke.
            Assert.assertTrue(left.startsWith("<?xml") && !left.contains("<item ")
                    && left.endsWith("</soapenv:Envelope>"), left);
            Assert.assertTrue(leftByBreak.startsWith("<?xml") && leftByBreak.contains("<open>")
                    && leftByBreak.endsWith("</soapenv:Envelope>"), leftByBreak);
        }
    }

    private Axis2MessageContext message(Body body) throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        MessageContext axis = message.getAxis2MessageContext();
        AxisConfiguration configuration = axis.getConfigurationContext().getAxisConfiguration();
        // The formatters of the pack (conf/axis2/axis2.xml). The JSON formatter of the pack hands on to this one.
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("application/soap+xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("text/plain", new PlainTextFormatter());
        configuration.addMessageFormatter("application/octet-stream", new ExpandingMessageFormatter());
        configuration.addMessageFormatter("application/json", new JsonStreamFormatter());
        configuration.addMessageFormatter("application/x-www-form-urlencoded", new XFormURLEncodedFormatter());
        configuration.addMessageFormatter("multipart/form-data", new MultipartFormDataFormatter());
        axis.setOperationContext(new OperationContext());
        // A fixed boundary, so that two multipart writes give the same bytes.
        axis.setProperty(Constants.Configuration.MIME_BOUNDARY, "boundary0");
        if (body != null) {
            install(axis, body);
        }
        return message;
    }

    private static void install(MessageContext axis, byte[] content, String charset, boolean soap) throws Exception {
        // As the inbound builds a file whose content type names a charset.
        String type = soap ? "text/xml" : "application/xml";
        if (charset != null) {
            axis.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charset);
            type += "; charset=" + charset;
        }
        OMElement element = soap ? new SOAPBuilder().processDocument(new ByteArrayInputStream(content), type, axis)
                : new ApplicationXMLBuilder().processDocument(new ByteArrayInputStream(content), type, axis);
        axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
        axis.removeProperty(Constants.Configuration.CHARACTER_SET_ENCODING);
    }

    private static void install(MessageContext axis, Body body) throws Exception {
        switch (body) {
            case LAZY_XML:
            case TOUCHED_XML:
            case BUILT_XML: {
                // As the inbound builds a file of application/xml.
                OMElement element = new ApplicationXMLBuilder().processDocument(stream(XML), "application/xml", axis);
                if (body == Body.BUILT_XML) {
                    element.build();
                }
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
                if (body == Body.TOUCHED_XML) {
                    // A mediator looked at the first child, so the body is built only in part.
                    axis.getEnvelope().getBody().getFirstElement().getFirstElement();
                }
                break;
            }
            case LAZY_SOAP:
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(
                        new SOAPBuilder().processDocument(stream(SOAP), "text/xml", axis)));
                break;
            case FACTORY_XML: {
                // As the payload factory puts an XML payload into the body.
                OMElement out = new StAXOMBuilder(XMLInputFactory.newInstance().createXMLStreamReader(
                        new StringReader("<format xmlns=\"http://ws.apache.org/ns/synapse\">" + XML + "</format>")))
                        .getDocumentElement();
                axis.getEnvelope().getBody().addChild(out.getFirstElement());
                break;
            }
            case FACTORY_TEXT: {
                // As the payload factory puts a text payload into the body.
                OMElement text = OMAbstractFactory.getOMFactory().createOMElement(BaseConstants.DEFAULT_TEXT_WRAPPER);
                text.setText(TEXT);
                axis.getEnvelope().getBody().addChild(text);
                break;
            }
            case JSON_STREAM:
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(
                        new JsonStreamBuilder().processDocument(stream(JSON), "application/json", axis)));
                break;
            case JSON_EXPANDED:
                // A mediator read the JSON body as XML, so its element is expanded.
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(
                        new JsonStreamBuilder().processDocument(stream(JSON), "application/json", axis)));
                axis.getEnvelope().getBody().getFirstElement().getFirstElement();
                break;
            case TEXT:
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(
                        new PlainTextBuilder().processDocument(stream(TEXT), "text/plain", axis)));
                break;
            case TEXT_SOURCE:
                // As the inbound builds a text file with Streaming true: the text is read from a data source.
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(new PlainTextBuilder().processDocument(
                        new ByteArrayDataSource(TEXT.getBytes(StandardCharsets.UTF_8), "text/plain"), "text/plain",
                        axis)));
                break;
            case EMPTY:
                break;
            case BINARY:
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(new BinaryRelayBuilder().processDocument(
                        new ByteArrayInputStream(BINARY), "application/octet-stream", axis)));
                break;
            default:
                throw new IllegalArgumentException(body.name());
        }
    }

    // The write before the body was kept: the same pick of the formatter, and preserve false.
    private static byte[] writeAsBefore(MessageContext axis, boolean streamed) throws Exception {
        MessageFormatter formatter;
        OMElement first = axis.getEnvelope().getBody().getFirstElement();
        if (streamed) {
            formatter = new ExpandingMessageFormatter();
        } else if (first != null && BaseConstants.DEFAULT_BINARY_WRAPPER.equals(first.getQName())) {
            formatter = new BinaryFormatter();
        } else if (first != null && BaseConstants.DEFAULT_TEXT_WRAPPER.equals(first.getQName())) {
            formatter = new PlainTextFormatter();
        } else {
            formatter = MessageProcessorSelector.getMessageFormatter(axis);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        formatter.writeTo(axis, BaseUtils.getOMOutputFormat(axis), output, false);
        return output.toByteArray();
    }

    private static void write(Axis2MessageContext message, String name, String type, String mode, boolean streamed,
                              boolean replace, boolean atomic) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/" + name);
        values.put("writeMode", mode);
        values.put("mimeType", type);
        values.put("enableStreaming", Boolean.toString(streamed));
        values.put("atomicWrite", Boolean.toString(atomic));
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().execute(message, "result", replace);
    }

    // What a later step reads from the body, or the kind of failure when it can not be read.
    private static String read(Axis2MessageContext message, Body body) {
        MessageContext axis = message.getAxis2MessageContext();
        try {
            switch (body) {
                case JSON_STREAM:
                    return JsonUtil.jsonPayloadToString(axis);
                case JSON_EXPANDED:
                    return JsonUtil.jsonPayloadToString(axis) + axis.getEnvelope().getBody().getFirstElement();
                case LAZY_SOAP:
                case EMPTY:
                    return axis.getEnvelope().toString();
                case TEXT:
                case TEXT_SOURCE:
                case FACTORY_TEXT:
                    return axis.getEnvelope().getBody().getFirstElement().getText();
                case BINARY: {
                    OMText text = (OMText) axis.getEnvelope().getBody().getFirstElement().getFirstOMChild();
                    try (InputStream content = ((DataHandler) text.getDataHandler()).getInputStream()) {
                        return digest(IOUtils.toByteArray(content));
                    }
                }
                default:
                    return axis.getEnvelope().getBody().getFirstElement().toString();
            }
        } catch (Exception | Error failure) {
            return "unreadable " + failure.getClass().getName();
        }
    }

    private static String text(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause).append('\n');
            for (Throwable suppressed : cause.getSuppressed()) {
                text.append(suppressed).append('\n');
            }
        }
        return text.toString();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    // Axiom names an undeclared namespace axis2ns with a counter of the JVM, so two writes of one body differ there.
    private static String digest(byte[] bytes) throws Exception {
        byte[] named = new String(bytes, StandardCharsets.ISO_8859_1).replaceAll("axis2ns\\d+", "axis2ns")
                .getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(named)) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
