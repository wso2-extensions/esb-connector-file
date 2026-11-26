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
import org.apache.axiom.om.OMDataSource;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.om.impl.llom.OMSourcedElementImpl;
import org.apache.axiom.om.util.AXIOMUtil;
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
import org.apache.axiom.om.ds.WrappedTextNodeOMDataSourceFromReader;
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
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.Charset;
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
 * that is with preserve false. A body that is kept must read as before after the write. Only a binary body is
 * streamed, and it is left as before. Any other body is written as without streaming, by the formatter of its
 * message type, as the transport sender writes it. A write that replaces the body must not fail on a body it
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
            "text/plain", "application/octet-stream", "application/binary", "application/x-www-form-urlencoded",
            "multipart/form-data", "text/csv"};
    private static final String[] XML_TYPES = {"application/xml", "text/xml", "application/soap+xml",
            "application/octet-stream", "text/csv"};
    private static final String[] MODES = {"Overwrite", "Create New", "Append"};

    static {
        for (int i = 0; i < BINARY.length; i++) {
            BINARY[i] = (byte) (i * 31);
        }
    }

    enum Body {
        LAZY_XML, TOUCHED_XML, BUILT_XML, LAZY_SOAP, TOUCHED_SOAP, BUILT_SOAP, FACTORY_XML, FACTORY_TEXT,
        JSON_STREAM, JSON_EXPANDED, TEXT, TEXT_SOURCE, BINARY, EMPTY;

        boolean soap() {
            return this == LAZY_SOAP || this == TOUCHED_SOAP || this == BUILT_SOAP;
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

        // Only a binary body under the relay formatter of application/octet-stream is streamed.
        boolean streamedBinary = streamed && body == Body.BINARY && "application/octet-stream".equals(type);
        Axis2MessageContext before = message(body);
        before.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, type);
        byte[] expected = null;
        Exception beforeFailure = null;
        try {
            expected = writeAsBefore(before.getAxis2MessageContext());
        } catch (Exception failure) {
            beforeFailure = failure;
        }
        if (beforeFailure != null && !replace && !streamedBinary && body != Body.JSON_STREAM) {
            // The transport sender writes with preserve false, and a formatter that writes XML fails on a text source
            // that can be read once. The write keeps the body, so the formatter expands the source and writes it. A
            // JSON stream body is written with preserve false in both, so it fails in both.
            Axis2MessageContext kept = message(body);
            kept.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, type);
            try {
                expected = writeKept(kept.getAxis2MessageContext());
                beforeFailure = null;
            } catch (Exception failure) {
                // The formatter fails on the kept body too.
            }
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
        } else if (streamedBinary) {
            Assert.assertEquals(read(message, body), read(before, body), "body after a streamed write");
        } else {
            Assert.assertEquals(read(message, body), read(untouched, body), "body after the write");
        }
        if (!replace && !streamedBinary) {
            // A kept body is written a second time in the same bytes.
            String second = name + "-second";
            write(message, second, type, "Create New", streamed, false, true);
            Assert.assertEquals(digest(Files.readAllBytes(directory.resolve(second))), digest(expected),
                    "bytes of a second write of the kept body");
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
                {"SOAP pretty printed", soapBytes("\n  <!-- under the envelope -->\n  <s:Header>\n    <!-- in the header -->"
                        + "\n    <h:t xmlns:h=\"urn:h\">1</h:t>\n    text\n  </s:Header>\n  <s:Body>\n    " + HARD
                        + "\n  </s:Body>\n", "UTF-8", null, S11), null, true},
                {"SOAP empty header", soapBytes("<s:Header/><s:Body>" + HARD + "</s:Body>", "UTF-8", null, S11), null,
                        true},
                {"SOAP no header", soapBytes("<s:Body>" + HARD + "</s:Body>", "UTF-8", null, S11), null, true},
                {"SOAP 1.2", soapBytes("\n <s:Header/>\n <s:Body>" + HARD + "</s:Body>\n", "UTF-8", null, S12), null,
                        true},
                {"SOAP element after the body", soapBytes("<s:Body>" + HARD + "</s:Body><x:after xmlns:x=\"urn:x\"/>",
                        "UTF-8", null, S11), null, true},
                {"SOAP declaration with standalone", soapBytes("\n <s:Body>" + HARD + "</s:Body>\n", "UTF-8",
                        " standalone=\"yes\"", S11), null, true},
                {"SOAP ISO-8859-1", soapBytes("\n <s:Body>" + latin + "</s:Body>\n", "ISO-8859-1", null, S11),
                        "ISO-8859-1", true},
                {"SOAP UTF-16", soapBytes("\n <s:Body>" + HARD + "</s:Body>\n", "UTF-16", null, S11), "UTF-16", true},
                {"SOAP byte order mark", join(bom, soapBytes("\n <s:Body>" + HARD + "</s:Body>\n", "UTF-8", null,
                        S11)), null, true},
                {"DOCTYPE", ("<!DOCTYPE a:r [<!ENTITY ent \"entity\">]>" + HARD.replace("t</a:k>", "&ent;</a:k>"))
                        .getBytes(StandardCharsets.UTF_8), null, false},
        };
        List<Object[]> cases = new ArrayList<>();
        for (Object[] fixture : fixtures) {
            // A SOAP envelope is written as the parser made it, as a log read it, and as a mediator touched it.
            // An envelope with an element after the body can not be built at all, by any step of a flow.
            String[] states = !Boolean.TRUE.equals(fixture[3]) ? new String[]{"lazy"}
                    : "SOAP element after the body".equals(fixture[0]) ? new String[]{"lazy", "logged", "touched"}
                    : new String[]{"lazy", "logged", "touched", "built"};
            for (String state : states) {
                for (String type : XML_TYPES) {
                    cases.add(new Object[]{fixture[0], fixture[1], fixture[2], fixture[3], type, state});
                }
                if ("namespaces of the envelope".equals(fixture[0])) {
                    cases.add(new Object[]{fixture[0], fixture[1], fixture[2], fixture[3], "application/json", state});
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "hard")
    public void hardXmlKeepsItsBytes(String fixture, byte[] content, String charset, boolean soap, String type,
                                     String state) throws Exception {
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
        // A built envelope gives the file of the lazy one: the bytes do not depend on what built it before.
        touch(before.getAxis2MessageContext(), "built".equals(state) && !"application/xml".equals(type)
                ? "lazy" : state);
        byte[] expected = null;
        Exception beforeFailure = null;
        try {
            expected = writeAsBefore(before.getAxis2MessageContext());
        } catch (Exception failure) {
            beforeFailure = failure;
        }
        Axis2MessageContext untouched = message(null);
        install(untouched.getAxis2MessageContext(), content, charset, soap);
        Axis2MessageContext message = message(null);
        install(message.getAxis2MessageContext(), content, charset, soap);
        touch(message.getAxis2MessageContext(), state);
        try {
            write(message, name, type, "Create New", false, false, true);
        } catch (SynapseException failure) {
            if ("SOAP element after the body".equals(fixture) && !"application/xml".equals(type)) {
                // The SOAP builder refuses an element after the body when it builds, as it refuses a processing
                // instruction. The write that keeps the body builds the envelope, so it fails where the write that
                // spent the body wrote it. A ruled row.
                Assert.assertTrue(text(failure).contains("Envelope can not have elements other than Header and Body"),
                        text(failure));
                return;
            }
            Assert.assertNotNull(beforeFailure, "The write failed where the formatter passed before: " + text(failure));
            return;
        }
        Assert.assertNull(beforeFailure, "The write passed where the formatter failed before");
        Assert.assertEquals(Files.readAllBytes(directory.resolve(name)), expected, fixture);
        Assert.assertEquals(message.getAxis2MessageContext().getEnvelope().toString(),
                untouched.getAxis2MessageContext().getEnvelope().toString(), fixture);
        // The kept body is written a second time in the same bytes.
        write(message, name + "-second", type, "Create New", false, false, true);
        Assert.assertEquals(Files.readAllBytes(directory.resolve(name + "-second")), expected, fixture);
    }

    // A processing instruction in a body that is not built is written from the tree, so one space follows the
    // target. A parser reads the same target and data. A body built before the write is written so today.
    @Test
    public void processingInstructionOfAKeptBodyGetsOneSpaceAfterTheTarget() throws Exception {
        byte[] content = "<r><?keep this?><?t?></r>".getBytes(StandardCharsets.UTF_8);
        Axis2MessageContext before = message(null);
        install(before.getAxis2MessageContext(), content, null, false);
        before.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, "application/xml");
        Assert.assertEquals(new String(writeAsBefore(before.getAxis2MessageContext()), StandardCharsets.UTF_8),
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

    // Three declarations on an element the parser has not built yet. The reader that keeps what it reads takes them
    // from the tree, the order of a declaration list is not content. The test pins what is written.
    @Test
    public void declarationsOfAnElementThatIsNotBuilt() throws Exception {
        byte[] content = ("<s:Envelope xmlns:s=\"" + S11 + "\"><s:Body><m:x xmlns:xsi=\"urn:xsi\" xmlns:xsd=\"urn:xsd\" "
                + "xmlns:m=\"urn:m\"><m:y xsi:type=\"xsd:string\">v</m:y><m:o xmlns:m=\"urn:m\" xmlns:xsi=\"urn:xsi\" "
                + "xmlns:a=\"urn:a\"/></m:x></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
        Axis2MessageContext before = message(null);
        install(before.getAxis2MessageContext(), content, null, true);
        before.getAxis2MessageContext().setProperty(Constants.Configuration.MESSAGE_TYPE, "text/xml");
        String spent = new String(writeAsBefore(before.getAxis2MessageContext()), StandardCharsets.UTF_8);
        Axis2MessageContext message = message(null);
        install(message.getAxis2MessageContext(), content, null, true);
        String name = "n" + names.incrementAndGet();
        write(message, name, "text/xml", "Create New", false, false, true);
        String kept = new String(Files.readAllBytes(directory.resolve(name)), StandardCharsets.UTF_8);
        Assert.assertEquals(kept.replaceAll(" xmlns:[a-z]+=\"[^\"]*\"", ""),
                spent.replaceAll(" xmlns:[a-z]+=\"[^\"]*\"", ""));
        for (String declaration : new String[]{"xmlns:xsi=\"urn:xsi\"", "xmlns:xsd=\"urn:xsd\"", "xmlns:m=\"urn:m\""}) {
            Assert.assertTrue(kept.contains(declaration), kept);
        }
    }

    // A plain data source that can be read only once, under a parsed envelope, is written twice in the same bytes.
    @Test
    public void plainSourceThatReadsOnceIsWrittenTwice() throws Exception {
        Axis2MessageContext message = message(null);
        MessageContext axis = message.getAxis2MessageContext();
        install(axis, ("<s:Envelope xmlns:s=\"" + S11 + "\">\n <s:Body><old/></s:Body>\n</s:Envelope>")
                .getBytes(StandardCharsets.UTF_8), null, true);
        axis.getEnvelope().getBody().getFirstElement().detach();
        OMDataSource once = new OMDataSource() {
            private boolean read;

            private void take() throws XMLStreamException {
                if (read) {
                    throw new XMLStreamException("The source was read before");
                }
                read = true;
            }

            @Override
            public void serialize(OutputStream output, OMOutputFormat format) throws XMLStreamException {
                serialize(XMLOutputFactory.newInstance().createXMLStreamWriter(output));
            }

            @Override
            public void serialize(Writer writer, OMOutputFormat format) throws XMLStreamException {
                serialize(XMLOutputFactory.newInstance().createXMLStreamWriter(writer));
            }

            @Override
            public void serialize(XMLStreamWriter writer) throws XMLStreamException {
                take();
                writer.writeStartElement("once");
                writer.writeCharacters("read once");
                writer.writeEndElement();
            }

            @Override
            public XMLStreamReader getReader() throws XMLStreamException {
                take();
                return XMLInputFactory.newInstance().createXMLStreamReader(new StringReader("<once>read once</once>"));
            }
        };
        axis.getEnvelope().getBody().addChild(new OMSourcedElementImpl(new QName("once"),
                OMAbstractFactory.getOMFactory(), once));
        String first = "q" + names.incrementAndGet();
        String second = "q" + names.incrementAndGet();
        write(message, first, "text/xml", "Create New", false, false, true);
        write(message, second, "text/xml", "Create New", false, false, true);
        String written = new String(Files.readAllBytes(directory.resolve(first)), StandardCharsets.UTF_8);
        Assert.assertTrue(written.contains("<once>read once</once>"), written);
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(second)), StandardCharsets.UTF_8),
                written);
    }

    // A JSON payload put into a parsed envelope by a payload factory, partly read by an XPath, written as text/xml
    // with the body kept: the payload is written from its tree, twice in the same bytes, with no second document.
    @Test
    public void jsonPayloadInAParsedEnvelopeIsWrittenTwice() throws Exception {
        Axis2MessageContext message = message(null);
        MessageContext axis = message.getAxis2MessageContext();
        install(axis, ("<s:Envelope xmlns:s=\"" + S11 + "\">\n <s:Body><old/></s:Body>\n</s:Envelope>")
                .getBytes(StandardCharsets.UTF_8), null, true);
        JsonUtil.getNewJsonPayload(axis, JSON, true, true);
        axis.getEnvelope().getBody().getFirstElement().getFirstElement();
        String first = "j" + names.incrementAndGet();
        String second = "j" + names.incrementAndGet();
        write(message, first, "text/xml", "Create New", false, false, true);
        write(message, second, "text/xml", "Create New", false, false, true);
        String written = new String(Files.readAllBytes(directory.resolve(first)), StandardCharsets.UTF_8);
        Assert.assertEquals(written.indexOf("<?xml", 1), -1, written);
        Assert.assertTrue(written.contains("<jsonObject>") && written.contains("<flag>true</flag>"), written);
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(second)), StandardCharsets.UTF_8),
                written);
    }

    // A parsed envelope whose body a payload factory replaced is written twice in the same bytes, and a message
    // that asks for no declaration gets none.
    @DataProvider(name = "declaration")
    public Object[][] declaration() {
        return new Object[][]{{true}, {false}};
    }

    @Test(dataProvider = "declaration")
    public void replacedBodyOfAParsedEnvelopeIsWrittenTwice(boolean declaration) throws Exception {
        Axis2MessageContext message = message(null);
        MessageContext axis = message.getAxis2MessageContext();
        install(axis, ("<s:Envelope xmlns:s=\"" + S11 + "\">\n <s:Header/>\n <s:Body><old/></s:Body>\n</s:Envelope>")
                .getBytes(StandardCharsets.UTF_8), null, true);
        if (!declaration) {
            axis.setProperty("WRITE_XML_DECLARATION", "false");
        }
        axis.getEnvelope().getBody().getFirstElement().detach();
        axis.getEnvelope().getBody().addChild(AXIOMUtil.stringToOM("<made><by>factory</by></made>"));
        String first = "r" + names.incrementAndGet();
        String second = "r" + names.incrementAndGet();
        write(message, first, "text/xml", "Create New", false, false, true);
        write(message, second, "text/xml", "Create New", false, false, true);
        String written = new String(Files.readAllBytes(directory.resolve(first)), StandardCharsets.UTF_8);
        Assert.assertEquals(written.startsWith("<?xml"), declaration, written);
        Assert.assertTrue(written.contains("<s:Header />") && written.contains("<made><by>factory</by></made>"),
                written);
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(second)), StandardCharsets.UTF_8),
                written);
    }

    // An element of a SOAP body whose data source can be read only once is written twice in the same bytes.
    @Test
    public void sourceThatReadsOnceIsWrittenTwice() throws Exception {
        Axis2MessageContext message = message(null);
        MessageContext axis = message.getAxis2MessageContext();
        install(axis, ("<s:Envelope xmlns:s=\"" + S11 + "\">\n <s:Body><old/></s:Body>\n</s:Envelope>")
                .getBytes(StandardCharsets.UTF_8), null, true);
        axis.getEnvelope().getBody().getFirstElement().detach();
        OMFactory factory = OMAbstractFactory.getOMFactory();
        QName wrapper = BaseConstants.DEFAULT_TEXT_WRAPPER;
        axis.getEnvelope().getBody().addChild(new OMSourcedElementImpl(wrapper, factory,
                new WrappedTextNodeOMDataSourceFromReader(wrapper, new StringReader(TEXT))));
        String first = "o" + names.incrementAndGet();
        String second = "o" + names.incrementAndGet();
        write(message, first, "text/xml", "Create New", false, false, true);
        write(message, second, "text/xml", "Create New", false, false, true);
        String written = new String(Files.readAllBytes(directory.resolve(first)), StandardCharsets.UTF_8);
        Assert.assertTrue(written.contains("line two"), written);
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(second)), StandardCharsets.UTF_8),
                written);
    }

    @DataProvider(name = "atomic")
    public Object[][] atomic() {
        return new Object[][]{{true}, {false}};
    }

    // A SOAP envelope that is not built and holds a processing instruction in its body is not valid SOAP. The
    // write that keeps the body builds it and fails, where the write before spent the body and wrote the bytes. The
    // target is left as it was, as after a body that breaks late.
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
        // An atomic write drops its temp file, and a write straight into the target builds the body before it opens
        // the target. So both leave the target as it was.
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(pi)), StandardCharsets.UTF_8),
                "old content\n");
        Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(broken)), StandardCharsets.UTF_8),
                "old content\n");
    }

    private Axis2MessageContext message(Body body) throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        MessageContext axis = message.getAxis2MessageContext();
        AxisConfiguration configuration = axis.getConfigurationContext().getAxisConfiguration();
        // The formatters of the pack (conf/axis2/axis2.xml as the pack writes it at start). The JSON formatter of the
        // pack hands on to this one.
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("application/soap+xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("text/plain", new PlainTextFormatter());
        configuration.addMessageFormatter("application/octet-stream", new ExpandingMessageFormatter());
        configuration.addMessageFormatter("application/binary", new BinaryFormatter());
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

    private static final String S11 = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String S12 = "http://www.w3.org/2003/05/soap-envelope";

    private static byte[] soapBytes(String inside, String charset, String standalone, String version) {
        return ("<?xml version=\"1.0\" encoding=\"" + charset + "\"" + (standalone == null ? "" : standalone)
                + "?>\n<s:Envelope xmlns:s=\"" + version + "\">" + inside + "</s:Envelope>\n")
                .getBytes(Charset.forName(charset));
    }

    // As a log reads the envelope whole, or as a mediator looks at the first element of the body.
    private static void touch(MessageContext axis, String state) {
        if ("logged".equals(state)) {
            axis.getEnvelope().toString();
        } else if ("built".equals(state)) {
            axis.getEnvelope().build();
        } else if ("touched".equals(state)) {
            axis.getEnvelope().getBody().getFirstElement();
        }
    }

    private static void install(MessageContext axis, byte[] content, String charset, boolean soap) throws Exception {
        // As the inbound builds a file whose content type names a charset.
        String type = soap ? (new String(content, StandardCharsets.ISO_8859_1).contains(S12) ? "application/soap+xml"
                : "text/xml") : "application/xml";
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
            case TOUCHED_SOAP:
            case BUILT_SOAP:
                axis.setEnvelope(TransportUtils.createSOAPEnvelope(
                        new SOAPBuilder().processDocument(stream(SOAP), "text/xml", axis)));
                if (body == Body.BUILT_SOAP) {
                    // As a full log builds the envelope. Axiom still takes it as not complete.
                    axis.getEnvelope().toString();
                } else if (body == Body.TOUCHED_SOAP) {
                    axis.getEnvelope().getBody().getFirstElement().getFirstElement();
                }
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

    // The write as the transport sender writes it: the formatter of the message type, and preserve false. A streamed
    // write of a binary body under application/octet-stream takes the same relay formatter.
    private static byte[] writeAsBefore(MessageContext axis) throws Exception {
        MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(axis);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        formatter.writeTo(axis, BaseUtils.getOMOutputFormat(axis), output, false);
        return output.toByteArray();
    }

    private static byte[] writeKept(MessageContext axis) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MessageProcessorSelector.getMessageFormatter(axis).writeTo(axis, BaseUtils.getOMOutputFormat(axis), output,
                true);
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
                case TOUCHED_SOAP:
                case BUILT_SOAP:
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
