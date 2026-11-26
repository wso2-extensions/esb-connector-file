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
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.format.BinaryBuilder;
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.testng.Assert;
import org.wso2.integration.connector.core.connection.PoolBusyException;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;

import javax.activation.DataSource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicReference;

public class ReadFileTest {
    private static final String TYPE = "application/octet-stream";
    private static final byte[] PAYLOAD = {10, 20, 30};

    Axis2MessageContext message(FilePoolFixture files, Builder builder) throws Exception {
        AxisConfiguration config = new AxisConfiguration();
        config.addMessageBuilder(TYPE, builder);
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setConfigurationContext(new ConfigurationContext(config));
        axis.setProperty(PassThroughConstants.NO_ENTITY_BODY, true);
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        message.setProperty("name", files.name);
        return message;
    }

    void read(Axis2MessageContext message, String path, boolean lock, boolean stream, String... options)
            throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("path", path);
        values.put("contentType", TYPE);
        values.put("enableLock", Boolean.toString(lock));
        values.put("enableStreaming", Boolean.toString(stream));
        for (int index = 0; index < options.length; index += 2) {
            values.put(options[index], options[index + 1]);
        }
        TemplateContext template = new TemplateContext("file.read", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        new ReadFile().execute(message, "result", true);
    }

    @DataProvider
    public Object[][] modes() {
        return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}};
    }

    private void readPayload(StreamLease lease, byte[] expected) throws IOException {
        try (InputStream in = lease.getInputStream()) {
            for (byte b : expected) {
                Assert.assertEquals(in.read(), b & 255);
            }
            Assert.assertEquals(in.read(), -1);
        }
    }

    @Test(dataProvider = "modes")
    public void aliasesShareTheSelectedProviderFile(boolean streamed, boolean folderFirst) throws Exception {
        String[][] names = {{"space name", "space%20name"}, {"bracket[2]", "bracket%5B2%5D"},
                {"invoice", "./invoice"}, {"doc%5B2%5D", "doc%255B2%255D"},
                {"line\nfile", "line%0Afile"}};
        for (String[] name : names) {
            Path dir = Files.createTempDirectory("file-read-name");
            Path source = Files.write(dir.resolve(name[0]), PAYLOAD);
            Path lock = dir.resolve(name[0] + ".lock");
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
                FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
                Axis2MessageContext message = message(files, new BinaryBuilder());
                String direct = "/" + name[1];
                String lookup = files.factory.getContext().getBaseUri() + direct;
                read(message, folderFirst ? "/" : direct, true, streamed);
                StreamLeases holder = StreamLeases.find(message);
                StreamLease before = holder.get(files.name, lookup);
                Assert.assertNotNull(before, name[0]);
                if (streamed) {
                    readPayload(before, PAYLOAD);
                    readPayload(before, PAYLOAD);
                }
                Assert.assertNull(holder.get(files.name, lookup + ".sibling"));
                Assert.assertNull(holder.get(files.name + ".other", lookup));
                Assert.assertTrue(Files.exists(lock), name[0]);
                read(message, folderFirst ? direct : "/", true, !streamed);
                Assert.assertEquals(before.reason(), StreamLease.Reason.MOVED, name[0]);
                StreamLease after = holder.get(files.name, lookup);
                Assert.assertNotSame(before, after);
                if (!streamed) {
                    readPayload(after, PAYLOAD);
                    readPayload(after, PAYLOAD);
                }
                Assert.assertTrue(Files.exists(lock));
                Assert.assertEquals(pool.attempts.size(), 3);
                Assert.assertEquals(pool.getNumActive(), 0);
            } finally {
                Files.deleteIfExists(lock);
                Files.delete(source);
                Files.delete(dir);
            }
        }
    }

    @Test
    public void onlyExplicitPatternsRestrictLineTerminators() throws Exception {
        Path dir = Files.createTempDirectory("file-read-pattern");
        Path file = Files.write(dir.resolve("line\nfile"), PAYLOAD);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files, new BinaryBuilder());
            read(message, "/line%0Afile", false, false);
            try {
                read(message, "/line%0Afile", false, false, "filePattern", ".*");
                Assert.fail("Explicit Java dot pattern accepted a line terminator");
            } catch (SynapseException expected) {
                Assert.assertEquals(message.getProperty("ERROR_CODE"), "700108");
            }
            read(message, "/line%0Afile", false, false, "filePattern", "(?s).*");
        } finally {
            Files.delete(file);
            Files.delete(dir);
        }
    }

    @Test
    public void directReadersKeepSeparateConnectionsAndTheOriginalOperand() throws Exception {
        Path dir = Files.createTempDirectory("file-read-connections");
        Path file = Files.write(dir.resolve("question?mark"), PAYLOAD);
        try (FilePoolFixture one = new FilePoolFixture("LOCAL", "workingDir", dir.toString());
             FilePoolFixture two = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(one, new BinaryBuilder());
            String path = "/question%3Fmark";
            String lookup = one.factory.getContext().getBaseUri() + path;
            read(message, path, false, true);
            StreamLeases holder = StreamLeases.find(message);
            StreamLease first = holder.get(one.name, lookup);
            readPayload(first, PAYLOAD);
            message.setProperty("name", two.name);
            read(message, path, false, true);
            StreamLease other = holder.get(two.name, lookup);
            Assert.assertNotSame(other, first);
            readPayload(other, PAYLOAD);
            readPayload(first, PAYLOAD);
            Assert.assertSame(holder.get(one.name, lookup), first);
        } finally {
            Files.delete(file);
            Files.delete(dir);
        }
    }

    @Test
    public void distinctPercentFileHasItsOwnReadRecord() throws Exception {
        Path dir = Files.createTempDirectory("file-read-percent");
        Path brackets = Files.write(dir.resolve("doc[2]"), new byte[] {35});
        Path percent = Files.write(dir.resolve("doc%5B2%5D"), new byte[] {37});
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files, new BinaryBuilder());
            String base = files.factory.getContext().getBaseUri();
            read(message, "/doc%5B2%5D", true, true);
            StreamLeases holder = StreamLeases.find(message);
            StreamLease first = holder.get(files.name, base + "/doc[2]");
            readPayload(first, new byte[] {35});
            read(message, "/doc%255B2%255D", true, true);
            StreamLease second = holder.get(files.name, base + "/doc%255B2%255D");
            Assert.assertNotSame(first, second);
            Assert.assertEquals(first.state(), StreamLease.State.REPLAY);
            readPayload(second, new byte[] {37});
            readPayload(second, new byte[] {37});
            Assert.assertTrue(Files.exists(dir.resolve("doc[2].lock")));
            Assert.assertTrue(Files.exists(dir.resolve("doc%5B2%5D.lock")));
        } finally {
            Files.deleteIfExists(dir.resolve("doc[2].lock"));
            Files.deleteIfExists(dir.resolve("doc%5B2%5D.lock"));
            Files.delete(brackets);
            Files.delete(percent);
            Files.delete(dir);
        }
    }

    @Test(dataProvider = "modes")
    public void folderReadOwnsOnlyTheSelectedFile(boolean stream, boolean lock) throws Exception {
        Path dir = Files.createTempDirectory("file-read");
        Path member = Files.write(dir.resolve("order.part"), PAYLOAD);
        Path marker = Files.write(dir.resolve("other.fail"), PAYLOAD);
        Path part = Files.write(dir.resolve("order.0t9k2m17f3k9q2.part"), PAYLOAD);
        Path memberLock = dir.resolve("order.part.lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Axis2MessageContext message = message(files, new BinaryBuilder());
            read(message, "/", lock, stream);
            Assert.assertNull(message.getAxis2MessageContext().getProperty(PassThroughConstants.NO_ENTITY_BODY));
            String root = files.factory.getContext().getBaseUri() + "/";
            StreamLeases holder = StreamLeases.find(message);
            StreamLease lease = holder == null ? null : holder.get(files.name, root + "order.part");
            Assert.assertEquals(Files.exists(memberLock), lock);
            Assert.assertEquals(pool.attempts.size(), 1);
            Assert.assertEquals(pool.getNumActive(), stream ? 1 : 0);
            if (holder != null) {
                Assert.assertNull(holder.get(files.name, root));
                Assert.assertNull(holder.get(files.name, root + "other.fail"));
            }
            if (stream) {
                Assert.assertEquals(lease.state(), StreamLease.State.ACTIVE);
                try (InputStream in = lease.getInputStream()) {
                    Assert.assertEquals(in.read(), 10);
                    Assert.assertEquals(in.read(), 20);
                    Assert.assertEquals(in.read(), 30);
                    Assert.assertEquals(in.read(), -1);
                }
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
                Assert.assertEquals(pool.getNumActive(), 0);
                try (InputStream in = lease.getInputStream()) {
                    Assert.assertEquals(in.read(), 10);
                }
            } else if (lock) {
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
                try {
                    lease.getInputStream();
                    Assert.fail("A built delivery opened the source again");
                } catch (IOException expected) {
                    Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
                }
            } else {
                Assert.assertNull(lease);
            }
            if (lock) {
                read(message, "/order.part", true, false);
                Assert.assertEquals(lease.reason(), StreamLease.Reason.MOVED);
                StreamLease next = holder.get(files.name, root + "order.part");
                Assert.assertNotSame(next, lease);
                Assert.assertEquals(next.state(), StreamLease.State.REPLAY);
                Assert.assertTrue(Files.exists(memberLock));
            }
            Assert.assertEquals(Files.readAllBytes(marker), PAYLOAD);
            Assert.assertEquals(Files.readAllBytes(part), PAYLOAD);
        } finally {
            Files.deleteIfExists(memberLock);
            Files.delete(member);
            Files.delete(marker);
            Files.delete(part);
            Files.delete(dir);
        }
    }

    private static final class Assembly extends BinaryBuilder {
        private final int bytes;
        private final boolean reject;
        private DataSource received;
        private InputStream stream;

        Assembly(int bytes, boolean reject) {
            this.bytes = bytes;
            this.reject = reject;
        }

        @Override
        public OMElement processDocument(DataSource data, String type, org.apache.axis2.context.MessageContext msg)
                throws AxisFault {
            received = data;
            if (bytes > 0) {
                try {
                    stream = data.getInputStream();
                    for (int index = 0; index < bytes; index++) {
                        Assert.assertEquals(stream.read(), index < PAYLOAD.length ? PAYLOAD[index] : -1);
                    }
                } catch (IOException failure) {
                    throw new AxisFault("Could not read", failure);
                }
            }
            if (reject) {
                throw new AxisFault("The message builder rejected the content");
            }
            return super.processDocument(data, type, msg);
        }
    }

    @DataProvider
    public Object[][] assembly() {
        return new Object[][] {{0, true}, {1, true}, {4, true}, {1, false}};
    }

    @Test(dataProvider = "assembly")
    public void synchronousBuilderOwnsOneLoan(int bytes, boolean reject) throws Exception {
        Path dir = Files.createTempDirectory("file-builder");
        Path file = Files.write(dir.resolve("data"), PAYLOAD);
        Path lock = dir.resolve("data.lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Assembly builder = new Assembly(bytes, reject);
            Axis2MessageContext message = message(files, builder);
            try {
                read(message, "/data", true, true);
                Assert.assertFalse(reject);
            } catch (SynapseException failure) {
                Assert.assertTrue(reject);
                Assert.assertEquals(message.getProperty("ERROR_CODE"), "700108");
            }
            StreamLease lease = StreamLeases.find(message).get(files.name, files.factory.getContext().getBaseUri() + "/data");
            if (reject) {
                Assert.assertEquals(lease.reason(), StreamLease.Reason.APPLICATION_ERROR);
                try {
                    builder.received.getInputStream();
                    Assert.fail("The failed delivery opened again");
                } catch (IOException expected) {
                    Assert.assertEquals(lease.state(), StreamLease.State.ENDED);
                }
                if (bytes > 0 && bytes <= PAYLOAD.length) {
                    try {
                        builder.stream.read();
                        Assert.fail("The rejected reader returned another byte");
                    } catch (IOException expected) {
                        Assert.assertEquals(lease.reason(), StreamLease.Reason.APPLICATION_ERROR);
                    }
                }
                long deadline = System.nanoTime() + 3_000_000_000L;
                while ((Files.exists(lock) || pool.getNumActive() != 0) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                Assert.assertFalse(Files.exists(lock));
            } else {
                Assert.assertEquals(builder.stream.read(), 20);
                builder.stream.close();
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            }
            Assert.assertEquals(pool.getNumActive(), 0);
            // A release after EOF may borrow a handler of its own.
            Assert.assertEquals(pool.attempts.size(), reject && bytes > PAYLOAD.length ? 2 : 1);
            Assert.assertEquals(pool.returns(), pool.attempts.size());
        } finally {
            Files.deleteIfExists(lock);
            Files.delete(file);
            Files.delete(dir);
        }
    }

    @Test(dataProvider = "modes")
    public void streamingBuilderSelectionFailsBeforeOwnershipTransfer(boolean folder, boolean own) throws Exception {
        Path dir = Files.createTempDirectory("read-selection");
        Path file = Files.write(dir.resolve("data"), PAYLOAD);
        Path lock = dir.resolve("data.lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Builder unsupported = (input, type, context) -> {
                throw new AssertionError("A builder without data source support was called");
            };
            Axis2MessageContext message = message(files, unsupported);
            try {
                read(message, folder ? "/" : "/data", own, true);
                Assert.fail("A streaming read succeeded without a supported builder");
            } catch (SynapseException expected) {
                Assert.assertEquals(message.getProperty("ERROR_CODE"), "700108");
            }
            String base = files.factory.getContext().getBaseUri() + "/";
            StreamLeases holder = StreamLeases.find(message);
            StreamLease record = holder == null ? null : holder.get(files.name, base + "data");
            if (own) {
                Assert.assertEquals(record.reason(), StreamLease.Reason.APPLICATION_ERROR);
                Assert.assertNull(holder.get(files.name, base));
            } else {
                Assert.assertNull(record);
            }
            Assert.assertFalse(Files.exists(lock));
            Assert.assertEquals(pool.attempts.size(), 1);
            Assert.assertEquals(pool.returns(), 1);
            Assert.assertEquals(pool.getNumActive(), 0);
        } finally {
            Files.deleteIfExists(lock);
            Files.delete(file);
            Files.delete(dir);
        }
    }

    @DataProvider
    public Object[][] lifecycle() {
        return new Object[][] {{"idle", false}, {"idle", true}, {"retire", false}, {"retire", true},
                {"age", false}, {"age", true}};
    }

    @Test(dataProvider = "lifecycle")
    public void readLeaseFollowsThePoolLifecycle(String change, boolean stream) throws Exception {
        Path dir = Files.createTempDirectory("file-read-lifecycle");
        Path file = Files.write(dir.resolve("data"), PAYLOAD);
        Path other = Files.write(dir.resolve("other"), PAYLOAD);
        Path lock = dir.resolve("data.lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Axis2MessageContext message = message(files, new BinaryBuilder());
            read(message, "/data", true, stream);
            StreamLease lease = StreamLeases.find(message).get(files.name, files.factory.getContext().getBaseUri() + "/data");
            Assert.assertEquals(lease.state(), stream ? StreamLease.State.ACTIVE : StreamLease.State.REPLAY);
            if (change.equals("idle")) {
                lease.expire(lease.nextDeadline(0));
                Assert.assertEquals(lease.reason(), StreamLease.Reason.IDLE_LIMIT);
            } else if (change.equals("retire")) {
                files.redeploy();
                Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
                Assert.assertTrue(Files.exists(lock));
            } else {
                pool.setAgedTimeoutEnabled(true);
                pool.setPoolConnectionAgedTimeout(1);
                pool.setStrat(java.time.Instant.now().minusSeconds(10));
                read(message(files, new BinaryBuilder()), "/other", false, false);
                Assert.assertTrue(pool.isClosed());
                if (stream) {
                    try (InputStream input = lease.getInputStream()) {
                        Assert.assertEquals(input.readAllBytes(), PAYLOAD);
                    }
                    Assert.assertEquals(pool.returnedToClosed, 1);
                }
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
                Assert.assertTrue(Files.exists(lock));
            }
            Assert.assertEquals(pool.getNumActive(), 0);
        } finally {
            Files.deleteIfExists(lock);
            Files.delete(file);
            Files.delete(other);
            Files.delete(dir);
        }
    }

    @Test(dataProvider = "modes")
    public void failedReadPublishesTheProviderIdentityWhileTheMarkerIsPresent(boolean streamed, boolean folder)
            throws Exception {
        Path dir = Files.createTempDirectory("file-read-publication");
        Path file = Files.write(dir.resolve("report [2]"), PAYLOAD);
        Path lock = dir.resolve("report [2].lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Builder reject = (stream, type, axis) -> { throw new AxisFault("Invalid local test document"); };
            Axis2MessageContext message = message(files, reject);
            StreamLeases holder = StreamLeases.forRead(message);
            String root = files.factory.getContext().getBaseUri();
            String encoded = "/report%20%5B2%5D";
            AtomicInteger publications = new AtomicInteger();
            // Observe the actual holder insertion synchronously, before the frame can continue to disposal.
            Field field = StreamLeases.class.getDeclaredField("connections");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ConcurrentMap<Object, StreamLease>> connections =
                    (Map<String, ConcurrentMap<Object, StreamLease>>) field.get(holder);
            connections.put(files.name, new ConcurrentHashMap<Object, StreamLease>() {
                @Override
                public StreamLease put(Object key, StreamLease record) {
                    StreamLease previous = super.put(key, record);
                    Assert.assertSame(holder.get(files.name, root + encoded), record);
                    Assert.assertSame(holder.get(files.name, root + "/report [2]"), record);
                    Assert.assertEquals(record.reason(), StreamLease.Reason.APPLICATION_ERROR);
                    Assert.assertTrue(Files.exists(lock));
                    publications.incrementAndGet();
                    return previous;
                }
            });
            try {
                read(message, folder ? "/" : encoded, true, streamed);
                Assert.fail("The unsupported local document was accepted");
            } catch (SynapseException expected) {
                Assert.assertEquals(message.getProperty("ERROR_CODE"), "700108");
            }
            Assert.assertEquals(publications.get(), 1);
            Assert.assertNull(holder.get(files.name, root + "/"));
            Assert.assertNull(holder.get(files.name, root + "/report%2520%255B2%255D"));
            Assert.assertFalse(Files.exists(lock));
            Assert.assertEquals(pool.attempts.size(), 1);
            Assert.assertEquals(pool.returns(), 1);
        } finally {
            Files.deleteIfExists(lock);
            Files.delete(file);
            Files.delete(dir);
        }
    }

    @DataProvider
    public Object[][] failedReads() {
        return new Object[][] {{"application"}, {"retry"}, {"secondApplication"}, {"busy"}};
    }

    @Test(dataProvider = "failedReads")
    public void failedBuiltReadRecordsTheOwnedChildBeforeRetry(String outcome) throws Exception {
        Path dir = Files.createTempDirectory("file-retry");
        Path file = Files.write(dir.resolve("A"), PAYLOAD);
        Path sibling = Files.write(dir.resolve("B"), new byte[]{40});
        Path lock = dir.resolve("A.lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            AtomicReference<StreamLease> beforeRetry = new AtomicReference<>();
            String folder = files.factory.getContext().getBaseUri() + "/";
            BinaryBuilder builder = new BinaryBuilder() {
                int builds;
                public OMElement processDocument(InputStream in, String type, org.apache.axis2.context.MessageContext msg)
                        throws AxisFault {
                    builds++;
                    if (builds == 1 && !outcome.equals("application")) {
                        if (outcome.equals("busy")) {
                            pool.denial = new PoolBusyException(new IllegalStateException("The test pool is busy"));
                        }
                        FileSystemException cause = new FileSystemException("The file read stopped");
                        cause.setFault(Fault.TRANSPORT);
                        throw new AxisFault("The builder could not finish", cause);
                    }
                    if (!outcome.equals("retry")) {
                        throw new AxisFault("Invalid document");
                    }
                    return super.processDocument(in, type, msg);
                }
            };
            Axis2MessageContext message = message(files, builder);
            pool.afterBorrow = ignored -> {
                if (pool.attempts.size() == 2) {
                    StreamLeases holder = StreamLeases.find(message);
                    beforeRetry.set(holder == null ? null : holder.get(files.name, folder + "A"));
                }
            };
            try {
                read(message, "/", true, false, "filePattern", "A");
                Assert.assertEquals(outcome, "retry");
            } catch (SynapseException expected) {
                Assert.assertNotEquals(outcome, "retry");
                Assert.assertEquals(message.getProperty("ERROR_CODE"), outcome.equals("busy") ? "700101" : "700108");
            }
            StreamLeases holder = StreamLeases.find(message);
            StreamLease last = holder.get(files.name, folder + "A");
            Assert.assertNull(holder.get(files.name, folder));
            Assert.assertNull(holder.get(files.name, folder + "B"));
            Assert.assertEquals(Files.readAllBytes(sibling), new byte[]{40});
            Assert.assertEquals(pool.getNumActive(), 0);
            if (outcome.equals("application")) {
                Assert.assertEquals(last.reason(), StreamLease.Reason.APPLICATION_ERROR);
                Assert.assertEquals(pool.attempts.size(), 1);
                Assert.assertEquals(pool.returns(), 1);
            } else {
                Assert.assertEquals(pool.attempts.size(), 2);
                Assert.assertEquals(pool.attempts.get(0).getAsJsonObject().get("invalidates").getAsInt(), 1);
                if (outcome.equals("busy")) {
                    Assert.assertEquals(last.reason(), StreamLease.Reason.TRANSPORT_ERROR);
                    Assert.assertEquals(pool.returns(), 0);
                } else {
                    Assert.assertEquals(beforeRetry.get().reason(), StreamLease.Reason.TRANSPORT_ERROR);
                    Assert.assertNotSame(last, beforeRetry.get());
                    if (outcome.equals("retry")) {
                        Assert.assertEquals(last.state(), StreamLease.State.REPLAY);
                    } else {
                        Assert.assertEquals(last.reason(), StreamLease.Reason.APPLICATION_ERROR);
                    }
                    Assert.assertEquals(pool.returns(), 1);
                }
            }
        } finally {
            Files.deleteIfExists(lock);
            Files.delete(file);
            Files.delete(sibling);
            Files.delete(dir);
        }
    }

    @Test
    public void readSelectsOnlyEligibleDataAndHonoursForeignLock() throws Exception {
        Path dir = Files.createTempDirectory("file-guards");
        Path folder = Files.createDirectory(dir.resolve(".part"));
        Path reserved = Files.write(dir.resolve("data.fail.0t9k2m17f3k9q2.next"), PAYLOAD);
        Path old = Files.write(dir.resolve("old.next"), PAYLOAD);
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 15_000));
        Path young = Files.write(dir.resolve("young.next"), PAYLOAD);
        Path foreign = Files.write(dir.resolve("foreign.next"), PAYLOAD);
        String owner = "4711:otherhost:10.0.0.9:" + System.currentTimeMillis() + ":v2:" + UUID.randomUUID();
        Path lock = Files.write(dir.resolve("foreign.next.lock"), owner.getBytes(StandardCharsets.UTF_8));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext message = message(files, new BinaryBuilder());
            read(message, "/", false, true, "filePattern", ".*next", "includeFiles", "old.*|young.*",
                    "excludeFiles", "unrelated.*", "minFileAge", "10s", "maxFileAge", "1m",
                    "timeBetweenSizeCheck", "1");
            String root = files.factory.getContext().getBaseUri() + "/";
            StreamLease chosen = StreamLeases.find(message).get(files.name, root + "old.next");
            Assert.assertNotNull(chosen);
            try (InputStream readable = chosen.getInputStream()) {
                Assert.assertEquals(readable.read(), 10);
            }
            try {
                read(message, "/", false, false, "filePattern", "foreign.*");
                Assert.fail("A locked folder member was read");
            } catch (SynapseException expected) {
                Assert.assertEquals(message.getProperty("ERROR_CODE"), "700108");
            }
            try {
                read(message, "/foreign.next", false, false);
                Assert.fail("A locked single file was read");
            } catch (SynapseException expected) {
                Assert.assertEquals(message.getProperty("ERROR_CODE"), "700106");
            }
            Assert.assertEquals(Files.readAllBytes(lock), owner.getBytes(StandardCharsets.UTF_8));
            Assert.assertEquals(Files.readAllBytes(foreign), PAYLOAD);
            Assert.assertNull(StreamLeases.find(message).get(files.name, root + "foreign.next"));
        } finally {
            for (Path entry : new Path[]{lock, foreign, young, old, reserved, folder, dir}) {
                Files.delete(entry);
            }
        }
    }
}
