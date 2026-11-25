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
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonStreamBuilder;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFile;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileSystem;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A file whose content the builder can not read fails that read alone, with an operation error, and the connection
 * goes on. A failure of the stream of the file is still a fault of the connection. The connection has the default
 * settings, where one fault of the connection suspends it.
 */
public class ReadContentErrorTest {
    private static final String OPERATION_ERROR = "700108";
    private static final String CONNECTION_ERROR = "700101";

    @DataProvider(name = "contents")
    public Object[][] contents() {
        byte[] badUtf8 = {'<', 'r', '>', 'c', 'a', 'f', (byte) 0xE9, '<', '/', 'r', '>'};
        byte[] nul = "caf\u00e9\u0000\n".getBytes(StandardCharsets.UTF_8);
        byte[] json16 = "{\"a\":1}".getBytes(StandardCharsets.UTF_16);
        byte[] xml = "<r>1</r>".getBytes(StandardCharsets.UTF_8);
        return new Object[][]{
                // A character that XML can not write, when the variable or the body is made.
                {"text with a NUL character", "text/plain", "UTF-8", nul, false},
                {"text with a NUL character", "text/plain", "UTF-8", nul, true},
                // A byte that is not valid for the charset of the file.
                {"XML with a byte that is not UTF-8", "application/xml", "UTF-8", badUtf8, false},
                {"XML with a byte that is not UTF-8", "application/xml", "UTF-8", badUtf8, true},
                // A charset that the JVM does not know.
                {"XML in a charset the JVM does not know", "application/xml", "x-no-such-charset", xml, false},
                {"XML in a charset the JVM does not know", "application/xml", "x-no-such-charset", xml, true},
                {"XML that breaks late", "application/xml", "UTF-8",
                        "<r><a>1</a><b></r>".getBytes(StandardCharsets.UTF_8), false},
                {"JSON in UTF-16", "application/json", "UTF-16", json16, false},
                // A JSON that ends early.
                {"JSON that ends early", "application/json", "UTF-8", "{\"a\":[1,2".getBytes(StandardCharsets.UTF_8),
                        false},
                {"JSON that ends early", "application/json", "UTF-8", "{\"a\":[1,2".getBytes(StandardCharsets.UTF_8),
                        true},
        };
    }

