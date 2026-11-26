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
    // The write closes the parser it has spent right after the body is written, before the connector core puts the
    // result in place of the body.
    @Test public void completedStandaloneXmlKeepsEnvelopeAndClosesOnce() throws Exception {
        Path directory = WriteFileTest.directory();
        Path file = directory.resolve("xml");
        String input = "<request><value>42</value></request>";
        int[] closes = {0};
        XMLStreamReader reader = new StreamReaderDelegate(XMLInputFactory.newFactory()
                .createXMLStreamReader(new StringReader(input))) {
            @Override public void close() throws javax.xml.stream.XMLStreamException {
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
        new WriteXmlResponseTest().completedStandaloneXmlKeepsEnvelopeAndClosesOnce();
        System.out.println("PASS public WriteFile retained envelope/header/body/comment/extension; parser closed once");
    }
}
