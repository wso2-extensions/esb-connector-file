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
import org.apache.axis2.Constants;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.BinaryBuilder;
import org.apache.axis2.format.BinaryFormatter;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.commons.io.IOUtils;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonStreamFormatter;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.relay.BinaryRelayBuilder;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import java.io.InputStream;
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
import javax.activation.DataHandler;

/**
 * A read with no content type, of a file whose type has no builder of its own, reads it as bytes and names it as
 * bytes. A later write with the type of the flow then writes the bytes, whatever type the flow came with.
 */
public class ReadThenWriteTypeTest {

    private static final byte[] CONTENT = new byte[3000];

    static {
        for (int i = 0; i < CONTENT.length; i++) {
            CONTENT[i] = (byte) (i * 11);
        }
    }

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

    @DataProvider(name = "files")
    public Object[][] files() {
        List<Object[]> cases = new ArrayList<>();
        for (String extension : new String[]{".zip", ".pdf", ".png", ".qzx", ""}) {
            for (String flow : new String[]{"application/json", "text/xml", ""}) {
                cases.add(new Object[]{extension, flow});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    // A zip, a png, a file with an unknown extension and one with none have no builder of their own. The relay builder
    // reads them and the message type names application/octet-stream. A pdf has the relay pair of the pack under its
    // own type. The write with the type of the flow then gives the bytes of the file, in a flow that came with JSON,
    // with SOAP or with no type.
    @Test(dataProvider = "files")
    public void readIntoTheBodyIsWrittenAsItsBytes(String extension, String flow) throws Exception {
        String source = "s" + names.incrementAndGet() + extension;
        Files.write(directory.resolve(source), CONTENT);
        Axis2MessageContext message = message(flow);
        read(message, source, true);
        String target = "t" + names.incrementAndGet();
        write(message, target);
        Assert.assertEquals(Files.readAllBytes(directory.resolve(target)), CONTENT,
                "message type " + message.getAxis2MessageContext().getProperty(Constants.Configuration.MESSAGE_TYPE));
    }

    // The same read into a variable leaves the message type of the flow as it was.
    @Test(dataProvider = "files")
    public void readIntoAVariableLeavesTheMessageType(String extension, String flow) throws Exception {
        String source = "s" + names.incrementAndGet() + extension;
        Files.write(directory.resolve(source), CONTENT);
        Axis2MessageContext message = message(flow);
        read(message, source, false);
        Assert.assertEquals(message.getAxis2MessageContext().getProperty(Constants.Configuration.MESSAGE_TYPE),
                flow.isEmpty() ? null : flow);
    }

    // A read of the whole file with contentType application/binary into the body, as a flow does before it sends the
    // body as a BYTES message. The binary builder of the pack puts the binary wrapper with a binary node of the bytes
    // in the body. The message type is application/binary, the charset is the encoding of the read, UTF-8 by
    // default, and ContentType names application/binary too, as the read carries the type of what it built.
    @Test
    public void readAsApplicationBinaryPutsTheBinaryWrapper() throws Exception {
        String source = "s" + names.incrementAndGet() + ".png";
        Files.write(directory.resolve(source), CONTENT);
        Axis2MessageContext message = message("application/json");
        Map<String, String> values = new HashMap<>();
        values.put("contentType", "application/binary");
        values.put("enableStreaming", "false");
        values.put("enableLock", "false");
        read(message, source, true, values);
        MessageContext axis = message.getAxis2MessageContext();
        OMElement wrapper = axis.getEnvelope().getBody().getFirstElement();
        Assert.assertEquals(wrapper.getQName(), BaseConstants.DEFAULT_BINARY_WRAPPER);
        OMText node = (OMText) wrapper.getFirstOMChild();
        Assert.assertTrue(node.isBinary());
        try (InputStream content = ((DataHandler) node.getDataHandler()).getInputStream()) {
            Assert.assertEquals(IOUtils.toByteArray(content), CONTENT);
        }
        Assert.assertEquals(axis.getProperty(Constants.Configuration.MESSAGE_TYPE), "application/binary");
        Assert.assertEquals(axis.getProperty(Constants.Configuration.CHARACTER_SET_ENCODING), "UTF-8");
        Assert.assertEquals(axis.getProperty(Constants.Configuration.CONTENT_TYPE), "application/binary");
    }

    // A read into the body carries the type of the file as the message type, the property ContentType and the header
    // Content-Type, also behind a request that came with JSON and an Accept header of JSON, with content negotiation
    // on as in the pack. A write with mime type Automatic then gives the bytes of the file. A read into a variable
    // leaves the three as the request had them.
    @DataProvider(name = "typed")
    public Object[][] typed() {
        return new Object[][]{{".txt", "text/plain", "text/plain", false}, {".txt", null, "text/plain", false},
                {".xml", "application/xml", "application/xml", false}, {".xml", null, "application/xml", false},
                {".txt", "text/plain", "text/plain", true}};
    }

    @Test(dataProvider = "typed")
    public void readIntoTheBodyCarriesTheTypeOfTheFile(String extension, String contentType, String expected,
                                                       boolean streamed) throws Exception {
        byte[] content = (".xml".equals(extension) ? "<a>file</a>" : "a file\n").getBytes(StandardCharsets.UTF_8);
        for (boolean replace : streamed ? new boolean[]{true} : new boolean[]{true, false}) {
            String source = "s" + names.incrementAndGet() + extension;
            Files.write(directory.resolve(source), content);
            Axis2MessageContext message = message("application/json");
            MessageContext axis = message.getAxis2MessageContext();
            axis.getConfigurationContext().getAxisConfiguration().addParameter(
                    new Parameter(Constants.Configuration.ENABLE_HTTP_CONTENT_NEGOTIATION, "true"));
            axis.setProperty(Constants.Configuration.CONTENT_TYPE, "application/json");
            Map<String, Object> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
            axis.setProperty(MessageContext.TRANSPORT_HEADERS, headers);
            Map<String, String> values = new HashMap<>();
            if (contentType != null) {
                values.put("contentType", contentType);
            }
            values.put("enableStreaming", Boolean.toString(streamed));
            read(message, source, replace, values);
            String type = replace ? expected : "application/json";
            Assert.assertEquals(axis.getProperty(Constants.Configuration.MESSAGE_TYPE), type, "message type");
            Assert.assertEquals(axis.getProperty(Constants.Configuration.CONTENT_TYPE), type, "ContentType");
            Assert.assertEquals(headers.get("Content-Type"), type, "header");
            if (replace) {
                String target = "t" + names.incrementAndGet();
                write(message, target);
                Assert.assertEquals(new String(Files.readAllBytes(directory.resolve(target)), StandardCharsets.UTF_8),
                        new String(content, StandardCharsets.UTF_8));
            }
        }
    }

    // The builders and the formatters of the pack, as it writes conf/axis2/axis2.xml at start.
    private Axis2MessageContext message(String flow) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("application/xml", new ApplicationXMLBuilder());
        configuration.addMessageBuilder("text/plain", new PlainTextBuilder());
        configuration.addMessageBuilder("application/json", new JsonStreamBuilder());
        configuration.addMessageBuilder("application/octet-stream", new BinaryRelayBuilder());
        configuration.addMessageBuilder("application/binary", new BinaryBuilder());
        configuration.addMessageBuilder("application/pdf", new BinaryRelayBuilder());
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        configuration.addMessageFormatter("text/plain", new PlainTextFormatter());
        configuration.addMessageFormatter("application/json", new JsonStreamFormatter());
        configuration.addMessageFormatter("application/octet-stream", new ExpandingMessageFormatter());
        configuration.addMessageFormatter("application/binary", new BinaryFormatter());
        configuration.addMessageFormatter("application/pdf", new ExpandingMessageFormatter());
        MessageContext axis = new MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(configuration));
        axis.setOperationContext(new OperationContext());
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        if (!flow.isEmpty()) {
            axis.setProperty(Constants.Configuration.MESSAGE_TYPE, flow);
        }
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private void read(Axis2MessageContext message, String path, boolean replace) throws Exception {
        read(message, path, replace, new HashMap<>());
    }

    private void read(Axis2MessageContext message, String path, boolean replace, Map<String, String> values)
            throws Exception {
        values.put("path", "/" + path);
        values.put("readMode", "Complete File");
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new ReadFile().execute(message, "content", replace);
    }

    private void write(Axis2MessageContext message, String name) throws Exception {
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
    }
}
