package org.wso2.carbon.connector.operations;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Stack;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;
import org.apache.axiom.om.*;
import org.apache.axiom.om.impl.OMNodeEx;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axiom.soap.*;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.Test;

public class WriteXmlResponseTest {
    @Test public void completedStandaloneXmlKeepsEnvelopeAndClosesAfterCommit() throws Exception {
        Path directory = WriteFileTest.directory();
        Path file = directory.resolve("xml");
        String input = "<request><value>42</value></request>";
        int[] closes = {0};
        XMLStreamReader reader = new StreamReaderDelegate(XMLInputFactory.newFactory()
                .createXMLStreamReader(new StringReader(input))) {
            @Override public void close() throws javax.xml.stream.XMLStreamException {
                Assert.assertTrue(Files.isRegularFile(file), "The file must be committed before reader release");
                closes[0]++;
                super.close();
            }
        };
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext context = WriteFileTest.message(files);
            org.apache.axis2.context.MessageContext axis = context.getAxis2MessageContext();
            axis.getConfigurationContext().getAxisConfiguration().addMessageFormatter(
                    "application/xml", new ApplicationXMLFormatter());
            axis.setProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE, "application/xml");
            SOAPEnvelope envelope = axis.getEnvelope();
            SOAPBody body = envelope.getBody();
            SOAPHeader header = envelope.getHeader();
            OMFactory factory = envelope.getOMFactory();
            OMComment comment = factory.createOMComment(envelope, "keep");
            OMElement extension = factory.createOMElement("extra", factory.createOMNamespace("urn:test", "t"));
            envelope.addChild(extension);
            OMElement root = new StAXOMBuilder(reader).getDocumentElement();
            // Axis2 ApplicationXMLBuilder transfers the streaming document root without building it.
            ((OMNodeEx) root).setParent(null);
            body.addChild(root);
            TemplateContext frame = new TemplateContext("file.write", Collections.emptyList());
            frame.setMappedValues(Collections.singletonMap("filePath", "/xml"));
            Stack<TemplateContext> frames = new Stack<>();
            frames.push(frame);
            context.setProperty("_SYNAPSE_FUNCTION_STACK", frames);
            new WriteFile().execute(context, "reply", true);
            Assert.assertEquals(Files.readAllBytes(file), input.getBytes(StandardCharsets.UTF_8));
            Assert.assertEquals(closes[0], 1);
            Assert.assertSame(axis.getEnvelope(), envelope);
            Assert.assertSame(envelope.getBody(), body);
            Assert.assertSame(envelope.getHeader(), header);
            Assert.assertSame(body.getNextOMSibling(), comment);
            Assert.assertSame(comment.getNextOMSibling(), extension);
            Assert.assertEquals(body.getFirstElement().getLocalName(), "jsonObject");
            Assert.assertNotNull(context.getVariable("reply"));
        }
    }
    public static void main(String[] args) throws Exception {
        new WriteXmlResponseTest().completedStandaloneXmlKeepsEnvelopeAndClosesAfterCommit();
        System.out.println("PASS public WriteFile retained envelope/header/body/comment/extension; parser closed once after commit");
    }
}
