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

import com.google.gson.JsonElement;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.xml.namespace.QName;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WriteFileTest {
    static Axis2MessageContext message(FilePoolFixture files) throws Exception {
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(new AxisConfiguration()));
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    static void write(Axis2MessageContext message, String path, String content, String... options) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("filePath", "/" + path);
        values.put("contentOrExpression", content);
        for (int i = 0; i < options.length; i += 2) {
            values.put(options[i], options[i + 1]);
        }
        TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new WriteFile().execute(message, "result", false);
    }

    static Path directory() throws Exception {
        Path root = java.nio.file.Paths.get("target", "write-tests").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "write");
    }

    @Test
    public void createAppendInsertAndSourceTime() throws Exception {
        Path dir = directory();
        Path file = dir.resolve("report");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files);
            message.getAxis2MessageContext().setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS,
                    Collections.singletonMap("LAST_MODIFIED", "1680000000000"));
            write(message, "report", "first\r\nlast", "writeMode", "Create New");
            Assert.assertEquals(Files.getLastModifiedTime(file).toMillis(), 1680000000000L);
            try {
                write(message, "report", "duplicate", "writeMode", "Create New");
                Assert.fail("An existing name must refuse publication");
            } catch (SynapseException expected) {
                Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), "700103");
            }
            write(message, "report", "é", "writeMode", "Append", "appendPosition", "1",
                    "lastModified", "1690000000000");
            Assert.assertEquals(Files.getLastModifiedTime(file).toMillis(), 1690000000000L);
            write(message, "report", " tail", "writeMode", "Append", "appendNewLine", "true");
            Assert.assertEquals(Files.readAllBytes(file), "firsté\r\nlast tail\n".getBytes(StandardCharsets.UTF_8));
            write(message, "missing", "new", "writeMode", "Append", "encoding", "UTF-16LE");
            Assert.assertEquals(Files.readAllBytes(dir.resolve("missing")), "new".getBytes(StandardCharsets.UTF_16LE));
            write(message, "report", "in place", "atomicWrite", "false", "enableLock", "false",
                    "updateLastModified", "false");
            Assert.assertEquals(Files.readAllBytes(file), "in place".getBytes(StandardCharsets.UTF_8));
            Assert.assertNotEquals(Files.getLastModifiedTime(file).toMillis(), 1680000000000L);
        }
    }

    @Test
    public void wholeWriteCreatesMissingParentsWithEitherLockSetting() throws Exception {
        Path dir = directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files);
            for (String locked : new String[]{"true", "false"}) {
                for (boolean parentExists : new boolean[]{true, false}) {
                    for (String kind : new String[]{"Append", "Position", "Create New", "Overwrite"}) {
                        String path = locked + "/" + parentExists + "/" + kind + "/nested/value";
                        if (parentExists) {
                            Files.createDirectories(dir.resolve(path).getParent());
                        }
                        String mode = kind.equals("Position") ? "Append" : kind;
                        write(message, path, "A", "writeMode", mode, "enableLock", locked, "atomicWrite", "false",
                                "appendPosition", kind.equals("Position") ? "1" : "");
                        write(message, path, "B", "writeMode", "Append", "enableLock", locked);
                        Assert.assertEquals(Files.readString(dir.resolve(path)), "AB", path);
                        Assert.assertFalse(Files.exists(dir.resolve(path + ".lock")), path);
                    }
                }
            }
            write(message, "default/nested/missing", "default", "writeMode", "Append");
            Assert.assertEquals(Files.readString(dir.resolve("default/nested/missing")), "default");
        }
    }

    @Test
    public void textAndSeparatorAreOneEncodedAppendPayload() throws Exception {
        Path dir = directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files);
            for (String mode : new String[]{"Overwrite", "Create New", "Append"}) {
                for (String encoding : new String[]{"UTF-16", "UTF-16LE", "UTF-8"}) {
                    for (boolean newline : new boolean[]{true, false}) {
                        String path = mode + encoding + newline;
                        java.nio.charset.Charset charset = java.nio.charset.Charset.forName(encoding);
                        String separator = newline ? System.lineSeparator() : "";
                        byte[] first = ("A" + separator).getBytes(charset);
                        write(message, path, "A", "encoding", encoding, "writeMode", mode,
                                "appendNewLine", Boolean.toString(newline));
                        Assert.assertEquals(Files.readAllBytes(dir.resolve(path)), first, path);
                        write(message, path, "B", "encoding", encoding, "writeMode", "Append",
                                "appendNewLine", Boolean.toString(newline));
                        byte[] second = ("B" + separator).getBytes(charset);
                        byte[] expected = java.util.Arrays.copyOf(first, first.length + second.length);
                        System.arraycopy(second, 0, expected, first.length, second.length);
                        Assert.assertEquals(Files.readAllBytes(dir.resolve(path)), expected, path);
                    }
                }
            }
            // With no expression text the connector uses the message body, even for an empty text body.
            org.apache.axiom.om.OMElement text = OMAbstractFactory.getOMFactory().createOMElement(
                    org.apache.axis2.transport.base.BaseConstants.DEFAULT_TEXT_WRAPPER);
            message.getEnvelope().getBody().addChild(text);
            write(message, "empty", "", "encoding", "UTF-16", "appendNewLine", "true");
            Assert.assertEquals(Files.readAllBytes(dir.resolve("empty")), System.lineSeparator().getBytes("UTF-16"));
        }
    }

    @Test
    public void appliesExactRequestedModeToThePublishedLocalFile() throws Exception {
        Path dir = directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files);
            write(message, "mode", "first", "updateFilePermission", "0641");
            Assert.assertEquals((Integer) Files.getAttribute(dir.resolve("mode"), "unix:mode") & 07777, 0641);
            write(message, "mode", "second", "updateFilePermission", "0604");
            Assert.assertEquals((Integer) Files.getAttribute(dir.resolve("mode"), "unix:mode") & 07777, 0604);
            write(message, "mode", "append", "writeMode", "Append");
            Assert.assertEquals((Integer) Files.getAttribute(dir.resolve("mode"), "unix:mode") & 07777, 0604);
        }
    }

    @Test
    public void readerSeesWholePublicationsAndConcurrentAppendsStayWhole() throws Exception {
        Path dir = directory();
        Path file = dir.resolve("current");
        ExecutorService workers = Executors.newFixedThreadPool(3);
        AtomicBoolean running = new AtomicBoolean(true);
        String a = block('a'), b = block('b');
        byte[] first = a.getBytes(StandardCharsets.UTF_8), second = b.getBytes(StandardCharsets.UTF_8);
        Files.write(file, first);
        try (FilePoolFixture left = new FilePoolFixture("LOCAL", "workingDir", dir.toString());
             FilePoolFixture right = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            CountDownLatch sawOld = new CountDownLatch(1);
            CountDownLatch sawNew = new CountDownLatch(1);
            Future<Integer> reader = workers.submit(() -> {
                int reads = 0;
                while (running.get()) {
                    byte[] seen = Files.readAllBytes(file);
                    if (Arrays.equals(seen, first)) {
                        sawOld.countDown();
                    } else {
                        Assert.assertTrue(Arrays.equals(seen, second), "Reader saw a partial generation");
                        sawNew.countDown();
                    }
                    reads++;
                }
                return reads;
            });
            Assert.assertTrue(sawOld.await(10, TimeUnit.SECONDS), "Reader did not observe the initial generation");
            Axis2MessageContext message = message(left);
            for (int i = 0; i < 8; i++) {
                write(message, "current", i % 2 == 0 ? b : a);
                if (i == 0) {
                    Assert.assertTrue(sawNew.await(10, TimeUnit.SECONDS), "Reader did not observe the new generation");
                }
            }
            running.set(false);
            Assert.assertTrue(reader.get(10, TimeUnit.SECONDS) >= 2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Void> one = workers.submit(() -> append(left, a, start));
            Future<Void> two = workers.submit(() -> append(right, b, start));
            start.countDown();
            one.get(60, TimeUnit.SECONDS);
            two.get(60, TimeUnit.SECONDS);
            java.util.List<String> lines = Files.readAllLines(dir.resolve("appended"), StandardCharsets.UTF_8);
            Assert.assertEquals(lines.size(), 8);
            Assert.assertEquals(Collections.frequency(lines, a), 4);
            Assert.assertEquals(Collections.frequency(lines, b), 4);
        } finally {
            running.set(false);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void brokenBodySourceLeavesTheTargetConnectionHealthy() throws Exception {
        Path dir = directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Axis2MessageContext message = message(files);
            DataSource source = new DataSource() {
                public InputStream getInputStream() {
                    return new InputStream() {
                        int sent;

                        public int read() throws IOException {
                            if (sent++ < 4) {
                                return 'a';
                            }
                            throw new IOException("The connection of the body source was lost");
                        }
                    };
                }

                public OutputStream getOutputStream() {
                    throw new UnsupportedOperationException();
                }

                public String getContentType() {
                    return "application/octet-stream";
                }

                public String getName() {
                    return "body";
                }
            };
            SOAPEnvelope envelope = message.getAxis2MessageContext().getEnvelope();
            OMElement wrapper = envelope.getOMFactory().createOMElement(BaseConstants.DEFAULT_BINARY_WRAPPER);
            wrapper.addChild(envelope.getOMFactory().createOMText(new DataHandler(source), true));
            envelope.getBody().addChild(wrapper);
            TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
            template.setMappedValues(Collections.singletonMap("filePath", "/body"));
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            try {
                new WriteFile().execute(message, "result", false);
                Assert.fail("The body source failed");
            } catch (SynapseException expected) {
                Assert.assertEquals(pool.getBreaker().getMode(), CircuitBreaker.Mode.CLOSED);
            }
            for (JsonElement loan : pool.attempts) {
                Assert.assertEquals(loan.getAsJsonObject().get("invalidates").getAsInt(), 0, loan.toString());
            }
            Assert.assertFalse(Files.exists(dir.resolve("body")));
        }
    }

    @Test
    public void failedCloseOfTheTargetIsATransportError() throws Exception {
        Path dir = directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString(),
                "maxActiveConnections", "1")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            ConnectionLease lease = files.borrow();
            DefaultFileSystemManager manager =
                    (DefaultFileSystemManager) ((FileSystemHandler) lease.getConnection()).getFsManager();
            manager.removeProvider("file");
            manager.addProvider("file", new DefaultLocalFileProvider() {
                @Override
                public FileObject findFile(FileObject base, String uri, FileSystemOptions options)
                        throws FileSystemException {
                    FileObject real = super.findFile(base, uri, options);
                    if (!uri.contains("/.part/closed.")) {
                        return real;
                    }
                    FileContent content = real.getContent();
                    FileContent closing = (FileContent) Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{FileContent.class}, (proxy, method, args) -> {
                                Object value;
                                try {
                                    value = method.invoke(content, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                                if (!"getOutputStream".equals(method.getName())) {
                                    return value;
                                }
                                // Only the first close fails. A second close of a closed stream does nothing.
                                return new FilterOutputStream((OutputStream) value) {
                                    private boolean failed;

                                    @Override
                                    public void close() throws IOException {
                                        super.close();
                                        if (!failed) {
                                            failed = true;
                                            throw new IOException("The connection was lost at the close");
                                        }
                                    }
                                };
                            });
                    return new DecoratedFileObject(real) {
                        @Override
                        public FileContent getContent() {
                            return closing;
                        }
                    };
                }
            });
            lease.returnToPool();
            Axis2MessageContext message = message(files);
            message.getAxis2MessageContext().getConfigurationContext().getAxisConfiguration().addMessageFormatter(
                    "application/x-closing", new ApplicationXMLFormatter() {
                        @Override
                        public void writeTo(MessageContext context, OMOutputFormat format, OutputStream out,
                                            boolean preserve) throws AxisFault {
                            super.writeTo(context, format, out, preserve);
                            try {
                                out.close();
                            } catch (IOException failure) {
                                throw AxisFault.makeFault(failure);
                            }
                        }
                    });
            SOAPEnvelope envelope = message.getAxis2MessageContext().getEnvelope();
            OMElement data = envelope.getOMFactory().createOMElement(new QName("data"));
            data.setText("body");
            envelope.getBody().addChild(data);
            Map<String, String> values = new HashMap<>();
            values.put("filePath", "/closed");
            values.put("mimeType", "application/x-closing");
            TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
            template.setMappedValues(values);
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            try {
                new WriteFile().execute(message, "result", false);
                Assert.fail("The close of the target failed");
            } catch (SynapseException expected) {
                Assert.assertEquals(pool.getBreaker().getMode(), CircuitBreaker.Mode.OPEN);
            }
            int invalidated = 0;
            for (JsonElement loan : pool.attempts) {
                invalidated += loan.getAsJsonObject().get("invalidates").getAsInt();
            }
            Assert.assertEquals(invalidated, 1);
        }
    }

    private static Void append(FilePoolFixture files, String bytes, CountDownLatch start) throws Exception {
        Axis2MessageContext message = message(files);
        start.await();
        for (int i = 0; i < 4; i++) {
            write(message, "appended", bytes, "writeMode", "Append", "appendNewLine", "true");
        }
        return null;
    }

    private static String block(char value) {
        char[] data = new char[65536];
        Arrays.fill(data, value);
        return new String(data);
    }
}
