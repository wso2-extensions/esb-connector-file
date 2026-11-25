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
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
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

/**
 * A write straight into the target, with atomicWrite false or in append mode, of a body that breaks late fails before
 * its first byte and leaves the target as it was, as the transport sender does. A body that does not break is written
 * in the same bytes as by an atomic write.
 */
public class WriteDirectBodyTest {
    private static final String OLD = "old content\n";

    private static String items() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            items.append("<item>").append(i).append("</item>");
        }
        return items.toString();
    }

    private static String xml(boolean broken) {
        return "<order>" + items() + (broken ? "<item></order>" : "</order>");
    }

    private static String soap(boolean broken) {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Header/><s:Body>" + xml(broken)
                + "</s:Body></s:Envelope>";
    }

    @DataProvider(name = "broken")
    public Object[][] broken() {
        List<Object[]> cases = new ArrayList<>();
        for (boolean soap : new boolean[]{false, true}) {
            for (String mode : new String[]{"Overwrite", "Create New", "Append"}) {
                for (boolean replace : new boolean[]{false, true}) {
                    cases.add(new Object[]{soap, mode, replace});
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "broken")
    public void bodyThatBreaksLateLeavesTheTargetAsItWas(boolean soap, String mode, boolean replace)
            throws Exception {
        Path directory = WriteFileTest.directory();
        Path target = directory.resolve("target");
        if (!"Create New".equals(mode)) {
            Files.write(target, OLD.getBytes(StandardCharsets.UTF_8));
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files, soap ? soap(true) : xml(true), soap);
            try {
                write(message, soap ? "text/xml" : "application/xml", mode, false, replace);
                Assert.fail("The body that breaks late was written");
            } catch (SynapseException failure) {
                Assert.assertTrue(failure.getMessage().contains("The message body could not be read"),
                        failure.getMessage());
            }
            if ("Create New".equals(mode)) {
                Assert.assertFalse(Files.exists(target));
            } else {
                Assert.assertEquals(new String(Files.readAllBytes(target), StandardCharsets.UTF_8), OLD);
            }
            if (replace) {
                JsonObject result = JsonParser.parseString(JsonUtil.jsonPayloadToString(
                        message.getAxis2MessageContext())).getAsJsonObject();
                Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), "700108");
            }
        }
    }

    @DataProvider(name = "good")
    public Object[][] good() {
        return new Object[][]{{false, false}, {false, true}, {true, false}, {true, true}};
    }

    @Test(dataProvider = "good")
    public void bodyThatIsWholeIsWrittenAsByAnAtomicWrite(boolean soap, boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            String type = soap ? "text/xml" : "application/xml";
            Axis2MessageContext atomic = message(files, soap ? soap(false) : xml(false), soap);
            write(atomic, type, "Overwrite", true, replace);
            byte[] expected = Files.readAllBytes(directory.resolve("target"));
            Files.delete(directory.resolve("target"));
            Axis2MessageContext direct = message(files, soap ? soap(false) : xml(false), soap);
            write(direct, type, "Overwrite", false, replace);
            Assert.assertEquals(Files.readAllBytes(directory.resolve("target")), expected);
        }
    }

    // A parsed envelope whose body a payload factory made the binary wrapper with base64 text, the way to write a
    // decoded file. Every form of the write puts the decoded bytes into the file.
    @DataProvider(name = "binary")
    public Object[][] binary() {
        List<Object[]> cases = new ArrayList<>();
        for (boolean streamed : new boolean[]{true, false}) {
            for (String mode : new String[]{"Overwrite", "Append"}) {
                cases.add(new Object[]{streamed, mode});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "binary")
    public void binaryBodyOfAParsedEnvelopeIsWrittenDecoded(boolean streamed, String mode) throws Exception {
        byte[] decoded = new byte[300];
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = (byte) (i * 13);
        }
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            for (boolean atomic : new boolean[]{true, false}) {
                Axis2MessageContext message = message(files, soap(false), true);
                MessageContext axis = message.getAxis2MessageContext();
                axis.setOperationContext(new OperationContext());
                axis.getConfigurationContext().getAxisConfiguration().addMessageFormatter("application/octet-stream",
                        new ExpandingMessageFormatter());
                axis.getEnvelope().getBody().getFirstElement().detach();
                OMElement wrapper = OMAbstractFactory.getOMFactory().createOMElement(BaseConstants.DEFAULT_BINARY_WRAPPER);
                wrapper.addChild(OMAbstractFactory.getOMFactory().createOMText(
                        Base64.getEncoder().encodeToString(decoded), "application/octet-stream", true));
                axis.getEnvelope().getBody().addChild(wrapper);
                Files.deleteIfExists(directory.resolve("target"));
                Map<String, String> values = new HashMap<>();
                values.put("filePath", "/target");
                values.put("writeMode", mode);
                values.put("mimeType", "application/octet-stream");
                values.put("atomicWrite", Boolean.toString(atomic));
                values.put("enableStreaming", Boolean.toString(streamed));
                TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
                template.setMappedValues(values);
                Stack<TemplateContext> stack = new Stack<>();
                stack.push(template);
                message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
                new WriteFile().execute(message, "result", false);
                Assert.assertEquals(Files.readAllBytes(directory.resolve("target")), decoded,
                        "atomic " + atomic);
            }
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files, String body, boolean soap) throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        MessageContext axis = message.getAxis2MessageContext();
        AxisConfiguration configuration = axis.getConfigurationContext().getAxisConfiguration();
        configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
        configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
        ByteArrayInputStream content = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        OMElement element = soap ? new SOAPBuilder().processDocument(content, "text/xml", axis)
                : new ApplicationXMLBuilder().processDocument(content, "application/xml", axis);
        axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
        return message;
    }

    private static void write(Axis2MessageContext message, String type, String mode, boolean atomic, boolean replace)
            throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/target");
        values.put("writeMode", mode);
        values.put("mimeType", type);
        values.put("atomicWrite", Boolean.toString(atomic));
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().execute(message, "result", replace);
    }
}
