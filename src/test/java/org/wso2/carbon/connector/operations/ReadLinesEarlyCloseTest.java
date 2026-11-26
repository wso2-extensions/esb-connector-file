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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.integration.connector.core.pool.CircuitBreaker;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * A read by lines stops before the end of a large file. When the server refuses the close of that transfer, as an FTP
 * server answers 426, the read still gives its lines, makes one attempt, counts nothing against the connection, and
 * the connection is not used again. A failure of the stream in the middle of the lines still fails the read.
 */
public class ReadLinesEarlyCloseTest {

    private static byte[] lines() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 5000; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @DataProvider(name = "closes")
    public Object[][] closes() {
        return new Object[][]{{false, true}, {true, true}, {false, false}, {true, false}};
    }

    @Test(dataProvider = "closes")
    public void earlyStopGivesTheLines(boolean replace, boolean closeFails) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), lines());
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            if (closeFails) {
                ReadContentErrorTest.hookFileReads(files, stream -> new FilterInputStream(stream) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        throw new IOException("426 Failure writing network stream");
                    }
                });
            }
            int before = pool.attempts.size();
            Axis2MessageContext message = message(files);
            read(message, replace, 1);
            Assert.assertEquals(text(message, replace), "line 1\nline 2\nline 3");
            Assert.assertEquals(pool.attempts.size() - before, 1, pool.attempts.toString());
            JsonObject attempt = pool.attempts.get(pool.attempts.size() - 1).getAsJsonObject();
            Assert.assertEquals(attempt.get("invalidates").getAsInt(), closeFails ? 1 : 0, attempt.toString());
            Assert.assertEquals(pool.getBreaker().getMode(), CircuitBreaker.Mode.CLOSED);
            // The next operation works, on a new connection when the close failed.
            WriteFileTest.write(WriteFileTest.message(files), "next", "x");
            JsonArray attempts = pool.attempts;
            String next = attempts.get(attempts.size() - 1).getAsJsonObject().get("handler").getAsString();
            Assert.assertEquals(!next.equals(attempt.get("handler").getAsString()), closeFails, attempts.toString());
        }
    }

    // The stream fails before the lines are read: the read fails as a fault of the connection, as before.
    @Test(dataProvider = "replace")
    public void streamFailureInTheMiddleOfTheLinesStillFails(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), lines());
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            ReadContentErrorTest.hookFileReads(files, stream -> new FilterInputStream(stream) {
                private int read;

                @Override
                public int read() throws IOException {
                    if (++read > 10) {
                        throw new IOException("The connection was lost in the middle of the read");
                    }
                    return super.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int next = read();
                    if (next < 0) {
                        return -1;
                    }
                    bytes[offset] = (byte) next;
                    return 1;
                }
            });
            Axis2MessageContext message = message(files);
            try {
                read(message, replace, 0);
                Assert.fail("The read of a lost stream passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            String result = replace ? JsonUtil.jsonPayloadToString(message.getAxis2MessageContext())
                    : String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload());
            Assert.assertEquals(JsonParser.parseString(result).getAsJsonObject().getAsJsonObject("error")
                    .get("code").getAsString(), "700101");
        }
    }

    @DataProvider(name = "replace")
    public Object[][] replace() {
        return new Object[][]{{false}, {true}};
    }

    private static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("text/plain", new PlainTextBuilder());
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(configuration));
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private static void read(Axis2MessageContext message, boolean replace, int retries) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("path", "/in");
        values.put("contentType", "text/plain");
        values.put("readMode", "Up To Line");
        values.put("endLineNum", "3");
        values.put("maxRetries", Integer.toString(retries));
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new ReadFile().execute(message, "content", replace);
    }

    private static String text(Axis2MessageContext message, boolean replace) throws Exception {
        if (replace) {
            return message.getAxis2MessageContext().getEnvelope().getBody().getFirstElement().getText();
        }
        return AXIOMUtil.stringToOM(String.valueOf(((ConnectorResponse) message.getVariable("content"))
                .getPayload())).getText();
    }
}
