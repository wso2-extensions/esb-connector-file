/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.connector.operations;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.synapse.MessageContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class OperationFrameStreamTest {
    static MessageContext message() {
        Map<String, Object> values = new HashMap<>();
        return (MessageContext) Proxy.newProxyInstance(MessageContext.class.getClassLoader(),
                new Class<?>[]{MessageContext.class}, (proxy, method, args) -> {
                    if ("getProperty".equals(method.getName())) {
                        return values.get(args[0]);
                    }
                    if ("setProperty".equals(method.getName())) {
                        values.put((String) args[0], args[1]);
                    }
                    return null;
                });
    }

    abstract static class Steps<T> implements OperationFrame.Operation<T> {
        final LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
        public LinkedHashMap<String, OperationFrame.Lock> paths() { return paths; }
        public OperationFrame.Use use(String path) { return OperationFrame.Use.SOURCE; }
        public OperationFrame.Retry retry() { return OperationFrame.Retry.SAFE; }
        public T removed(String path) { throw new AssertionError(path); }
    }

    @Test
    public void finalReleasePreservesCompletedWorkAndStopsUsingTheLostHandler() throws Exception {
        for (boolean retire : new boolean[]{false, true}) {
            for (boolean lose : new boolean[]{false, true}) {
                finalRelease(retire, lose);
            }
        }
    }

    private void finalRelease(boolean retire, boolean lose) throws Exception {
        Path dir = Files.createTempDirectory("frame-final");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease other = files.borrow();
            try {
                ConnectionLease prepared = files.borrow();
                FileSystemHandler handler = (FileSystemHandler) prepared.getConnection();
                ConnectionPool pool = FilePoolFixture.pool(prepared);
                DefaultFileSystemManager manager = (DefaultFileSystemManager) handler.getFsManager();
                AtomicBoolean finish = new AtomicBoolean();
                AtomicInteger finalCalls = new AtomicInteger();
                manager.addProvider("releasecheck", new DefaultLocalFileProvider() {
                    public FileObject findFile(FileObject base, String uri, FileSystemOptions options)
                            throws FileSystemException {
                        if (finish.get()) {
                            finalCalls.incrementAndGet();
                            if (lose) {
                                FileSystemException lost = new FileSystemException("The final release failed");
                                lost.setFault(Fault.TRANSPORT);
                                throw lost;
                            }
                        }
                        return manager.resolveFile("file" + uri.substring("releasecheck".length()), options);
                    }
                });
                MessageContext message = message();
                StreamLeases holder = StreamLeases.forRead(message);
                Steps<Integer> op = new Steps<Integer>() {
                    public Integer steps(Attempt attempt) throws Exception {
                        Assert.assertSame(attempt.handler(), handler);
                        Assert.assertEquals(attempt.number, 1);
                        for (String uri : paths.keySet()) {
                            Assert.assertEquals(holder.get(files.name, uri).state(), StreamLease.State.LENT);
                            Path source = dir.resolve(uri.substring(uri.lastIndexOf('/') + 1));
                            if (retire) {
                                Files.copy(source, dir.resolve(source.getFileName() + ".copy"));
                            } else {
                                Files.delete(source);
                                attempt.removed(uri);
                            }
                        }
                        if (retire) {
                            files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                        }
                        finish.set(true);
                        return 2;
                    }
                };
                for (String name : new String[]{"one", "two"}) {
                    Path source = Files.write(dir.resolve(name), new byte[]{42});
                    String uri = "releasecheck" + source.toUri().toString().substring("file".length());
                    HeldLock lock = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
                    Assert.assertEquals(lock.lockShare(StreamLease.io(handler), 1, 0).kind, LockOutcome.Kind.ACQUIRED);
                    context.publish(holder, StreamLease.replay(context, uri, 1, 0, lock), null);
                    op.paths.put(uri, OperationFrame.Lock.GUARD);
                }
                prepared.returnToPool();
                Assert.assertEquals(OperationFrame.run(message, files.name, 1, op).intValue(), 2);
                Assert.assertEquals(pool.getNumActive(), 1);
                Assert.assertEquals(pool.getNumIdle(), lose ? 0 : 1);
                if (lose) {
                    Assert.assertEquals(finalCalls.get(), 1);
                } else {
                    Assert.assertTrue(finalCalls.get() >= 2);
                }
                for (String uri : op.paths.keySet()) {
                    Assert.assertEquals(holder.get(files.name, uri).reason(),
                            retire ? StreamLease.Reason.RETIRED : StreamLease.Reason.REMOVED);
                    Path source = dir.resolve(uri.substring(uri.lastIndexOf('/') + 1));
                    Assert.assertEquals(Files.exists(source), retire);
                    Assert.assertEquals(Files.exists(dir.resolve(source.getFileName() + ".lock")), lose);
                    if (retire) {
                        Assert.assertEquals(Files.readAllBytes(dir.resolve(source.getFileName() + ".copy")),
                                new byte[]{42});
                    }
                }
            } finally {
                other.returnToPool();
            }
        } finally {
            try (Stream<Path> paths = Files.list(dir)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    Files.delete(path);
                }
            }
            Files.delete(dir);
        }
    }

    @Test
    public void failedLockedReadPublishesBeforeRetryAndProtectsLaterDelete() throws Exception {
        Path file = Files.createTempFile("failed-read", ".bin");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            String path = file.toUri().toString();
            MessageContext message = message();
            Steps<Integer> read = new Steps<Integer>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.READ; }
                public Integer steps(Attempt attempt) throws Exception {
                    if (attempt.number == 1) {
                        FileSystemException error = new FileSystemException("The read lost the connection");
                        error.setFault(Fault.TRANSPORT);
                        throw error;
                    }
                    Assert.assertEquals(StreamLeases.find(message).get(files.name, path).reason(),
                            StreamLease.Reason.TRANSPORT_ERROR);
                    throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The read was refused");
                }
            };
            read.paths.put(path, OperationFrame.Lock.OWN);
            try {
                OperationFrame.run(message, files.name, 1, read);
                Assert.fail("The read succeeded");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.OPERATION_ERROR);
            }
            Assert.assertEquals(StreamLeases.find(message).get(files.name, path).reason(),
                    StreamLease.Reason.APPLICATION_ERROR);
            Files.write(file, new byte[]{83});
            Steps<Integer> delete = new Steps<Integer>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.DELETE; }
                public Integer steps(Attempt attempt) { throw new AssertionError("The replacement was touched"); }
            };
            delete.paths.put(path, OperationFrame.Lock.GUARD);
            try {
                OperationFrame.run(message, files.name, 1, delete);
                Assert.fail("The failed read allowed a delete");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.OPERATION_ERROR);
            }
            Assert.assertEquals(Files.readAllBytes(file), new byte[]{83});
        } finally {
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".lock"));
            Files.delete(file);
        }
    }

    @Test
    public void concurrentOpenGivesExactlyOneStreamInEveryRound() throws Exception {
        Path source = Files.createTempFile("frame-open", ".bin");
        Files.write(source, new byte[]{71});
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            AtomicInteger opens = new AtomicInteger();
            String uri = "countopen" + source.toUri().toString().substring("file".length());
            pool.afterBorrow = item -> {
                FileSystemHandler handler = (FileSystemHandler) item;
                DefaultFileSystemManager manager = (DefaultFileSystemManager) handler.getFsManager();
                if (!manager.hasProvider("countopen")) {
                    try {
                        manager.addProvider("countopen", new DefaultLocalFileProvider() {
                            public FileObject findFile(FileObject base, String name, FileSystemOptions options)
                                    throws FileSystemException {
                                FileObject real = manager.resolveFile("file" + name.substring("countopen".length()), options);
                                return counted(real, opens);
                            }
                        });
                    } catch (FileSystemException error) {
                        throw new AssertionError(error);
                    }
                }
            };
            List<int[]> rounds = new ArrayList<>();
            JsonObject facts = new JsonObject();
            try {
                for (boolean replay : new boolean[]{false, true}) {
                    for (int round = 0; round < 200; round++) {
                        ConnectionLease loan = files.borrow();
                        FileObject file = ((FileSystemHandler) loan.getConnection()).resolveFile(uri);
                        StreamLease lease = StreamLease.active(context, uri, "binary", 1,
                                file.getContent().getLastModifiedTime(), loan, file, null);
                        if (replay) {
                            try (InputStream first = lease.getInputStream()) {
                                Assert.assertEquals(first.read(), 71);
                                Assert.assertEquals(first.read(), -1);
                            }
                        }
                        int before = opens.get();
                        AtomicLong target = new AtomicLong();
                        CyclicBarrier start = new CyclicBarrier(2,
                                () -> target.set(System.nanoTime() + 4000000));
                        Callable<InputStream> open = () -> {
                            start.await(5, TimeUnit.SECONDS);
                            while (System.nanoTime() < target.get()) {
                                Thread.onSpinWait();
                            }
                            try {
                                return lease.getInputStream();
                            } catch (IOException expected) {
                                return null;
                            }
                        };
                        Future<InputStream> one = workers.submit(open);
                        Future<InputStream> two = workers.submit(open);
                        InputStream a = one.get(5, TimeUnit.SECONDS);
                        InputStream b = two.get(5, TimeUnit.SECONDS);
                        int[] observation = {replay ? 1 : 0, opens.get() - before,
                                a == null ? 0 : 1, b == null ? 0 : 1, -1, pool.getNumActive()};
                        rounds.add(observation);
                        Assert.assertTrue((a == null) != (b == null));
                        Assert.assertEquals(opens.get() - before, 1);
                        Assert.assertEquals(pool.getNumActive(), 1);
                        try (InputStream winner = a == null ? b : a) {
                            Assert.assertEquals(winner.read(), 71);
                            Assert.assertEquals(winner.read(), -1);
                        }
                        observation[4] = pool.getNumActive();
                        Assert.assertEquals(observation[4], 0);
                        lease.retire();
                    }
                }
            } finally {
                pool.inventory(facts);
                facts.add("rounds", new Gson().toJsonTree(rounds));
                FrameUnitEvidence.write("C6.10", facts);
            }
            for (Map.Entry<String, JsonElement> fate : facts.getAsJsonObject("loanFates").entrySet()) {
                Assert.assertEquals(fate.getValue().getAsJsonArray().size(), 1, fate.getKey());
            }
        } finally {
            workers.shutdownNow();
            Files.delete(source);
        }
    }

    private static FileObject counted(FileObject real, AtomicInteger opens) {
        return (FileObject) Proxy.newProxyInstance(FileObject.class.getClassLoader(), new Class<?>[]{FileObject.class},
                (proxy, method, args) -> {
                    if ("getContent".equals(method.getName())) {
                        FileContent content = real.getContent();
                        return Proxy.newProxyInstance(FileObject.class.getClassLoader(),
                                new Class<?>[]{FileContent.class},
                                (ignored, call, arguments) -> {
                                    try {
                                        Object result = call.invoke(content, arguments);
                                        if ("getInputStream".equals(call.getName())) {
                                            opens.incrementAndGet();
                                        }
                                        return result;
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Test
    public void freshReleaseReturnsItsOwnLoanOnlyAfterFastFailure() throws Exception {
        for (boolean slow : new boolean[]{false, true}) {
            for (boolean breaker : new boolean[]{false, true}) {
                Path dir = Files.createTempDirectory("frame-release");
                try (FilePoolFixture files = new FilePoolFixture("LOCAL", "suspendOnConnectionFailure",
                        Boolean.toString(breaker))) {
                    Path source = Files.createFile(dir.resolve("source"));
                    Path target = Files.createFile(dir.resolve("target"));
                    FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
                    Steps<Integer> op = new Steps<Integer>() {
                        public Integer steps(Attempt attempt) throws Exception {
                            Assert.assertEquals(attempt.number, 1);
                            Assert.assertTrue(Files.exists(dir.resolve("source.lock")));
                            Assert.assertTrue(Files.exists(dir.resolve("target.lock")));
                            if (slow) {
                                Thread.sleep(5010);
                            }
                            FileSystemException failure = new FileSystemException("The reply was lost");
                            failure.setFault(Fault.TRANSPORT);
                            throw failure;
                        }
                    };
                    op.paths.put(source.toUri().toString(), OperationFrame.Lock.OWN);
                    op.paths.put(target.toUri().toString(), OperationFrame.Lock.OWN);
                    try {
                        OperationFrame.run(message(), files.name, 0, op);
                        Assert.fail("The lost reply was reported as success");
                    } catch (OperationFrame.Failure failure) {
                        Assert.assertEquals(failure.error, Error.CONNECTION_ERROR);
                    }
                    // The release try runs before the failure reaches the breaker, so a breaker that the failure
                    // opens does not stop it.
                    boolean fresh = !slow;
                    JsonObject facts = new JsonObject();
                    pool.inventory(facts);
                    Assert.assertEquals(facts.getAsJsonArray("borrowedLoans").size(), fresh ? 2 : 1);
                    Assert.assertEquals(pool.returns(), fresh ? 1 : 0);
                    Assert.assertEquals(pool.getNumActive(), 0);
                    Assert.assertEquals(pool.getNumIdle(), fresh ? 1 : 0);
                    Assert.assertEquals(Files.exists(dir.resolve("source.lock")), !fresh);
                    Assert.assertEquals(Files.exists(dir.resolve("target.lock")), !fresh);
                } finally {
                    try (Stream<Path> paths = Files.list(dir)) {
                        for (Path path : (Iterable<Path>) paths::iterator) {
                            Files.delete(path);
                        }
                    }
                    Files.delete(dir);
                }
            }
        }
    }

    @Test
    public void folderSkipsTerminalMembersAndStopsOnTheLostConnection() throws Exception {
        Path dir = Files.createTempDirectory("frame-members");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            MessageContext message = message();
            StreamLeases holder = StreamLeases.forRead(message);
            String a = dir.resolve("a").toUri().toString();
            String b = dir.resolve("b").toUri().toString();
            String c = dir.resolve("c").toUri().toString();
            String d = dir.resolve("d").toUri().toString();
            holder.failed(context, a, StreamLease.Reason.APPLICATION_ERROR);
            holder.failed(context, d, StreamLease.Reason.TRANSPORT_ERROR);
            HeldLock removed = context.getShareLock().enterLocalOnly(b, 1, 0).handle;
            StreamLease deleted = StreamLease.replay(context, b, 1, 0, removed);
            context.publish(holder, deleted, null);
            deleted.lend().removed(null);
            HeldLock taken = context.getShareLock().enterLocalOnly(c, 1, 0).handle;
            StreamLease foreign = StreamLease.replay(context, c, 1, 0, taken);
            context.publish(holder, foreign, null);
            taken.abandon();
            foreign.lend().takenOver();
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            try {
                OperationFrame.run(message, files.name, 1, new Steps<Integer>() {
                    public Integer steps(Attempt attempt) throws Exception {
                        Assert.assertEquals(attempt.guardMember(a), OperationFrame.Member.APPLICATION_ERROR);
                        Assert.assertEquals(attempt.guardMember(b), OperationFrame.Member.REMOVED);
                        Assert.assertEquals(attempt.guardMember(c), OperationFrame.Member.LOCKED);
                        attempt.guardMember(d);
                        throw new AssertionError("The walk continued after a lost connection");
                    }
                });
                Assert.fail("The walk returned success");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.CONNECTION_ERROR);
            }
            Assert.assertEquals(pool.returns(), 1);
            Assert.assertEquals(pool.getNumActive(), 0);
        } finally {
            Files.delete(dir);
        }
    }

    @Test
    public void successfulReadRetryReplacesItsFailedRecordAndLentFailurePreservesTheLease() throws Exception {
        Path file = Files.createTempFile("frame-replay", ".bin");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            String path = file.toUri().toString();
            MessageContext message = message();
            Steps<StreamLease> read = new Steps<StreamLease>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.READ; }
                public StreamLease steps(Attempt attempt) throws Exception {
                    if (attempt.number == 1) {
                        FileSystemException lost = new FileSystemException("Connection lost");
                        lost.setFault(Fault.TRANSPORT);
                        throw lost;
                    }
                    StreamLease first = StreamLeases.find(message).get(files.name, path);
                    Assert.assertEquals(first.reason(), StreamLease.Reason.TRANSPORT_ERROR);
                    return attempt.publishRead(message, path, null, "binary", Files.size(file),
                            Files.getLastModifiedTime(file).toMillis());
                }
            };
            read.paths.put(path, OperationFrame.Lock.OWN);
            StreamLease lease = OperationFrame.run(message, files.name, 1, read);
            Assert.assertSame(StreamLeases.find(message).get(files.name, path), lease);
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            byte[] owner = Files.readAllBytes(marker);
            Steps<Void> lent = new Steps<Void>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.READ; }
                public Void steps(Attempt attempt) throws Exception {
                    Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                    throw new OperationFrame.Failure(Error.OPERATION_ERROR, "Read refused");
                }
            };
            lent.paths.put(path, OperationFrame.Lock.OWN);
            try {
                OperationFrame.run(message, files.name, 1, lent);
                Assert.fail("The read must propagate its error");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.OPERATION_ERROR);
            }
            Assert.assertSame(StreamLeases.find(message).get(files.name, path), lease);
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            Assert.assertEquals(Files.readAllBytes(marker), owner);
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }

    @Test
    public void retryThatFailsBeforeItsLockKeepsTheFirstReadRecord() throws Exception {
        Path file = Files.createTempFile("frame-prior-read", ".bin");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            String path = file.toUri().toString();
            MessageContext message = message();
            List<StreamLease> first = new ArrayList<>();
            Steps<Void> read = new Steps<Void>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.READ; }
                public void options(FileSystemHandler handler) throws Exception {
                    StreamLeases holder = StreamLeases.find(message);
                    if (holder != null) {
                        first.add(holder.get(files.name, path));
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, "Refused before locking");
                    }
                }
                public Void steps(Attempt attempt) throws Exception {
                    Assert.assertEquals(attempt.number, 1);
                    FileSystemException lost = new FileSystemException("The read lost the connection");
                    lost.setFault(Fault.TRANSPORT);
                    throw lost;
                }
            };
            read.paths.put(path, OperationFrame.Lock.OWN);
            try {
                OperationFrame.run(message, files.name, 1, read);
                Assert.fail("The retry options failed");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.OPERATION_ERROR);
            }
            Assert.assertEquals(first.size(), 1);
            Assert.assertSame(StreamLeases.find(message).get(files.name, path), first.get(0));
            Assert.assertEquals(first.get(0).reason(), StreamLease.Reason.TRANSPORT_ERROR);
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }

    @Test
    public void aCloneChecksTheSharedFailedReadRecordAgainBeforeItsSteps() throws Exception {
        Path file = Files.createTempFile("clone-second-look", ".bin");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            String path = file.toUri().toString();
            MessageContext reader = message();
            MessageContext clone = message();
            StreamLeases holder = StreamLeases.forRead(reader);
            clone.setProperty("FILE_STREAM_LEASES", holder);
            Files.write(file, new byte[]{65, 66, 67});
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Steps<Void> write = new Steps<Void>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.WRITE; }
                public void options(FileSystemHandler handler) {
                    Assert.assertNull(holder.get(files.name, path));
                    holder.failed(handler.getContext(), path, StreamLease.Reason.APPLICATION_ERROR);
                }
                public Void steps(Attempt attempt) { throw new AssertionError("The clone missed the failed read"); }
            };
            write.paths.put(path, OperationFrame.Lock.WHOLE);
            try {
                OperationFrame.run(clone, files.name, 1, write);
                Assert.fail("The clone did not propagate the failed read");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.OPERATION_ERROR);
            }
            Assert.assertEquals(holder.get(files.name, path).reason(), StreamLease.Reason.APPLICATION_ERROR);
            Assert.assertEquals(pool.returns(), 1);
            Assert.assertEquals(pool.getNumActive(), 0);
            Assert.assertFalse(Files.exists(marker));
            Assert.assertEquals(Files.readAllBytes(file), new byte[]{65, 66, 67});
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }

    @Test
    public void lockBusyBeforeReadingCreatesNoTerminalReadRecord() throws Exception {
        Path file = Files.createTempFile("read-busy", ".bin");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        byte[] other = ("18:other:address:" + System.currentTimeMillis()).getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        Files.write(marker, other);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            MessageContext message = message();
            String path = file.toUri().toString();
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Steps<Void> read = new Steps<Void>() {
                public OperationFrame.Use use(String ignored) { return OperationFrame.Use.READ; }
                public Void steps(Attempt attempt) { throw new AssertionError("The read ignored the other writer"); }
            };
            read.paths.put(path, OperationFrame.Lock.OWN);
            try {
                OperationFrame.run(message, files.name, 1, read);
                Assert.fail("The read ignored the existing marker");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.FILE_LOCKING_ERROR);
            }
            Assert.assertNull(StreamLeases.find(message));
            Assert.assertEquals(pool.returns(), 1);
            Assert.assertEquals(pool.getNumActive(), 0);
            Assert.assertEquals(Files.readAllBytes(marker), other);
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }
}
