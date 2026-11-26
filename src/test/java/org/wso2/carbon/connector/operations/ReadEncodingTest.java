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
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

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

/**
 * The encoding of a read decodes a text file when its content type names no charset, and decodes and hands on the
 * lines of the line modes. A charset in the content type still decides for a text file. A JSON file is read as UTF-8,
 * as the JSON builder of synapse does, whatever the encoding says.
 */
public class ReadEncodingTest {
    private static final String TEXT = "line one caf\u00e9 \u00fc\nline two\n";
    private static final String LINES = "l1 caf\u00e9\nl2 \u00fc\nl3 caf\u00e9\nl4 end\n";
    private static final String XML = "<r><item>caf\u00e9 \u00fc</item></r>";

    @DataProvider(name = "encodings")
    public Object[][] encodings() {
        List<Object[]> cases = new ArrayList<>();
        for (String encoding : new String[]{"ISO-8859-1", "UTF-16"}) {
            for (boolean replace : new boolean[]{false, true}) {
                cases.add(new Object[]{encoding, replace});
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "encodings")
    public void textReadTakesTheEncoding(String encoding, boolean replace) throws Exception {
        Axis2MessageContext message = read(TEXT.getBytes(Charset.forName(encoding)), "text/plain", encoding, replace,
                null);
        Assert.assertEquals(text(message, replace), TEXT);
    }

    @Test(dataProvider = "encodings")
    public void charsetOfTheContentTypeStillDecides(String encoding, boolean replace) throws Exception {
        // The file is in ISO-8859-1 as the content type says. The encoding parameter names another charset.
        Axis2MessageContext message = read(TEXT.getBytes(StandardCharsets.ISO_8859_1),
                "text/plain; charset=ISO-8859-1", encoding.equals("UTF-16") ? "UTF-16" : "UTF-8", replace, null);
        Assert.assertEquals(text(message, replace), TEXT);
    }

    @Test(dataProvider = "encodings")
    public void xmlReadTakesTheEncoding(String encoding, boolean replace) throws Exception {
        byte[] content = ("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>" + XML)
                .getBytes(Charset.forName(encoding));
        Axis2MessageContext message = read(content, "application/xml", encoding, replace, null);
        String read = replace ? message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().toString()
                : String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload());
        Assert.assertTrue(read.contains(XML), read);
    }

    @Test
    public void textReadWithNoEncodingIsUtf8AsBefore() throws Exception {
        Axis2MessageContext message = read(TEXT.getBytes(StandardCharsets.UTF_8), "text/plain", null, true, null);
        Assert.assertEquals(text(message, true), TEXT);
    }

    // The JSON builder of synapse reads the bytes as UTF-8, whatever the encoding parameter says. This pins it.
    @Test
    public void jsonIsReadAsUtf8WhateverTheEncoding() throws Exception {
        Axis2MessageContext latin = read("{\"n\":\"caf\u00e9\"}".getBytes(StandardCharsets.ISO_8859_1),
                "application/json", "ISO-8859-1", false, null);
        JsonObject value = JsonParser.parseString(String.valueOf(((ConnectorResponse) latin.getVariable("content"))
                .getPayload())).getAsJsonObject();
        Assert.assertEquals(value.get("n").getAsString(), "caf\ufffd");
        try {
            read("{\"n\":\"caf\u00e9\"}".getBytes(StandardCharsets.UTF_16), "application/json", "UTF-16", false, null);
            Assert.fail("A JSON file in UTF-16 was read");
        } catch (SynapseException expected) {
            // The JSON builder can not read UTF-16.
        }
    }

    @DataProvider(name = "lines")
    public Object[][] lines() {
        List<Object[]> cases = new ArrayList<>();
        Object[][] modes = {
                {"Starting From Line", "3", null, null, "l3 caf\u00e9\nl4 end"},
                {"Up To Line", null, "2", null, "l1 caf\u00e9\nl2 \u00fc"},
                {"Between Lines", "2", "3", null, "l2 \u00fc\nl3 caf\u00e9"},
                {"Specific Line", null, null, "3", "l3 caf\u00e9"},
        };
        for (Object[] mode : modes) {
            for (String encoding : new String[]{"ISO-8859-1", "UTF-16"}) {
                for (boolean replace : new boolean[]{false, true}) {
                    cases.add(new Object[]{mode[0], mode[1], mode[2], mode[3], mode[4], encoding, replace});
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "lines")
    public void lineReadTakesTheEncoding(String mode, String start, String end, String line, String expected,
                                         String encoding, boolean replace) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("readMode", mode);
        if (start != null) {
            options.put("startLineNum", start);
        }
        if (end != null) {
            options.put("endLineNum", end);
        }
        if (line != null) {
            options.put("lineNum", line);
        }
        Axis2MessageContext message = read(LINES.getBytes(Charset.forName(encoding)), "text/plain", encoding,
                replace, options);
        Assert.assertEquals(text(message, replace), expected);
    }

    // A charset in the content type decides the lines too, before the encoding parameter.
    @DataProvider(name = "typed")
    public Object[][] typed() {
        return new Object[][]{{null}, {"UTF-8"}};
    }

    @Test(dataProvider = "typed")
    public void lineReadTakesTheCharsetOfTheContentType(String encoding) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("readMode", "Up To Line");
        options.put("endLineNum", "2");
        Axis2MessageContext message = read(LINES.getBytes(StandardCharsets.ISO_8859_1),
                "text/plain; charset=ISO-8859-1", encoding, true, options);
        Assert.assertEquals(text(message, true), "l1 caf\u00e9\nl2 \u00fc");
    }

    // An encoding the JVM does not know fails that read alone, on each path, with one attempt.
    @DataProvider(name = "unknown")
    public Object[][] unknown() {
        return new Object[][]{{"complete", false}, {"lines", false}, {"streamed", true}};
    }

    @Test(dataProvider = "unknown")
    public void unknownEncodingFailsTheReadAlone(String path, boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), TEXT.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Map<String, String> options = new HashMap<>();
            options.put("maxRetries", "1");
            if ("lines".equals(path)) {
                options.put("readMode", "Up To Line");
                options.put("endLineNum", "1");
            } else if ("streamed".equals(path)) {
                options.put("enableStreaming", "true");
            }
            int before = pool.attempts.size();
            Axis2MessageContext message = message(files);
            try {
                execute(message, "text/plain", "x-no-such-charset", replace, options);
                if ("streamed".equals(path)) {
                    // The streamed builder decodes later, when a step reads the body.
                    message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().getText();
                }
                Assert.fail("A read in an unknown encoding passed");
            } catch (SynapseException | IllegalArgumentException | OMException expected) {
                // The read fails.
            }
            Assert.assertEquals(pool.attempts.size() - before, 1, pool.attempts.toString());
            if (!"streamed".equals(path) || message.getAxis2MessageContext().getProperty("ERROR_CODE") != null) {
                Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), "700108");
            }
        }
    }

    @Test
    public void streamedTextReadTakesTheEncoding() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), TEXT.getBytes(StandardCharsets.ISO_8859_1));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Map<String, String> options = new HashMap<>();
            options.put("enableStreaming", "true");
            Axis2MessageContext message = message(files);
            execute(message, "text/plain", "ISO-8859-1", true, options);
            // The streamed body is read from the file while the connection lives.
            Assert.assertEquals(text(message, true), TEXT);
        }
    }

    // A byte order mark: the UTF-16 decoder takes it away, the UTF-8 decoder of the JVM keeps it as a character.
    @Test
    public void byteOrderMarkOfTheFile() throws Exception {
        byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[utf8.length + 3];
        marked[0] = (byte) 0xEF;
        marked[1] = (byte) 0xBB;
        marked[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, marked, 3, utf8.length);
        Assert.assertEquals(text(read(marked, "text/plain", "UTF-8", true, null), true), "\ufeff" + TEXT);
        Assert.assertEquals(text(read(TEXT.getBytes(StandardCharsets.UTF_16), "text/plain", "UTF-16", true, null), true),
                TEXT);
    }

    private static Axis2MessageContext read(byte[] content, String type, String encoding, boolean replace,
                                            Map<String, String> options) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), content);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            execute(message, type, encoding, replace, options);
            return message;
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("application/xml", new ApplicationXMLBuilder());
        configuration.addMessageBuilder("text/plain", new PlainTextBuilder());
        configuration.addMessageBuilder("application/json", new JsonStreamBuilder());
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(configuration));
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private static void execute(Axis2MessageContext message, String type, String encoding, boolean replace,
                                Map<String, String> options) throws Exception {
        {
            Map<String, String> values = new HashMap<>();
            values.put("path", "/in");
            values.put("contentType", type);
            if (encoding != null) {
                values.put("encoding", encoding);
            }
            if (options != null) {
                values.putAll(options);
            }
            TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
            template.setMappedValues(values);
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            new ReadFile().execute(message, "content", replace);
        }
    }

    // The text a read gave: the text of the body that replaced the old one, or the text inside the variable.
    private static String text(Axis2MessageContext message, boolean replace) throws Exception {
        if (replace) {
            return message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().getText();
        }
        String wrapper = String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload());
        return AXIOMUtil.stringToOM(wrapper).getText();
    }
}