    @Test(dataProvider = "contents")
    public void contentErrorFailsTheReadAlone(String name, String type, String encoding, byte[] content,
                                              boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), content);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            Axis2MessageContext message = message(files);
            try {
                read(message, type, encoding, replace);
                Assert.fail("The read of " + name + " passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            Assert.assertEquals(code(message, replace), OPERATION_ERROR, name);
            // The operator reads the sentence, not a message code of the file system library.
            String detail = detail(message, replace);
            Assert.assertTrue(detail.startsWith("The file content could not be read as " + type), detail);
            Assert.assertFalse(detail.contains("Unknown message with code"), detail);
            // The next operation on the same connection is not refused.
            WriteFileTest.write(WriteFileTest.message(files), "next", "x");
            Assert.assertEquals(new String(Files.readAllBytes(directory.resolve("next")), StandardCharsets.UTF_8),
                    "x");
        }
    }

    @DataProvider(name = "modes")
    public Object[][] modes() {
        return new Object[][]{{false}, {true}};
    }

    // The stream of the file fails in the middle of the read, as a lost connection does.
    @Test(dataProvider = "modes")
    public void failureOfTheFileStreamIsStillAFaultOfTheConnection(boolean replace) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), "<r><a>1</a><a>2</a><a>3</a></r>".getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            breakFileReads(files);
            Axis2MessageContext message = message(files);
            try {
                read(message, "application/xml", "UTF-8", replace);
                Assert.fail("The read of a lost stream passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            Assert.assertEquals(code(message, replace), CONNECTION_ERROR);
            try {
                WriteFileTest.write(WriteFileTest.message(files), "next", "x");
                Assert.fail("The connection was not suspended");
            } catch (SynapseException suspended) {
                Assert.assertTrue(suspended.getMessage().contains("The connection is suspended"),
                        suspended.getMessage());
            }
        }
    }

    @DataProvider(name = "cut")
    public Object[][] cut() {
        return new Object[][]{
                {"application/xml", "<r><a>1</a><a>2</a><a>3</a></r>"},
                {"application/json", "{\"a\":[1,2,3],\"b\":\"text\"}"},
        };
    }

    // The file only looks broken: the read is a fault of the connection, and a second try on a new connection
    // reads it whole.
    @Test(dataProvider = "cut")
    public void cutStreamThatFailsAtTheCloseIsAFaultOfTheConnection(String type, String content) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), content.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            cutFileReads(files);
            Axis2MessageContext message = message(files);
            try {
                read(message, type, "UTF-8", false, 0);
                Assert.fail("The read of a cut stream passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            Assert.assertEquals(code(message, false), CONNECTION_ERROR);
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            cutFileReads(files);
            Axis2MessageContext message = message(files);
            read(message, type, "UTF-8", false, 1);
            Assert.assertTrue(String.valueOf(((ConnectorResponse) message.getVariable("content")).getPayload())
                    .contains("3"));
        }
    }

    // A builder that drops the cause of its failure: the failure of the stream still decides.
    @Test
    public void failureOfTheStreamDecidesWhenTheBuilderDropsIt() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), "0123456789".getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            breakFileReads(files);
            Axis2MessageContext message = message(files);
            try {
                read(message, "application/x-dropping", "UTF-8", false, 0);
                Assert.fail("The read of a lost stream passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            Assert.assertEquals(code(message, false), CONNECTION_ERROR);
        }
    }

    // The parser stops at a broken byte long before the end, and the close of the stream that was still sending
    // fails, as an FTP server answers a close before the end. The file is broken, so the read is a content error.
    @Test
    public void closeBeforeTheEndAfterABrokenByteIsContent() throws Exception {
        Path directory = WriteFileTest.directory();
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            rest.append("<a>").append(i).append("</a>");
        }
        byte[] head = {'<', 'r', '>', (byte) 0xE9};
        byte[] tail = (rest + "</r>").getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[head.length + tail.length];
        System.arraycopy(head, 0, content, 0, head.length);
        System.arraycopy(tail, 0, content, head.length, tail.length);
        Files.write(directory.resolve("in"), content);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            failFileCloses(files, () -> new IOException("426 Connection closed, transfer aborted"));
            int before = pool.attempts.size();
            Axis2MessageContext message = message(files);
            try {
                read(message, "application/xml", "UTF-8", false, 1);
                Assert.fail("The read of a byte that is not UTF-8 passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            Assert.assertEquals(code(message, false), OPERATION_ERROR);
            Assert.assertEquals(pool.attempts.size() - before, 1, pool.attempts.toString());
        }
    }

    // The stream ended and its close fails with a reply that is no fault of the connection: that reply is reported.
    // The JSON builder leaves the close to the read. The XML parser closes the stream itself at its end.
    @Test
    public void closeAfterTheEndReportsItsOwnFailure() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), "{\"a\":[1,2,3],\"b\":\"text\"}".getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            cutFileReads(files, () -> {
                FileSystemException refused = new FileSystemException("550 Requested action not taken");
                refused.setFault(Fault.APPLICATION);
                return refused;
            });
            Axis2MessageContext message = message(files);
            try {
                read(message, "application/json", "UTF-8", false, 0);
                Assert.fail("The read of a cut stream passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            JsonObject error = JsonParser.parseString(String.valueOf(((ConnectorResponse) message
                    .getVariable("content")).getPayload())).getAsJsonObject().getAsJsonObject("error");
            Assert.assertTrue(error.get("detail").getAsString().contains("550 Requested action not taken"),
                    error.toString());
            Assert.assertFalse(error.get("detail").getAsString().contains("The file content could not be read"),
                    error.toString());
        }
    }

    // A content error is not tried again on a new connection.
    @Test
    public void contentErrorIsNotTriedAgain() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in"), new byte[]{'<', 'r', '>', (byte) 0xE9, '<', '/', 'r', '>'});
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Axis2MessageContext message = message(files);
            try {
                read(message, "application/xml", "UTF-8", false, 1);
                Assert.fail("The read of a byte that is not UTF-8 passed");
            } catch (SynapseException expected) {
                // The read fails.
            }
            Assert.assertEquals(code(message, false), OPERATION_ERROR);
            Assert.assertEquals(pool.attempts.size(), 1, pool.attempts.toString());
        }
    }

    private static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        AxisConfiguration configuration = new AxisConfiguration();
        configuration.addMessageBuilder("application/xml", new ApplicationXMLBuilder());
        configuration.addMessageBuilder("text/plain", new PlainTextBuilder());
        configuration.addMessageBuilder("application/json", new JsonStreamBuilder());
        // Reads the whole file and drops the cause when the read fails, as some builders do.
        configuration.addMessageBuilder("application/x-dropping", (in, type, context) -> {
            try {
                while (in.read() >= 0) {
                    continue;
                }
            } catch (IOException dropped) {
                throw new AxisFault("The builder could not read the file");
            }
            throw new AxisFault("The builder found nothing");
        });
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(configuration));
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    private static void read(Axis2MessageContext message, String type, String encoding, boolean replace)
            throws Exception {
        read(message, type, encoding, replace, 0);
    }

    private static void read(Axis2MessageContext message, String type, String encoding, boolean replace,
                             int retries) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("path", "/in");
        values.put("contentType", type);
        values.put("encoding", encoding);
        // With no second try on a new connection, a failure of the stream reaches the breaker.
        values.put("maxRetries", Integer.toString(retries));
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new ReadFile().execute(message, "content", replace);
    }

    private static String detail(Axis2MessageContext message, boolean replace) {
        JsonObject result = replace
                ? JsonParser.parseString(JsonUtil.jsonPayloadToString(message.getAxis2MessageContext()))
                .getAsJsonObject()
                : JsonParser.parseString(String.valueOf(((ConnectorResponse) message.getVariable("content"))
                .getPayload())).getAsJsonObject();
        return result.getAsJsonObject("error").get("detail").getAsString();
    }

    // The error code of the result: in the variable, or in place of the body.
    private static String code(Axis2MessageContext message, boolean replace) {
        JsonObject result = replace
                ? JsonParser.parseString(JsonUtil.jsonPayloadToString(message.getAxis2MessageContext()))
                .getAsJsonObject()
                : JsonParser.parseString(String.valueOf(((ConnectorResponse) message.getVariable("content"))
                .getPayload())).getAsJsonObject();
        return result.getAsJsonObject("error").get("code").getAsString();
    }

    // The stream of the file "in" fails after its first bytes. The read meets the file through the cache of the
    // file system, so the failure is put into the local file that the provider makes.
    private static void breakFileReads(FilePoolFixture files) throws Exception {
        hookFileReads(files, stream -> new FilterInputStream(stream) {
            private int read;

            @Override
            public int read() throws IOException {
                if (++read > 5) {
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
    }

    // The stream of the file ends cleanly after its first bytes, and its close fails, as the final reply of a cut
    // FTP transfer does.
    private static void cutFileReads(FilePoolFixture files) throws Exception {
        cutFileReads(files, () -> new IOException("426 Connection closed, transfer aborted"));
    }

    private static void cutFileReads(FilePoolFixture files, Supplier<IOException> close) throws Exception {
        hookFileReads(files, stream -> new FilterInputStream(stream) {
            private int read;

            @Override
            public int read() throws IOException {
                return ++read > 5 ? -1 : super.read();
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

            @Override
            public void close() throws IOException {
                super.close();
                throw close.get();
            }
        });
    }

    // The stream of the file reads whole, and its close fails.
    private static void failFileCloses(FilePoolFixture files, Supplier<IOException> close) throws Exception {
        hookFileReads(files, stream -> new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
                super.close();
                throw close.get();
            }
        });
    }

    static void hookFileReads(FilePoolFixture files, UnaryOperator<InputStream> hook) throws Exception {
        ConnectionLease lease = files.borrow();
        DefaultFileSystemManager manager =
                (DefaultFileSystemManager) ((FileSystemHandler) lease.getConnection()).getFsManager();
        manager.removeProvider("file");
        manager.addProvider("file", new DefaultLocalFileProvider() {
            @Override
            protected FileSystem doCreateFileSystem(FileName name, FileSystemOptions options) {
                String root = ((LocalFileName) name).getRootFile();
                return new LocalFileSystem(name, root, options) {
                    @Override
                    protected FileObject createFile(AbstractFileName file) {
                        return new LocalFile(this, root, file) {
                            @Override
                            protected InputStream doGetInputStream(int bufferSize) throws Exception {
                                InputStream stream = super.doGetInputStream(bufferSize);
                                return "in".equals(file.getBaseName()) ? hook.apply(stream) : stream;
                            }
                        };
                    }
                };
            }
        });
        lease.returnToPool();
    }
}
