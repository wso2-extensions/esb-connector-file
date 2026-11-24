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

package org.wso2.carbon.connector.connection;

import org.apache.synapse.MessageContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.carbon.connector.operations.FilePoolFixture;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.integration.connector.core.util.PoolTestPoints;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.wso2.carbon.connector.operations.FilePoolFixture.fileWithStream;

public class StreamLeaseTest {
    private static final long IDLE = 60000;

    @Test
    public void builtReadReleasesOnceAndReaperExits() throws Exception {
        recordRuntimeDependencies();
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong started = new AtomicLong(-1);
        AtomicLong released = new AtomicLong(-1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = virtualContext(files, time::get, (lock, io) -> {
                        calls.incrementAndGet();
                        started.set(time.get());
                        entered.countDown();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("The virtual release did not finish");
                            }
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                        LockOutcome result = lock.releaseChecked(io);
                        released.set(time.get());
                        return result;
                    });
            String path = "file:///stream/" + files.name;
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            Assert.assertNotNull(lock);
            StreamLease lease = StreamLease.replay(context, path, 17, 5, lock);
            Assert.assertNull(reaper(context));
            context.register(lease);
            Thread worker = reaper(context);
            Assert.assertNotNull(worker);
            Assert.assertTrue(worker.isDaemon());
            try {
                for (int second = 1; second <= 130; second++) {
                    time.set(second * 1000L);
                    context.wakeReaper();
                    if (second >= 60 && started.get() < 0) {
                        // At the last possible two-second start, let a scheduled worker run before advancing.
                        entered.await(second == 120 ? 5000 : 10, TimeUnit.MILLISECONDS);
                    }
                    if (started.get() >= 0 && time.get() >= started.get() + 2000 && finish.getCount() > 0) {
                        finish.countDown();
                        worker.join(5000);
                    }
                }
            } finally {
                finish.countDown();
            }
            System.out.println("releaseCalls=" + calls.get() + " startMs=" + started.get() + " endMs="
                    + released.get() + " state=" + lease.state() + " reaperAlive=" + worker.isAlive());
            Assert.assertEquals(calls.get(), 1);
            Assert.assertTrue(started.get() >= 60000 && released.get() <= 122000);
            Assert.assertEquals(released.get() - started.get(), 2000L);
            Assert.assertEquals(lease.reason(), StreamLease.Reason.IDLE_LIMIT);
            Assert.assertFalse(worker.isAlive());
            Assert.assertNull(reaper(context));
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
        }
    }

    @Test
    public void reaperGoesOnAfterAFailedRound() throws Exception {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = virtualContext(files, time::get, (lock, io) -> {
                if (calls.incrementAndGet() == 1) {
                    first.countDown();
                    throw new IllegalStateException("The release failed");
                }
                second.countDown();
                return lock.releaseChecked(io);
            });
            HeldLock failing = context.getShareLock().enterLocalOnly("file:///stream/" + files.name + "/1", 1, 0)
                    .handle;
            context.register(StreamLease.replay(context, "file:///stream/" + files.name + "/1", 17, 5, failing));
            Thread worker = reaper(context);
            time.set(10000);
            HeldLock next = context.getShareLock().enterLocalOnly("file:///stream/" + files.name + "/2", 1, 0).handle;
            StreamLease later = StreamLease.replay(context, "file:///stream/" + files.name + "/2", 17, 5, next);
            context.register(later);
            try {
                time.set(IDLE + 1000);
                context.wakeReaper();
                Assert.assertTrue(first.await(5, TimeUnit.SECONDS));
                time.set(IDLE + 11000);
                context.wakeReaper();
                Assert.assertTrue(second.await(5, TimeUnit.SECONDS), "The reaper stopped after a failed round");
                worker.join(5000);
                Assert.assertFalse(worker.isAlive());
                Assert.assertEquals(later.reason(), StreamLease.Reason.IDLE_LIMIT);
            } finally {
                failing.abandon();
            }
        }
    }

    @Test
    public void twoLendersHaveOneWinnerAndRetirementLeavesItToTheOwner() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            String path = "file:///stream/" + files.name;
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            StreamLease lease = StreamLease.replay(context, path, 17, 5, lock);
            context.register(lease);
            Thread worker = reaper(context);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<StreamLease.Lending> lend = () -> {
                start.await(5, TimeUnit.SECONDS);
                return lease.lend();
            };
            try {
                Future<StreamLease.Lending> first = workers.submit(lend);
                Future<StreamLease.Lending> second = workers.submit(lend);
                StreamLease.Lending firstToken = first.get(5, TimeUnit.SECONDS);
                StreamLease.Lending secondToken = second.get(5, TimeUnit.SECONDS);
                Assert.assertTrue((firstToken == null) != (secondToken == null));
                StreamLease.Lending winner = firstToken == null ? secondToken : firstToken;
                files.factory.beforePoolClose(PoolCloseReason.AGE);
                Assert.assertFalse(context.isClosed());
                Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                lease.expire(Long.MAX_VALUE);
                Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                winner.giveBack(null);
                Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
                worker.join(5000);
                Assert.assertFalse(worker.isAlive());
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    public void finalReadAndLifecycleFollowBothScheduledOrders() throws Exception {
        for (String action : new String[] {"idle", "retire", "rotate"}) {
            finalReadOrder(action, true);
            finalReadOrder(action, false);
        }
    }

    private static void finalReadOrder(String action, boolean lifecycleFirst) throws Exception {
        CountDownLatch beforeEntry = new CountDownLatch(1);
        CountDownLatch enter = new CountDownLatch(1);
        CountDownLatch inProvider = new CountDownLatch(1);
        CountDownLatch eof = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease core = context.borrowForStream();
            ConnectionPool original = pool(core);
            InputStream bytes = new InputStream() {
                public int read() {
                    providerCalls.incrementAndGet();
                    inProvider.countDown();
                    await(eof);
                    return -1;
                }
            };
            StreamLease lease = StreamLease.active(context, "file:///order/" + files.name, "binary", 0, 0,
                    core, fileWithStream(bytes), null);
            context.register(lease);
            InputStream reader = lease.getInputStream();
            Future<Integer> result = worker.submit(() -> {
                beforeEntry.countDown();
                await(enter);
                return reader.read();
            });
            await(beforeEntry);
            boolean idleWins = action.equals("idle") && lifecycleFirst;
            if (!idleWins) {
                enter.countDown();
                await(inProvider);
            }
            // For idle, the opposite order is expiry observing the reader inside its call.
            boolean afterEof = !action.equals("idle") && !lifecycleFirst;
            if (afterEof) {
                eof.countDown();
                Assert.assertEquals(result.get(5, TimeUnit.SECONDS).intValue(), -1);
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            }
            if (action.equals("idle")) {
                lease.expire(Long.MAX_VALUE);
                Assert.assertEquals(lease.state(), idleWins ? StreamLease.State.ENDED : StreamLease.State.ACTIVE);
            } else if (action.equals("retire")) {
                files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
            } else {
                original.setAgedTimeoutEnabled(true);
                original.setPoolConnectionAgedTimeout(1);
                original.setStrat(java.time.Instant.now().minusSeconds(10));
                ConnectionLease next = context.borrowForStream();
                try {
                    Assert.assertNotSame(pool(next), original);
                    Assert.assertTrue(original.isClosed());
                    Assert.assertEquals(lease.state(), afterEof ? StreamLease.State.REPLAY : StreamLease.State.ACTIVE);
                } finally {
                    next.returnToPool();
                }
            }
            enter.countDown();
            eof.countDown();
            boolean rejected = idleWins || action.equals("retire") && lifecycleFirst;
            try {
                Assert.assertEquals(result.get(5, TimeUnit.SECONDS).intValue(), -1);
                Assert.assertFalse(rejected);
            } catch (ExecutionException error) {
                Assert.assertTrue(rejected);
                Assert.assertTrue(error.getCause() instanceof IOException);
            }
            Assert.assertEquals(providerCalls.get(), idleWins ? 0 : 1);
            if (!action.equals("retire") && !idleWins) {
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            }
            awaitPoolIdle(original);
            Assert.assertEquals(original.getNumActive(), 0);
        } finally {
            enter.countDown();
            eof.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void primaryCloseErrorAtEofDisposesOnce() throws Exception {
        for (boolean transport : new boolean[] {false, true}) {
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "retriesBeforeSuspension", "0")) {
                ConnectionContext context = files.factory.getContext();
                ConnectionLease core = context.borrowForStream();
                AtomicInteger closes = new AtomicInteger();
                IOException failure = transport ? new SocketException("test close") : new AccessDeniedException("test close");
                InputStream bytes = new ByteArrayInputStream(new byte[0]) {
                    public void close() throws IOException {
                        closes.incrementAndGet();
                        throw failure;
                    }
                };
                String path = "file:///close/" + files.name;
                HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
                StreamLease lease = StreamLease.active(context, path, "binary", 0, 0, core, fileWithStream(bytes), lock);
                context.register(lease);
                try {
                    lease.getInputStream().read();
                    Assert.fail("EOF hid the close error");
                } catch (IOException expected) {
                    Assert.assertSame(expected, failure);
                }
                Assert.assertEquals(closes.get(), 1);
                Assert.assertEquals(lease.reason(), transport ? StreamLease.Reason.TRANSPORT_ERROR : StreamLease.Reason.APPLICATION_ERROR);
                Assert.assertEquals(pool(core).getNumActive(), 0);
                Assert.assertEquals(pool(core).getNumIdle(), transport ? 0 : 1);
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            }
        }
    }

    @Test
    public void retireInReadLeavesTheHandlerToTheReaderAndDiscardsTheByte() throws Exception {
        retiredRead(7);
        retiredRead(-1);
    }

    private void retiredRead(int readResult) throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            String path = "file:///stream/" + files.name;
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            ConnectionLease core = context.borrowForStream();
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            InputStream controlled = new InputStream() {
                @Override
                public int read() throws IOException {
                    inside.countDown();
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("The reader timed out");
                        }
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return readResult;
                }

                @Override
                public void close() {
                    throw new AssertionError("The retired reader closed a transport after the read returned");
                }
            };
            FileObject file = fileWithStream(controlled);
            StreamLease lease = StreamLease.active(context, path, "application/binary", 1, 0, core, file, lock);
            context.register(lease);
            InputStream input = lease.getInputStream();
            Assert.assertEquals(context.activeStreamCount(), 1);
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> result = workers.submit((Callable<Integer>) input::read);
                Assert.assertTrue(inside.await(5, TimeUnit.SECONDS));
                lease.expire(Long.MAX_VALUE);
                Assert.assertEquals(lease.state(), StreamLease.State.ACTIVE);
                try {
                    input.read();
                    Assert.fail("A second read entered the same call");
                } catch (IOException expected) {
                    Assert.assertEquals(lease.state(), StreamLease.State.ACTIVE);
                }
                context.retire();
                Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
                Assert.assertEquals(pool(core).getNumActive(), 1);
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
                resume.countDown();
                try {
                    result.get(5, TimeUnit.SECONDS);
                    Assert.fail("The retired read returned its byte");
                } catch (ExecutionException rejected) {
                    Assert.assertTrue(rejected.getCause() instanceof IOException);
                    Assert.assertTrue(rejected.getCause().getMessage().contains("retired"));
                }
                Assert.assertEquals(pool(core).getNumActive(), 0);
            } finally {
                resume.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    public void streamEndAndFreshBorrowOnSecondOpen() throws Exception {
        Path data = Files.createTempFile("stream", ".bin");
        Files.write(data, new byte[]{3, 8});
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease core = context.borrowForStream();
            String path = data.toUri().toString();
            FileSystemHandler handler = (FileSystemHandler) core.getConnection();
            FileObject file = handler.resolveFile(path);
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            StreamLease lease = StreamLease.active(context, path, "application/binary", 2,
                    file.getContent().getLastModifiedTime(), core, file, lock);
            context.register(lease);
            Assert.assertEquals(lease.getSize(), 2L);
            try (InputStream first = lease.getInputStream()) {
                Assert.assertEquals(first.read(), 3);
                Assert.assertEquals(first.read(), 8);
                Assert.assertEquals(first.read(), -1);
            }
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            Assert.assertEquals(pool(core).getNumActive(), 0);
            files.factory.beforePoolClose(PoolCloseReason.AGE);
            try (InputStream second = lease.getInputStream()) {
                Assert.assertEquals(pool(core).getNumActive(), 1);
                Assert.assertEquals(second.read(), 3);
            }
            Assert.assertEquals(pool(core).getNumActive(), 0);
            context.retire();
            Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    public void retiringAnActiveLeaseBetweenCallsEndsItOnce() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease core = context.borrowForStream();
            String path = "file:///stream/" + files.name;
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            StreamLease lease = StreamLease.active(context, path, "application/binary", 0, 0, core, null, lock);
            context.register(lease);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            Callable<Void> retire = () -> {
                start.await(5, TimeUnit.SECONDS);
                lease.retire();
                return null;
            };
            try {
                Future<Void> firstRetire = workers.submit(retire);
                Future<Void> secondRetire = workers.submit(retire);
                firstRetire.get(5, TimeUnit.SECONDS);
                secondRetire.get(5, TimeUnit.SECONDS);
                Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
                Assert.assertEquals(pool(core).getNumActive(), 0);
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test(groups = "testbuild")
    public void markedSnapshotShowsTheBorrowedPoolAndReaper() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Paths.get("target", "points")), "stream-points-").toAbsolutePath();
        String old = System.getProperty("vfs.testpoints.dir");
        System.setProperty("vfs.testpoints.dir", root.toString());
        try {
            TestPoints.copy("connector", StreamLease.class, EndpointHealth.class);
            try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
                ConnectionContext context = files.factory.getContext();
                ConnectionLease core = context.borrowForStream();
                ConnectionPool pool = pool(core);
                try {
                    String path = "file:///stream/" + files.name;
                    HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
                    StreamLease lease = StreamLease.replay(context, path, 1, 1, lock);
                    context.register(lease);
                    Thread worker = reaper(context);
                    Path copy;
                    try (Stream<Path> dirs = Files.list(root)) {
                        copy = dirs.filter(p -> p.getFileName().toString().startsWith("connector."))
                                .findFirst().get();
                    }
                    String key = "file:" + files.name;
                    String line = ask(copy, key);
                    String id = PoolTestPoints.activeCounts(key, files.factory).keySet().iterator().next();
                    Assert.assertEquals(pool.getNumActive(), 1);
                    Assert.assertTrue(line.contains("pool=" + id + " active=1"), line);
                    Assert.assertTrue(line.contains("context=" + Integer.toUnsignedString(
                            System.identityHashCode(context))), line);
                    Assert.assertTrue(line.contains("queue=0 reaper=" + worker.getId()), line);
                    context.retire();
                    worker.join(5000);
                    Assert.assertFalse(worker.isAlive());
                    Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
                } finally {
                    core.returnToPool();
                }
            }
        } finally {
            if (old == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", old);
            }
        }
    }

    private static String ask(Path copy, String key) throws Exception {
        Files.createFile(copy.resolve("pool.state.ask"));
        long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            try (Stream<Path> paths = Files.list(copy)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("pool.state@")
                        && p.getFileName().toString().endsWith(".snap")).collect(Collectors.toList())) {
                    String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    if (text.contains("key=" + key)) {
                        return text;
                    }
                }
            }
            Thread.sleep(5);
        } while (System.nanoTime() < stop);
        throw new AssertionError("No snapshot for " + key);
    }

    @Test
    public void holderIsSharedByReferenceAndReplacesOnlyTheSelectedFile() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            Map<String, Object> values = new HashMap<>();
            MessageContext message = message(values);
            MessageContext earlyClone = message(new HashMap<>(values));
            String path = "file:///stream/" + files.name;
            Assert.assertNull(StreamLeases.find(message));
            StreamLeases holder = StreamLeases.forRead(message);
            MessageContext laterClone = message(new HashMap<>(values));
            holder.failed(context, path, StreamLease.Reason.TRANSPORT_ERROR);
            Assert.assertNull(StreamLeases.find(earlyClone));
            Assert.assertSame(StreamLeases.find(laterClone), holder);
            Assert.assertEquals(holder.get(context.getName(), path).reason(), StreamLease.Reason.TRANSPORT_ERROR);
            Assert.assertNull(holder.get(context.getName(), path + "other"));
            Assert.assertNull(holder.get(context.getName() + "other", path));
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            StreamLease first = StreamLease.replay(context, path, 1, 2, lock);
            context.publish(holder, first, null);
            StreamLease.Lending lending = first.lend();
            Assert.assertNotNull(lending);
            StreamLease next = StreamLease.replay(context, path, 2, 3, lending.lock());
            context.publish(holder, next, lending);
            Assert.assertSame(StreamLeases.find(laterClone).get(context.getName(), path), next);
            Assert.assertEquals(first.reason(), StreamLease.Reason.MOVED);
            Assert.assertEquals(next.state(), StreamLease.State.REPLAY);
            Assert.assertEquals(context.getShareLock().enterLocalOnly(path, 1, 0).kind, LockOutcome.Kind.LOCK_BUSY);
            context.retire();
            Assert.assertEquals(next.reason(), StreamLease.Reason.RETIRED);
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
        }
    }

    static MessageContext message(Map<String, Object> values) {
        return (MessageContext) Proxy.newProxyInstance(StreamLeaseTest.class.getClassLoader(),
                new Class<?>[]{MessageContext.class}, (proxy, method, args) -> {
                    if ("getProperty".equals(method.getName())) {
                        return values.get(args[0]);
                    }
                    if ("setProperty".equals(method.getName())) {
                        values.put((String) args[0], args[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void defaultIdleAndOneReleaseStayBelowTheStaleAge() throws Exception {
        recordRuntimeDependencies();
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = virtualContext(files, time::get, (lock, io) -> {
                calls.incrementAndGet();
                time.addAndGet(10000);
                LockOutcome released = lock.releaseChecked(io);
                time.addAndGet(120000);
                return released;
            });
            List<StreamLease> leases = new ArrayList<>();
            List<HeldLock> locks = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                String path = "file:///stream/" + files.name + "/" + index;
                HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
                Assert.assertNotNull(lock);
                locks.add(lock);
                StreamLease lease = StreamLease.replay(context, path, 1, 0, lock);
                leases.add(lease);
                context.register(lease);
            }
            time.set(1000);
            String path = "file:///stream/" + files.name + "/active";
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            ConnectionLease core = context.borrowForStream();
            StreamLease active = StreamLease.active(context, path, "application/binary", 1, 0, core, null, lock);
            context.register(active);
            Thread worker = reaper(context);
            time.set(60000);
            context.wakeReaper();
            worker.join(5000);
            Assert.assertFalse(worker.isAlive());
            Assert.assertEquals(calls.get(), 1);
            Assert.assertEquals(active.reason(), StreamLease.Reason.IDLE_LIMIT);
            Assert.assertEquals(pool(core).getNumActive(), 0);
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            for (int index = 0; index < leases.size(); index++) {
                Assert.assertEquals(leases.get(index).reason(), StreamLease.Reason.IDLE_LIMIT);
                Assert.assertEquals(locks.get(index).abandon().kind, LockOutcome.Kind.ENDED);
            }
            long bound = 2 * context.idleMs() + 10000 + 120000;
            long stale = context.getConfiguration().getLockStaleAge();
            Assert.assertTrue(time.get() <= bound);
            Assert.assertTrue(bound < stale);
            System.out.println("replay=" + leases.size() + " active=1 releases=" + calls.get()
                    + " lastEndMs=" + time.get() + " boundMs=" + bound + " staleMs=" + stale);
        }
    }

    static ConnectionContext virtualContext(FilePoolFixture files, LongSupplier clock,
            BiFunction<HeldLock, LockIo, LockOutcome> release) throws Exception {
        FileSystemHandlerFactory factory = files.factory;
        ConnectionContext original = factory.getContext();
        ConnectionConfiguration config = original.getConfiguration();
        original.retire();
        ConnectionContext context = new ConnectionContext(config, files.name, files.name, clock, IDLE, release);
        Field owner = FileSystemHandlerFactory.class.getDeclaredField("context");
        owner.setAccessible(true);
        owner.set(factory, context);
        return context;
    }

    @Test
    public void aReadErrorEndsTheHandlerAndLockAndReportsOnlyTransportFailures() throws Exception {
        IOException denied = new AccessDeniedException("Read refused");
        assertFailedRead(denied, null, false, 1);
        assertFailedRead(denied, denied, false, 1);
        assertFailedRead(denied, new SocketException("Close lost"), true, 1);
        assertFailedRead(new SocketException("Read lost"), null, true, 0);
    }

    private void assertFailedRead(IOException cause, IOException closeFailure, boolean transport, int closeCalls)
            throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            String path = "file:///stream/" + files.name;
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            ConnectionLease core = context.borrowForStream();
            core.reportSuccess();
            AtomicInteger closes = new AtomicInteger();
            InputStream controlled = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw cause;
                }

                @Override
                public void close() throws IOException {
                    closes.incrementAndGet();
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                }
            };
            StreamLease lease = StreamLease.active(context, path, "application/binary", 1, 0, core,
                    fileWithStream(controlled), lock);
            context.register(lease);
            InputStream reader = lease.getInputStream();
            try {
                reader.read();
                Assert.fail("An error in a read reached the caller as a byte");
            } catch (IOException error) {
                Assert.assertSame(error, cause);
            }
            Assert.assertEquals(closes.get(), closeCalls);
            Assert.assertEquals(lease.reason(), transport ? StreamLease.Reason.TRANSPORT_ERROR
                    : StreamLease.Reason.APPLICATION_ERROR);
            Assert.assertEquals(pool(core).getNumActive(), 0);
            Assert.assertEquals(pool(core).getNumIdle(), transport ? 0 : 1);
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            Assert.assertEquals(ConnectionHandler.getConnectionHandler().getBreakerState("file", files.name),
                    transport ? CircuitBreaker.Mode.OPEN : CircuitBreaker.Mode.CLOSED);
        }
    }

    @Test
    public void changedFileNoteOnSecondOpenIsAnApplicationFailure() throws Exception {
        Path path = Files.createTempFile("stream-note", ".bin");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            Files.write(path, new byte[]{5});
            String uri = path.toUri().toString();
            ConnectionContext context = files.factory.getContext();
            HeldLock lock = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
            ConnectionLease core = context.borrowForStream();
            FileObject file = ((FileSystemHandler) core.getConnection()).resolveFile(uri);
            StreamLease lease = StreamLease.active(context, uri, "application/binary", 1,
                    file.getContent().getLastModifiedTime(), core, file, lock);
            context.register(lease);
            try (InputStream reader = lease.getInputStream()) {
                Assert.assertEquals(reader.read(), 5);
            }
            Files.write(path, new byte[]{5, 8});
            try {
                lease.getInputStream();
                Assert.fail("The changed file opened on the old lease");
            } catch (IOException expected) {
                Assert.assertEquals(lease.reason(), StreamLease.Reason.APPLICATION_ERROR);
            }
            Assert.assertEquals(pool(core).getNumActive(), 0);
            Assert.assertEquals(pool(core).getNumIdle(), 1);
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            Assert.assertEquals(ConnectionHandler.getConnectionHandler().getBreakerState("file", files.name),
                    CircuitBreaker.Mode.CLOSED);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void retirementDuringOneReleaseAbandonsTheOtherQueuedHandle() throws Exception {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch releasing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = virtualContext(files, time::get, (lock, io) -> {
                calls.incrementAndGet();
                releasing.countDown();
                await(resume);
                return lock.releaseChecked(io);
            });
            ConnectionLease inspection = context.borrowForStream();
            ConnectionPool old = pool(inspection);
            inspection.returnToPool();
            List<HeldLock> handles = new ArrayList<>();
            List<StreamLease> leases = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                String path = "file:///stream/" + files.name + "/" + n;
                HeldLock handle = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
                handles.add(handle);
                StreamLease lease = StreamLease.replay(context, path, 1, 0, handle);
                context.register(lease);
                leases.add(lease);
            }
            Thread worker = reaper(context);
            time.set(IDLE);
            context.wakeReaper();
            try {
                Assert.assertTrue(releasing.await(5, TimeUnit.SECONDS));
                Assert.assertEquals(old.getNumActive(), 1);
                files.close();
                Assert.assertTrue(context.isClosed());
                Assert.assertEquals(old.getNumActive(), 1);
                Assert.assertEquals(calls.get(), 1);
            } finally {
                resume.countDown();
            }
            worker.join(5000);
            Assert.assertFalse(worker.isAlive());
            Assert.assertNull(reaper(context));
            Assert.assertEquals(calls.get(), 1);
            Assert.assertEquals(old.getNumActive(), 0);
            Assert.assertEquals(old.getNumIdle(), 0);
            for (int n = 0; n < 2; n++) {
                Assert.assertEquals(leases.get(n).reason(), StreamLease.Reason.IDLE_LIMIT);
                Assert.assertEquals(handles.get(n).abandon().kind, LockOutcome.Kind.ENDED);
            }
        }
    }

    @Test
    public void aSecondOpenReturnsItsUnusedHandlerWhenLendingOrExpiryWins() throws Exception {
        for (boolean expire : new boolean[]{false, true}) {
            Path path = Files.createTempFile("stream-borrow", ".bin");
            CountDownLatch borrowed = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
                Files.write(path, new byte[]{7});
                AtomicLong time = new AtomicLong();
                AtomicInteger releases = new AtomicInteger();
                ConnectionContext context = virtualContext(files, time::get, (lock, io) -> {
                    releases.incrementAndGet();
                    return lock.releaseChecked(io);
                });
                StreamLease.Owner owner = (StreamLease.Owner) Proxy.newProxyInstance(
                        StreamLeaseTest.class.getClassLoader(), new Class<?>[]{StreamLease.Owner.class},
                        (proxy, method, args) -> {
                            if ("borrowForStream".equals(method.getName())) {
                                ConnectionLease held = context.borrowForStream();
                                borrowed.countDown();
                                await(resume);
                                return held;
                            }
                            try {
                                return method.invoke(context, args);
                            } catch (InvocationTargetException ex) {
                                throw ex.getCause();
                            }
                        });
                ConnectionLease first = context.borrowForStream();
                String uri = path.toUri().toString();
                FileObject file = ((FileSystemHandler) first.getConnection()).resolveFile(uri);
                HeldLock lock = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
                StreamLease lease = StreamLease.active(owner, uri, "application/octet-stream", 1,
                        file.getContent().getLastModifiedTime(), first, file, lock);
                context.register(lease);
                Thread worker = reaper(context);
                try (InputStream firstReader = lease.getInputStream()) {
                    Assert.assertEquals(firstReader.read(), 7);
                    Assert.assertEquals(firstReader.read(), -1);
                }
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
                Future<InputStream> second = reader.submit(lease::getInputStream);
                Assert.assertTrue(borrowed.await(5, TimeUnit.SECONDS));
                Assert.assertEquals(pool(first).getNumActive(), 1);
                StreamLease.Lending loan = null;
                if (expire) {
                    time.set(IDLE + 1000);
                    context.wakeReaper();
                    worker.join(5000);
                    Assert.assertFalse(worker.isAlive());
                    Assert.assertEquals(lease.reason(), StreamLease.Reason.IDLE_LIMIT);
                } else {
                    loan = lease.lend();
                    Assert.assertNotNull(loan);
                    Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                }
                resume.countDown();
                try {
                    second.get(5, TimeUnit.SECONDS);
                    Assert.fail("The interrupted second open returned a stream");
                } catch (ExecutionException rejected) {
                    Assert.assertTrue(rejected.getCause() instanceof IOException);
                    Assert.assertTrue(rejected.getCause().getMessage().contains(expire ? "idle" : "operation"));
                }
                Assert.assertEquals(pool(first).getNumActive(), 0);
                Assert.assertEquals(releases.get(), expire ? 1 : 0);
                if (loan != null) {
                    loan.removed(null);
                    Assert.assertEquals(lease.reason(), StreamLease.Reason.REMOVED);
                }
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            } finally {
                resume.countDown();
                reader.shutdownNow();
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void concurrentFirstOpenAndStaleLendingTokenHaveOneOwner() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            String path = "file:///stream/" + files.name;
            ConnectionLease held = context.borrowForStream();
            HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            InputStream raw = new ByteArrayInputStream(new byte[]{11});
            StreamLease lease = StreamLease.active(context, path, "application/octet-stream", 1, 0, held,
                    fileWithStream(raw), lock);
            context.register(lease);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            Callable<InputStream> open = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    return lease.getInputStream();
                } catch (IOException expected) {
                    return null;
                }
            };
            try {
                Future<InputStream> firstOpen = threads.submit(open);
                Future<InputStream> secondOpen = threads.submit(open);
                InputStream firstReader = firstOpen.get(5, TimeUnit.SECONDS);
                InputStream secondReader = secondOpen.get(5, TimeUnit.SECONDS);
                Assert.assertTrue((firstReader == null) != (secondReader == null));
                try (InputStream winner = firstReader == null ? secondReader : firstReader) {
                    Assert.assertEquals(winner.read(), 11);
                    Assert.assertEquals(winner.read(), -1);
                }
                StreamLease.Lending prior = lease.lend();
                Assert.assertNotNull(prior);
                prior.giveBack(null);
                StreamLease.Lending current = lease.lend();
                Assert.assertNotNull(current);
                prior.removed(null);
                prior.moved();
                prior.takenOver();
                Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                ConnectionLease operation = context.borrowForStream();
                try {
                    current.removed((FileSystemHandler) operation.getConnection());
                } finally {
                    operation.returnToPool();
                }
                Assert.assertEquals(lease.reason(), StreamLease.Reason.REMOVED);
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
                Assert.assertEquals(pool(held).getNumActive(), 0);
            } finally {
                threads.shutdownNow();
            }
        }
    }

    @Test
    public void aReopenedReaderCrossesTheOldDeadlineAndRelendsAfterEof() throws Exception {
        Path path = Files.createTempFile("reopen-deadline", ".bin");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        CountDownLatch round = new CountDownLatch(1);
        ExecutorService readers = Executors.newSingleThreadExecutor();
        AtomicLong time = new AtomicLong();
        ThreadLocal<Boolean> pauseRead = new ThreadLocal<>();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            Files.write(path, new byte[]{13});
            ConnectionContext context = virtualContext(files, () -> {
                if (Boolean.TRUE.equals(pauseRead.get())) {
                    pauseRead.remove();
                    reading.countDown();
                    await(finishRead);
                }
                return time.get();
            }, (lock, io) -> {
                LockOutcome result = lock.releaseChecked(io);
                round.countDown();
                return result;
            });
            String uri = path.toUri().toString();
            ConnectionLease first = context.borrowForStream();
            FileObject file = ((FileSystemHandler) first.getConnection()).resolveFile(uri);
            HeldLock handle = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
            StreamLease lease = StreamLease.active(context, uri, "application/octet-stream", 1,
                    file.getContent().getLastModifiedTime(), first, file, handle);
            context.register(lease);
            try (InputStream initial = lease.getInputStream()) {
                Assert.assertEquals(initial.read(), 13);
                Assert.assertEquals(initial.read(), -1);
            }
            Assert.assertEquals(lease.nextDeadline(0), IDLE);
            String sentinel = uri + ".tick";
            HeldLock sentinelHandle = context.getShareLock().enterLocalOnly(sentinel, 1, 0).handle;
            context.register(StreamLease.replay(context, sentinel, 1, 0, sentinelHandle));
            time.set(IDLE - 1000);
            InputStream reopened = lease.getInputStream();
            Future<Integer> byteRead = readers.submit(() -> {
                pauseRead.set(true);
                return reopened.read();
            });
            Assert.assertTrue(reading.await(5, TimeUnit.SECONDS));
            time.set(IDLE + 1000);
            context.wakeReaper();
            await(round);
            Assert.assertEquals(lease.state(), StreamLease.State.ACTIVE);
            Assert.assertNull(lease.lend());
            finishRead.countDown();
            Assert.assertEquals(byteRead.get(5, TimeUnit.SECONDS).intValue(), 13);
            Assert.assertEquals(reopened.read(), -1);
            reopened.close();
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            Assert.assertEquals(lease.nextDeadline(time.get()), 121000L);
            StreamLease.Lending loan = lease.lend();
            Assert.assertNotNull(loan);
            Assert.assertSame(loan.lock(), handle);
            loan.removed(null);
            Assert.assertEquals(pool(first).getNumActive(), 0);
        } finally {
            finishRead.countDown();
            readers.shutdownNow();
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void lendingAndExpiryEachWinOnTheirSideOfTheReaperBoundary() throws Exception {
        for (boolean lenderFirst : new boolean[]{true, false}) {
            AtomicLong time = new AtomicLong();
            AtomicInteger clockReads = new AtomicInteger();
            AtomicInteger releases = new AtomicInteger();
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
                ConnectionContext context = virtualContext(files, () -> {
                    long now = time.get();
                    if (lenderFirst && now > IDLE && Thread.currentThread().getName().startsWith("File stream reaper")
                            && clockReads.incrementAndGet() == 2) {
                        // The first clock read chose the due set. The second is outside the monitor, before expiry.
                        held.countDown();
                        await(resume);
                    }
                    return now;
                }, (lock, io) -> {
                    releases.incrementAndGet();
                    held.countDown();
                    await(resume);
                    return lock.releaseChecked(io);
                });
                String path = "file:///stream/" + files.name;
                HeldLock lock = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
                StreamLease lease = StreamLease.replay(context, path, 1, 0, lock);
                context.register(lease);
                Thread worker = reaper(context);
                time.set(IDLE + 1000);
                context.wakeReaper();
                await(held);
                if (lenderFirst) {
                    StreamLease.Lending loan = lease.lend();
                    Assert.assertNotNull(loan);
                    resume.countDown();
                    lease.expire(time.get());
                    Assert.assertEquals(lease.state(), StreamLease.State.LENT);
                    loan.removed(null);
                    Assert.assertEquals(releases.get(), 0);
                } else {
                    Assert.assertEquals(lease.reason(), StreamLease.Reason.IDLE_LIMIT);
                    Assert.assertNull(lease.lend());
                    Assert.assertEquals(context.getShareLock().enterLocalOnly(path, 1, 0).kind,
                            LockOutcome.Kind.LOCK_BUSY);
                    time.addAndGet(5000);
                    resume.countDown();
                }
                worker.join(5000);
                Assert.assertFalse(worker.isAlive());
                Assert.assertEquals(releases.get(), lenderFirst ? 0 : 1);
                HeldLock next = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
                Assert.assertNotNull(next);
                next.abandon();
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    public void retireAndExpiryDisposeOnlyTheirLocalFileStream() throws Exception {
        Path path = Files.createTempFile("lease-dispose", ".bin");
        Files.write(path, new byte[]{17});
        Path descriptors = Paths.get("/proc/self/fd");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL");
             InputStream independent = Files.newInputStream(path)) {
            ConnectionContext context = files.factory.getContext();
            for (boolean retiring : new boolean[]{false, true}) {
                String uri = path.toUri().toString();
                ConnectionLease core = context.borrowForStream();
                FileObject file = ((FileSystemHandler) core.getConnection()).resolveFile(uri);
                StreamLease lease = StreamLease.active(context, uri, "binary", 1, 0, core, file, null);
                context.register(lease);
                InputStream reader = lease.getInputStream();
                Assert.assertEquals(reader.read(), 17);
                if (Files.isDirectory(descriptors)) {
                    Assert.assertEquals(descriptorCount(descriptors, path), 2L);
                }
                if (retiring) {
                    files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                } else {
                    lease.expire(Long.MAX_VALUE);
                }
                awaitPoolIdle(pool(core));
                if (Files.isDirectory(descriptors)) {
                    Assert.assertEquals(descriptorCount(descriptors, path), 1L);
                }
                Assert.assertEquals(pool(core).getNumActive(), 0);
                try {
                    reader.close();
                    Assert.fail("Late close lost the terminal reason");
                } catch (IOException ended) {
                    Assert.assertTrue(ended.getMessage().contains(retiring ? "retired" : "idle"));
                }
            }
            Assert.assertEquals(independent.read(), 17);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void awaitPoolIdle(ConnectionPool pool) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getNumActive() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Assert.assertEquals(pool.getNumActive(), 0);
    }

    @Test
    public void parkedLocalDisposalsDrainTheirOriginalLoansAfterExpiryAndRedeploy() throws Exception {
        for (boolean retire : new boolean[]{false, true}) {
            CountDownLatch parked = new CountDownLatch(2);
            CountDownLatch resume = new CountDownLatch(1);
            CountDownLatch replayReleased = new CountDownLatch(1);
            AtomicLong clock = new AtomicLong();
            AtomicInteger closes = new AtomicInteger();
            Set<Thread> disposers = ConcurrentHashMap.newKeySet();
            Path folder = Files.createTempDirectory("lease-parked");
            List<Path> paths = new ArrayList<>();
            List<StreamLease> leases = new ArrayList<>();
            List<InputStream> readers = new ArrayList<>();
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "maxActiveConnections", "8")) {
                ConnectionContext context = virtualContext(files, clock::get, (lock, io) -> {
                    LockOutcome result = lock.releaseChecked(io);
                    replayReleased.countDown();
                    return result;
                });
                ConnectionPool original = null;
                for (int index = 0; index < 3; index++) {
                    Path path = Files.write(folder.resolve("input" + index), new byte[]{41});
                    paths.add(path);
                    String uri = path.toUri().toString();
                    ConnectionLease core = context.borrowForStream();
                    original = pool(core);
                    FileObject file = ((FileSystemHandler) core.getConnection()).resolveFile(uri);
                    InputStream controlled = new FilterInputStream(file.getContent().getInputStream()) {
                        @Override
                        public void close() throws IOException {
                            disposers.add(Thread.currentThread());
                            closes.incrementAndGet();
                            parked.countDown();
                            await(resume);
                            super.close();
                        }
                    };
                    FileObject local = new DecoratedFileObject(file) {
                        @Override
                        public FileContent getContent() throws FileSystemException {
                            return fileWithStream(controlled).getContent();
                        }
                    };
                    StreamLease lease = StreamLease.active(context, uri, "binary", 1, 0, core, local, null);
                    context.register(lease);
                    leases.add(lease);
                    readers.add(lease.getInputStream());
                }
                String uri = folder.resolve("replay").toUri().toString();
                HeldLock lock = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
                StreamLease other = StreamLease.replay(context, uri, 0, 0, lock);
                context.register(other);
                Thread reaperThread = reaper(context);
                ConnectionLease replacement = null;
                try {
                    if (retire) {
                        replacement = files.redeploy().getContext().borrowForStream();
                        Assert.assertNotSame(pool(replacement), original);
                    } else {
                        clock.set(IDLE);
                        context.wakeReaper();
                        await(replayReleased);
                    }
                    reaperThread.join(5000);
                    Assert.assertFalse(reaperThread.isAlive());
                    await(parked);
                    Assert.assertEquals(original.getNumActive(), 3);
                    Assert.assertEquals(closes.get(), 2);
                    Assert.assertEquals(disposers.size(), 2);
                    Assert.assertEquals(other.state(), StreamLease.State.ENDED);
                    for (StreamLease lease : leases) {
                        Assert.assertEquals(lease.reason(), retire ? StreamLease.Reason.RETIRED : StreamLease.Reason.IDLE_LIMIT);
                    }
                    resume.countDown();
                    awaitPoolIdle(original);
                    Assert.assertEquals(original.getNumIdle(), retire ? 0 : 1);
                    Assert.assertEquals(closes.get(), 3);
                    if (replacement != null) {
                        Assert.assertEquals(pool(replacement).getNumActive(), 1);
                    }
                    for (int index = 0; index < readers.size(); index++) {
                        if (Files.isDirectory(Paths.get("/proc/self/fd"))) {
                            Assert.assertEquals(descriptorCount(Paths.get("/proc/self/fd"), paths.get(index)), 0L);
                        }
                        try {
                            readers.get(index).close();
                            Assert.fail("Retained reader reopened its terminal lease");
                        } catch (IOException ended) {
                            Assert.assertTrue(ended.getMessage().contains(retire ? "retired" : "idle"));
                        }
                    }
                    for (Thread disposer : disposers) {
                        disposer.join(5000);
                        Assert.assertFalse(disposer.isAlive());
                    }
                } finally {
                    resume.countDown();
                    if (replacement != null) {
                        replacement.returnToPool();
                    }
                }
            } finally {
                resume.countDown();
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
                Files.deleteIfExists(folder);
            }
        }
    }

    private static long descriptorCount(Path directory, Path file) throws IOException {
        Path real = file.toRealPath();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(fd -> {
                try {
                    return real.equals(Files.readSymbolicLink(fd));
                } catch (IOException alreadyClosed) {
                    return false;
                }
            }).count();
        }
    }

    @Test
    public void retiringInsideLocalCallsLeavesOneCloseToTheCaller() throws Exception {
        for (String stage : new String[]{"open", "read", "eof", "error", "close"}) {
            localCallRetirement(stage);
        }
    }

    private static void localCallRetirement(String stage) throws Exception {
        Path path = Files.createTempFile("lease-race", ".bin");
        Files.write(path, new byte[]{23});
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease core = context.borrowForStream();
            String uri = path.toUri().toString();
            FileObject file = ((FileSystemHandler) core.getConnection()).resolveFile(uri);
            try (InputStream descriptor = file.getContent().getInputStream()) {
                InputStream controlled = new FilterInputStream(descriptor) {
                    @Override
                    public int read() throws IOException {
                        paused.countDown();
                        await(resume);
                        if ("error".equals(stage)) {
                            throw new IOException("The local read failed");
                        }
                        return "eof".equals(stage) ? -1 : super.read();
                    }

                    @Override
                    public void close() throws IOException {
                        Assert.assertEquals(closes.incrementAndGet(), 1);
                        if ("close".equals(stage)) {
                            paused.countDown();
                            await(resume);
                        }
                        super.close();
                    }
                };
                FileObject decorated = new DecoratedFileObject(file) {
                    @Override
                    public FileContent getContent() throws FileSystemException {
                        if ("open".equals(stage)) {
                            paused.countDown();
                            await(resume);
                        }
                        return fileWithStream(controlled).getContent();
                    }
                };
                StreamLease lease = StreamLease.active(context, uri, "binary", 1, 0, core, decorated, null);
                context.register(lease);
                Future<?> operation = worker.submit(() -> {
                    InputStream reader = lease.getInputStream();
                    if ("close".equals(stage)) {
                        reader.close();
                    } else if (!"open".equals(stage)) {
                        reader.read();
                    }
                    return null;
                });
                await(paused);
                files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                Assert.assertEquals(pool(core).getNumActive(), 1);
                Assert.assertEquals(closes.get(), "close".equals(stage) ? 1 : 0);
                resume.countDown();
                try {
                    operation.get(5, TimeUnit.SECONDS);
                    Assert.fail("The retired call completed successfully at " + stage);
                } catch (ExecutionException rejected) {
                    Assert.assertTrue(rejected.getCause() instanceof IOException);
                    Assert.assertTrue(rejected.getCause().getMessage().contains("retired"));
                }
                Assert.assertEquals(closes.get(), 1, stage);
                Assert.assertEquals(pool(core).getNumActive(), 0);
            }
        } finally {
            resume.countDown();
            worker.shutdownNow();
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void finalLendingReleaseReportsTransportToTheBorrowingOperation() throws Exception {
        for (boolean retired : new boolean[]{false, true}) {
            for (boolean transport : new boolean[]{false, true}) {
                finalRelease(retired, transport);
            }
        }
    }

    private static void finalRelease(boolean retired, boolean transport) throws Exception {
        Path path = Files.createTempFile("lease-release", ".bin");
        Files.write(path, new byte[]{31});
        Path marker = Paths.get(path + ".lock");
        Path copy = path.resolveSibling(path.getFileName() + ".copy");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease operation = context.borrowForStream();
            ConnectionLease independent = context.borrowForStream();
            ConnectionPool pool = pool(operation);
            FileSystemHandler handler = (FileSystemHandler) operation.getConnection();
            DefaultFileSystemManager manager = (DefaultFileSystemManager) handler.getFsManager();
            AtomicInteger finalCalls = new AtomicInteger();
            AtomicBoolean finalRelease = new AtomicBoolean();
            manager.addProvider("releasecheck", new DefaultLocalFileProvider() {
                @Override
                public FileObject findFile(FileObject base, String uri, FileSystemOptions options)
                        throws FileSystemException {
                    if (finalRelease.get()) {
                        finalCalls.incrementAndGet();
                        if (transport) {
                            FileSystemException lost = new FileSystemException("The final lock read lost the connection");
                            lost.setFault(Fault.TRANSPORT);
                            throw lost;
                        }
                    }
                    return manager.resolveFile("file" + uri.substring("releasecheck".length()), options);
                }
            });
            String uri = "releasecheck" + path.toUri().toString().substring("file".length());
            try {
                HeldLock lock = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
                Assert.assertEquals(lock.lockShare(StreamLease.io(handler), 1, 0).kind, LockOutcome.Kind.ACQUIRED);
                StreamLease lease = StreamLease.replay(context, uri, 1, 0, lock);
                context.register(lease);
                StreamLease.Lending prior = lease.lend();
                Assert.assertNull(prior.giveBack(handler));
                StreamLease.Lending token = lease.lend();
                FileObject source = handler.resolveFile(path.toUri().toString());
                if (retired) {
                    try (InputStream data = source.getContent().getInputStream()) {
                        Assert.assertEquals(Files.copy(data, copy), 1L);
                    }
                    files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                } else {
                    Assert.assertTrue(source.delete());
                }
                finalRelease.set(true);
                Assert.assertNull(prior.removed(handler));
                Assert.assertNull(prior.giveBack(handler));
                Assert.assertEquals(finalCalls.get(), 0);
                LockOutcome result = retired ? token.giveBack(handler) : token.removed(handler);
                Assert.assertEquals(lease.reason(), retired ? StreamLease.Reason.RETIRED : StreamLease.Reason.REMOVED);
                Assert.assertEquals(result.kind, transport ? LockOutcome.Kind.LEFT : LockOutcome.Kind.RELEASED);
                boolean invalidate = result.classification != null && result.classification.getKind().isTransport();
                Assert.assertEquals(invalidate, transport);
                Assert.assertEquals(Files.exists(marker), transport);
                Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
                int calls = finalCalls.get();
                Assert.assertTrue(calls > 0);
                Assert.assertNull(token.removed(handler));
                Assert.assertNull(token.giveBack(handler));
                Assert.assertEquals(finalCalls.get(), calls);
                Assert.assertEquals(pool.getNumActive(), 2);
                // The operation frame uses this result to return or invalidate the handler.
                if (invalidate) {
                    operation.invalidate();
                } else {
                    operation.returnToPool();
                }
                Assert.assertEquals(pool.getNumActive(), 1);
                Assert.assertEquals(pool.getNumIdle(), transport ? 0 : 1);
                FileSystemHandler other = (FileSystemHandler) independent.getConnection();
                Assert.assertEquals(other.resolveFile(path.toUri().toString()).exists(), retired);
                if (retired) {
                    Assert.assertEquals(Files.readAllBytes(copy), new byte[]{31});
                }
            } finally {
                independent.returnToPool();
            }
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void retirementAfterGiveBackAbandonsWithoutUsingTheReturnedHandler() throws Exception {
        Path data = Files.createTempFile("lease-return", ".bin");
        Path marker = Paths.get(data + ".lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease operation = context.borrowForStream();
            ConnectionPool pool = pool(operation);
            FileSystemHandler handler = (FileSystemHandler) operation.getConnection();
            String uri = data.toUri().toString();
            HeldLock lock = context.getShareLock().enterLocalOnly(uri, 1, 0).handle;
            Assert.assertEquals(lock.lockShare(StreamLease.io(handler), 1, 0).kind, LockOutcome.Kind.ACQUIRED);
            StreamLease lease = StreamLease.replay(context, uri, 0, 0, lock);
            context.register(lease);
            StreamLease.Lending token = lease.lend();
            Assert.assertNull(token.giveBack(handler));
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            operation.returnToPool();
            files.factory.beforePoolClose(PoolCloseReason.RETIRE);
            Assert.assertEquals(lease.reason(), StreamLease.Reason.RETIRED);
            Assert.assertTrue(Files.exists(marker));
            Assert.assertNull(token.removed(handler));
            Assert.assertEquals(lock.abandon().kind, LockOutcome.Kind.ENDED);
            Assert.assertEquals(pool.getNumActive(), 0);
            Assert.assertEquals(pool.getNumIdle(), 1);
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(data);
        }
    }

    @Test
    public void locklessLendingSettlesWithoutTouchingTheOperationHandler() throws Exception {
        for (boolean retiring : new boolean[]{false, true}) {
            try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
                ConnectionContext context = files.factory.getContext();
                ConnectionLease reading = context.borrowForStream();
                StreamLease lease = StreamLease.active(context, "file:///no-lock", "binary", 0, 0, reading,
                        fileWithStream(new ByteArrayInputStream(new byte[0])), null);
                context.register(lease);
                lease.getInputStream().close();
                StreamLease.Lending loan = lease.lend();
                Assert.assertNull(loan.lock());
                ConnectionLease operation = context.borrowForStream();
                FileSystemHandler handler = (FileSystemHandler) operation.getConnection();
                if (retiring) {
                    files.factory.beforePoolClose(PoolCloseReason.RETIRE);
                }
                Assert.assertNull(retiring ? loan.giveBack(handler) : loan.removed(handler));
                Assert.assertEquals(lease.reason(), retiring ? StreamLease.Reason.RETIRED : StreamLease.Reason.REMOVED);
                Assert.assertEquals(pool(operation).getNumActive(), 1);
                operation.returnToPool();
                Assert.assertEquals(pool(operation).getNumActive(), 0);
            }
        }
    }

    static void recordRuntimeDependencies() throws Exception {
        if (System.getProperty("stream.unit.run") == null) {
            return;
        }
        recordDependency("connector-core.jar", ConnectionLease.class);
        recordDependency("fork.jar", FileObject.class);
        recordDependency("shared.jar", HeldLock.class);
    }

    private static void recordDependency(String role, Class<?> loaded) throws Exception {
        System.out.println("streamRuntime=" + role + "\t" + Paths.get(
                loaded.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }

    private static void await(CountDownLatch signal) {
        try {
            Assert.assertTrue(signal.await(5, TimeUnit.SECONDS), "The controlled participant did not resume");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static ConnectionPool pool(ConnectionLease core) throws ReflectiveOperationException {
        Field field = ConnectionLease.class.getDeclaredField("pool");
        field.setAccessible(true);
        return (ConnectionPool) field.get(core);
    }

    static Thread reaper(ConnectionContext context) throws ReflectiveOperationException {
        Field reaper = ConnectionContext.class.getDeclaredField("reaper");
        reaper.setAccessible(true);
        return (Thread) reaper.get(context);
    }
}
