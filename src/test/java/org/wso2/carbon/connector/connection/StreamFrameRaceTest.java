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
package org.wso2.carbon.connector.connection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.synapse.MessageContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.operations.FileFrameLeaseTest;
import org.wso2.carbon.connector.operations.FilePoolFixture;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Test(groups = "testbuild")
public class StreamFrameRaceTest {
    private static final long IDLE = 60000;

    @BeforeClass(alwaysRun = true)
    public void runtime() throws Exception {
        StreamLeaseTest.recordRuntimeDependencies();
    }

    private static String id(Object value) {
        return Integer.toUnsignedString(System.identityHashCode(value));
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void waitFor(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) {
                return;
            }
            Thread.sleep(2);
        }
        Assert.fail("The participant did not reach the required boundary");
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        void hold() {
            entered.countDown();
            try {
                Assert.assertTrue(released.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        void await() throws Exception { Assert.assertTrue(entered.await(12, TimeUnit.SECONDS)); }
        void open() { released.countDown(); }
    }

    private interface Action<T> { T run(Attempt attempt) throws Exception; }

    private interface Options { void apply(FileSystemHandler handler) throws Exception; }

    private static final class Bed implements AutoCloseable {
        final String label;
        final JsonObject facts = new JsonObject();
        final Path root;
        final Path directory;
        final String oldPoints;
        final FilePoolFixture files;
        final ConnectionContext context;
        final FileFrameLeaseTest.CountingPool pool;
        final MessageContext message = StreamLeaseTest.message(new HashMap<>());
        final AtomicLong now = new AtomicLong();
        final AtomicInteger reaperReads = new AtomicInteger();
        final Set<String> locks = ConcurrentHashMap.newKeySet();
        final List<String> released = Collections.synchronizedList(new ArrayList<>());
        final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        final ExecutorService workers = Executors.newCachedThreadPool();
        final Gate release = new Gate();
        final Gate clock = new Gate();
        final Gate reader = new Gate();
        volatile Object releaseManager;
        volatile long releaseEnd;
        volatile boolean blockRelease;
        volatile boolean blockReaper;
        volatile boolean blockRead;
        final AtomicReference<Thread> operating = new AtomicReference<>();
        String path;
        StreamLease lease;
        final AppenderSkeleton appender = new AppenderSkeleton() {
            protected void append(LoggingEvent event) {
                String line = event.getRenderedMessage();
                if (line.contains("streamIdleTimeout") && path != null && line.contains(path)) {
                    warnings.add(line);
                }
            }
            public void close() { }
            public boolean requiresLayout() { return false; }
        };

        Bed(String label) throws Exception {
            this.label = label;
            String configured = System.getProperty("stream.race.evidence", "target/frame-races");
            directory = Files.createDirectories(Paths.get(configured).toAbsolutePath());
            root = Files.createTempDirectory(directory, label + "-");
            oldPoints = System.getProperty("vfs.testpoints.dir");
            System.setProperty("vfs.testpoints.dir", root.toString());
            TestPoints.copy("connector", StreamLease.class, EndpointHealth.class);
            files = new FilePoolFixture("LOCAL", now::get, "suspendInitialDuration", "1000");
            context = StreamLeaseTest.virtualContext(files, () -> {
                if (blockRead && Thread.currentThread().getName().equals("frame-reader")) {
                    blockRead = false;
                    reader.hold();
                }
                if (blockReaper && Thread.currentThread().getName().startsWith("File stream reaper")
                        && reaperReads.incrementAndGet() == 2) {
                    clock.hold();
                }
                return now.get();
            }, (handle, io) -> {
                try {
                    releaseManager = field(io, "manager");
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                released.add(id(handle));
                if (blockRelease) {
                    release.hold();
                }
                now.addAndGet(2000);
                LockOutcome result = handle.releaseChecked(io);
                releaseEnd = now.get();
                return result;
            });
            pool = FileFrameLeaseTest.install(files);
            facts.addProperty("id", label);
            facts.addProperty("testRun", System.getProperty("stream.unit.run", "local"));
            Logger.getRootLogger().addAppender(appender);
        }

        <T> T run(String uri, OperationFrame.Use use, OperationFrame.Lock mode, Action<T> action)
                throws OperationFrame.Failure {
            return run(uri, use, mode, 0, handler -> { }, action);
        }

        <T> T run(String uri, OperationFrame.Use use, OperationFrame.Lock mode, int retries, Options options,
                Action<T> action) throws OperationFrame.Failure {
            OperationFrame.Operation<T> operation = new OperationFrame.Operation<T>() {
                public LinkedHashMap<String, OperationFrame.Lock> paths() {
                    LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
                    paths.put(uri, mode);
                    return paths;
                }
                public OperationFrame.Use use(String ignored) { return use; }
                public OperationFrame.Retry retry() { return OperationFrame.Retry.READ; }
                public T removed(String ignored) { throw new AssertionError(uri); }
                public void options(FileSystemHandler handler) throws Exception { options.apply(handler); }
                public T steps(Attempt attempt) throws Exception {
                    HeldLock lock = attempt.lock(uri);
                    if (lock != null) {
                        locks.add(id(lock));
                    }
                    return action.run(attempt);
                }
            };
            return OperationFrame.run(message, files.name, retries, operation);
        }

        StreamLease publish(String name, boolean streaming) throws Exception {
            Path source = Files.write(root.resolve(name), new byte[]{23});
            return read(source.toUri().toString(), streaming, handler -> { });
        }

        StreamLease read(String uri, boolean streaming, Options options) throws Exception {
            return run(uri, OperationFrame.Use.READ, OperationFrame.Lock.OWN, 0, options, attempt -> {
                FileObject file = attempt.handler().resolveFile(uri);
                facts.addProperty("readLock", id(attempt.lock(uri)));
                return attempt.publishRead(message, uri, streaming ? file : null, "application/octet-stream",
                        1, file.getContent().getLastModifiedTime());
            });
        }

        void setup() throws Exception {
            lease = publish("file.bin", true);
            path = lease.getName();
            facts.addProperty("path", path);
            try (InputStream stream = lease.getInputStream()) {
                Assert.assertEquals(stream.read(), 23);
                Assert.assertEquals(stream.read(), -1);
            }
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
        }

        <T> Future<T> worker(String name, Callable<T> work) {
            return workers.submit(() -> {
                Thread.currentThread().setName(name);
                if (name.equals("frame-operation")) {
                    operating.set(Thread.currentThread());
                }
                return work.call();
            });
        }

        void awaitMethod(String method) throws Exception {
            waitFor(() -> operating.get() != null && Arrays.stream(operating.get().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals(method)));
        }

        int queue() throws Exception {
            Object mutex = field(context, "leases");
            synchronized (mutex) {
                return ((Deque<?>) field(context, "releases")).size();
            }
        }

        void idleFacts() {
            facts.addProperty("reason", lease.reason().name());
            facts.addProperty("warnCount", warnings.size());
            facts.addProperty("warnText", String.join("\n", warnings));
            facts.addProperty("enqueueCount", released.size());
            if (!released.isEmpty()) {
                facts.addProperty("queuedLock", released.get(0));
            }
        }

        Map<String, JsonArray> endEvents() throws IOException {
            Map<String, JsonArray> ends = new HashMap<>();
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path file : tree.filter(p -> p.getFileName().toString().equals("events.log"))
                        .collect(Collectors.toList())) {
                    for (String line : Files.readAllLines(file)) {
                        List<String> fields = Arrays.asList(line.split("\t"));
                        if (fields.contains("point=lock.handle-end") && fields.contains("key=" + files.name)) {
                            String handle = fields.stream().filter(f -> f.startsWith("handle=")).findFirst().get();
                            String way = fields.stream().filter(f -> f.startsWith("way=")).findFirst().get();
                            ends.computeIfAbsent(handle.substring(7), k -> new JsonArray()).add(way.substring(4));
                        }
                    }
                }
            }
            return ends;
        }

        public void close() throws Exception {
            try {
                reader.open();
                release.open();
                clock.open();
                workers.shutdownNow();
                Assert.assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                Thread reaper = StreamLeaseTest.reaper(context);
                files.close();
                if (reaper != null) {
                    reaper.join(10000);
                    Assert.assertFalse(reaper.isAlive());
                }
                facts.addProperty("releaseEnd", releaseEnd);
                pool.inventory(facts);
                JsonArray acquired = new JsonArray();
                locks.forEach(acquired::add);
                facts.add("acquiredLocks", acquired);
                Map<String, JsonArray> observed = endEvents();
                JsonObject ends = new JsonObject();
                observed.forEach(ends::add);
                facts.add("lockFates", ends);
                Files.write(directory.resolve(label + ".json"), facts.toString().getBytes(StandardCharsets.UTF_8));
                Assert.assertEquals(observed.keySet(), locks);
                observed.values().forEach(values -> Assert.assertEquals(values.size(), 1));
                for (Map.Entry<String, JsonElement> row : facts.getAsJsonObject("loanFates").entrySet()) {
                    Assert.assertEquals(row.getValue().getAsJsonArray().size(), 1, row.getKey());
                }
            } finally {
                Logger.getRootLogger().removeAppender(appender);
                if (oldPoints == null) {
                    System.clearProperty("vfs.testpoints.dir");
                } else {
                    System.setProperty("vfs.testpoints.dir", oldPoints);
                }
            }
        }
    }

    public void operationWaitsForReader() throws Exception { readerFirst(true); }
    public void operationTimesOutForReader() throws Exception { readerFirst(false); }

    private void readerFirst(boolean eofFirst) throws Exception {
        try (Bed bed = new Bed(eofFirst ? "c1" : "c2")) {
            bed.setup();
            bed.now.set(50000);
            try (InputStream reopened = bed.lease.getInputStream()) {
                int active = bed.pool.getNumActive();
                bed.facts.addProperty("waitStart", bed.now.get());
                Future<String> operation = bed.worker("frame-operation", () -> {
                    try {
                        return bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, attempt -> {
                            bed.facts.addProperty("lendAt", bed.now.get());
                            bed.facts.addProperty("operationLock", id(attempt.lock(bed.path)));
                            return "ok";
                        });
                    } catch (OperationFrame.Failure failure) {
                        bed.facts.addProperty("code", Integer.parseInt(failure.error.getErrorCode()));
                        bed.facts.addProperty("waitEnd", bed.now.get());
                        return failure.error.getErrorDetail();
                    }
                });
                bed.awaitMethod("pause");
                Assert.assertEquals(bed.pool.getNumActive(), active);
                if (eofFirst) {
                    bed.now.set(54000);
                    Assert.assertEquals(reopened.read(), 23);
                    Assert.assertEquals(reopened.read(), -1);
                    bed.facts.addProperty("eofAt", bed.now.get());
                    bed.now.set(55000);
                    Assert.assertEquals(operation.get(5, TimeUnit.SECONDS), "ok");
                    Assert.assertEquals(bed.facts.get("operationLock"), bed.facts.get("readLock"));
                } else {
                    bed.now.set(60000);
                    Assert.assertEquals(operation.get(5, TimeUnit.SECONDS), Error.FILE_LOCKING_ERROR.getErrorDetail());
                    Assert.assertEquals(reopened.read(), 23);
                    bed.facts.addProperty("eof", reopened.read() == -1);
                    Assert.assertTrue(bed.facts.get("eof").getAsBoolean());
                }
            }
        }
    }

    public void expiredSecondOpen() throws Exception { readerAfterBorrow(true); }
    public void operationWinsAfterBorrow() throws Exception { readerAfterBorrow(false); }

    private void readerAfterBorrow(boolean expire) throws Exception {
        Gate borrowed = new Gate();
        Gate lent = new Gate();
        try (Bed bed = new Bed(expire ? "b" : "c3")) {
            bed.setup();
            bed.now.set(50000);
            bed.pool.afterBorrow = handler -> {
                if (Thread.currentThread().getName().equals("frame-reader")) {
                    borrowed.hold();
                }
            };
            Future<String> open = bed.worker("frame-reader", () -> {
                try (InputStream unexpected = bed.lease.getInputStream()) {
                    return "open";
                } catch (IOException failure) {
                    bed.facts.addProperty("openMessage", failure.getMessage());
                    return "IOException";
                }
            });
            borrowed.await();
            Future<String> op = null;
            try {
                if (expire) {
                    bed.blockRelease = true;
                    bed.now.set(61000);
                    bed.context.wakeReaper();
                    bed.release.await();
                } else {
                    op = bed.worker("frame-operation", () -> bed.run(bed.path, OperationFrame.Use.SOURCE,
                            OperationFrame.Lock.GUARD, attempt -> {
                                lent.hold();
                                return "ok";
                            }));
                    lent.await();
                }
                int before = bed.pool.returns();
                borrowed.open();
                String outcome = open.get(5, TimeUnit.SECONDS);
                bed.facts.addProperty("openException", outcome);
                bed.facts.addProperty("returnedUnused", bed.pool.returns() - before);
                bed.facts.addProperty("stateAtReturn", bed.lease.state().name());
                bed.facts.addProperty("enqueueCount", bed.released.size());
                if (expire) {
                    JsonObject beforeLate = new JsonObject();
                    bed.pool.inventory(beforeLate);
                    int beforeLateReturn = bed.pool.returns();
                    try (InputStream unexpected = bed.lease.getInputStream()) {
                        Assert.fail("A late reader reopened the file");
                    } catch (IOException late) {
                        JsonObject afterLate = new JsonObject();
                        bed.pool.inventory(afterLate);
                        int borrows = afterLate.getAsJsonArray("borrowedLoans").size()
                                - beforeLate.getAsJsonArray("borrowedLoans").size();
                        bed.facts.addProperty("lateReaderBorrows", borrows);
                        bed.facts.addProperty("lateReaderReturns", bed.pool.returns() - beforeLateReturn);
                        Assert.assertEquals(borrows, 0);
                        Assert.assertEquals(bed.pool.returns(), beforeLateReturn);
                    }
                }
                Assert.assertEquals(outcome, "IOException");
                Assert.assertEquals(bed.pool.returns() - before, 1);
                if (expire) {
                    Assert.assertEquals(bed.released.size(), 1);
                } else {
                    Assert.assertEquals(bed.lease.state(), StreamLease.State.LENT);
                    lent.open();
                    Assert.assertEquals(op.get(5, TimeUnit.SECONDS), "ok");
                }
            } finally {
                borrowed.open();
                lent.open();
                bed.release.open();
            }
        }
    }

    public void readerInCall() throws Exception {
        try (Bed bed = new Bed("a")) {
            bed.setup();
            long old = bed.lease.nextDeadline(0);
            bed.facts.addProperty("oldDeadline", old);
            // A second due lease witnesses a full reaper round while the first call is held.
            bed.publish("sentinel", false);
            bed.now.set(old - 1000);
            try (InputStream stream = bed.lease.getInputStream()) {
                bed.blockRead = true;
                Future<Integer> read = bed.worker("frame-reader", stream::read);
                bed.reader.await();
                try {
                    bed.now.set(old + 1000);
                    bed.context.wakeReaper();
                    waitFor(() -> bed.released.size() == 1);
                    bed.facts.addProperty("stateAtRound", bed.lease.state().name());
                    bed.facts.addProperty("queueAtRound", bed.queue());
                } finally {
                    bed.reader.open();
                }
                Assert.assertEquals(read.get(5, TimeUnit.SECONDS).intValue(), 23);
                bed.facts.addProperty("eof", stream.read() == -1);
                bed.facts.addProperty("final", bed.lease.state().name());
                bed.facts.addProperty("newDeadline", bed.lease.nextDeadline(bed.now.get()));
                Assert.assertEquals(bed.lease.state(), StreamLease.State.REPLAY);
                Assert.assertTrue(bed.lease.nextDeadline(bed.now.get()) > old);
            }
        }
    }

    public void operationWinsBeforeReaperChange() throws Exception {
        try (Bed bed = new Bed("d1")) {
            bed.setup();
            bed.blockReaper = true;
            bed.now.set(61000);
            bed.context.wakeReaper();
            bed.clock.await();
            try {
                bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, attempt -> {
                    bed.clock.open();
                    bed.lease.expire(bed.now.get());
                    bed.facts.addProperty("final", bed.lease.state().name());
                    bed.facts.addProperty("releaseCount", bed.released.size());
                    Assert.assertEquals(bed.lease.state(), StreamLease.State.LENT);
                    Assert.assertEquals(bed.released.size(), 0);
                    return null;
                });
            } finally {
                bed.clock.open();
            }
        }
    }

    public void readerFindsIdle() throws Exception { queued(true); }
    public void reaperWinsBeforeLend() throws Exception { queued(false); }

    private void queued(boolean readerWins) throws Exception {
        try (Bed bed = new Bed(readerWins ? "c4" : "d2")) {
            bed.setup();
            bed.blockRelease = true;
            Path point = bed.root.resolve("connector.1/lease.before-lend.hold");
            if (readerWins) {
                bed.now.set(61000);
                try (InputStream unexpected = bed.lease.getInputStream()) {
                    Assert.fail("An expired reader opened the file");
                } catch (IOException expected) {
                    Assert.assertTrue(expected.getMessage().contains("idle"));
                }
                bed.release.await();
            } else {
                bed.blockReaper = true;
                bed.now.set(61000);
                bed.context.wakeReaper();
                bed.clock.await();
                Files.write(point, Collections.singleton("wait=20000"));
            }
            try {
                Future<String> operation = bed.worker("frame-operation", () -> {
                    try {
                        return bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, attempt -> {
                            bed.facts.addProperty("acquiredAt", bed.now.get());
                            bed.facts.addProperty("operationLock", id(attempt.lock(bed.path)));
                            return "ok";
                        });
                    } catch (OperationFrame.Failure failure) {
                        bed.facts.addProperty("code", Integer.parseInt(failure.error.getErrorCode()));
                        return failure.error.getErrorDetail();
                    }
                });
                if (!readerWins) {
                    bed.awaitMethod("hold");
                    bed.clock.open();
                    bed.release.await();
                    Files.delete(point);
                }
                bed.awaitMethod("acquireWithWait");
                bed.facts.addProperty("acquirePath", bed.path);
                bed.facts.addProperty("ownAcquireAttempts", 1);
                bed.facts.addProperty("waitStart", bed.now.get());
                if (!readerWins) {
                    bed.now.addAndGet(5000);
                    bed.release.open();
                }
                String result = operation.get(14, TimeUnit.SECONDS);
                bed.facts.addProperty("acquireOutcome", Error.FILE_LOCKING_ERROR.getErrorDetail().equals(result)
                        ? "LOCK_BUSY" : result);
                bed.idleFacts();
                Assert.assertEquals(result, readerWins ? Error.FILE_LOCKING_ERROR.getErrorDetail() : "ok");
                Assert.assertEquals(bed.warnings.size(), 1);
            } finally {
                Files.deleteIfExists(point);
                bed.release.open();
            }
        }
    }

    public void retireDuringRelease() throws Exception {
        try (Bed bed = new Bed("e")) {
            StreamLease first = bed.publish("first", false);
            StreamLease second = bed.publish("second", false);
            bed.blockRelease = true;
            bed.now.set(IDLE + 1);
            bed.context.wakeReaper();
            bed.release.await();
            String releaseLock = bed.released.get(0);
            bed.facts.addProperty("oldPool", id(bed.pool));
            Assert.assertEquals(bed.pool.getNumActive(), 1);
            bed.facts.addProperty("releasePool", bed.pool.ownsManager(bed.releaseManager) ? id(bed.pool) : null);
            bed.facts.addProperty("queueBeforeRetire", bed.queue());
            Thread worker = StreamLeaseTest.reaper(bed.context);
            try {
                bed.files.close();
                bed.facts.addProperty("queueAfterRetire", bed.queue());
                Assert.assertTrue(bed.pool.isClosed());
            } finally {
                bed.release.open();
            }
            worker.join(10000);
            Assert.assertFalse(worker.isAlive());
            bed.facts.addProperty("returnedToClosed", bed.pool.returnedToClosed);
            Set<?> tracked = (Set<?>) field(bed.context, "handlers");
            boolean destroyed = bed.pool.closedHandler != null && !tracked.contains(bed.pool.closedHandler);
            bed.facts.addProperty("destroyed", destroyed ? 1 : 0);
            Map<String, JsonArray> ends = bed.endEvents();
            String other = bed.locks.stream().filter(lock -> !lock.equals(releaseLock)).findFirst().get();
            JsonArray fates = ends.get(other);
            int abandoned = 0;
            int released = 0;
            for (JsonElement fate : fates) {
                if ("abandon".equals(fate.getAsString())) {
                    abandoned++;
                } else if ("releaseChecked".equals(fate.getAsString())) {
                    released++;
                }
            }
            bed.facts.addProperty("secondAbandons", abandoned);
            bed.facts.addProperty("secondReleases", released);
            Assert.assertEquals(first.reason(), StreamLease.Reason.IDLE_LIMIT);
            Assert.assertEquals(second.reason(), StreamLease.Reason.IDLE_LIMIT);
            Assert.assertTrue(destroyed);
            Assert.assertEquals(abandoned, 1);
            Assert.assertEquals(released, 0);
        }
    }

    public void losingLendAfterBorrowCancelsOnlyTheUnusedAdmission() throws Exception {
        for (boolean trial : new boolean[]{false, true}) {
            Gate readerBorrow = new Gate();
            try (Bed bed = new Bed(trial ? "postborrow-trial" : "postborrow-closed")) {
                bed.setup();
                bed.now.set(10000);
                bed.pool.afterBorrow = handler -> {
                    if (Thread.currentThread().getName().equals("frame-reader")) {
                        readerBorrow.hold();
                    }
                };
                Future<InputStream> reader = bed.worker("frame-reader", bed.lease::getInputStream);
                readerBorrow.await();
                Path hold = bed.root.resolve("connector.1/lease.before-lend.hold");
                try {
                    if (trial) {
                        bed.pool.getBreaker().admit().reportFailure();
                        bed.now.addAndGet(1000);
                    }
                    Files.write(hold, Collections.singleton("wait=15000"));
                    JsonObject before = new JsonObject();
                    bed.pool.inventory(before);
                    int returns = bed.pool.returns();
                    long start = bed.now.get();
                    Future<Error> operation = bed.worker("frame-operation", () -> {
                        try {
                            bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, attempt -> {
                                throw new AssertionError("The losing caller reached file steps");
                            });
                            throw new AssertionError("The losing caller returned success");
                        } catch (OperationFrame.Failure failure) {
                            return failure.error;
                        }
                    });
                    bed.awaitMethod("hold");
                    readerBorrow.open();
                    try (InputStream stream = reader.get(5, TimeUnit.SECONDS)) {
                        Assert.assertEquals(bed.lease.state(), StreamLease.State.ACTIVE);
                        Files.delete(hold);
                        Error error = operation.get(5, TimeUnit.SECONDS);
                        JsonObject after = new JsonObject();
                        bed.pool.inventory(after);
                        int borrowed = after.getAsJsonArray("borrowedLoans").size()
                                - before.getAsJsonArray("borrowedLoans").size();
                        bed.facts.addProperty("operationBorrows", borrowed);
                        bed.facts.addProperty("operationReturns", bed.pool.returns() - returns);
                        bed.facts.addProperty("elapsed", bed.now.get() - start);
                        bed.facts.addProperty("code", Integer.parseInt(error.getErrorCode()));
                        Assert.assertEquals(error, Error.FILE_LOCKING_ERROR);
                        Assert.assertEquals(borrowed, 1);
                        Assert.assertEquals(bed.pool.returns() - returns, 1);
                        Assert.assertEquals(bed.now.get(), start);
                        Assert.assertEquals(bed.lease.state(), StreamLease.State.ACTIVE);
                        if (trial) {
                            CircuitBreaker.Admission next =
                                    bed.pool.getBreaker().admit();
                            Assert.assertTrue(next.isTrial());
                            next.cancelTrial();
                        }
                        Assert.assertEquals(stream.read(), 23);
                        Assert.assertEquals(stream.read(), -1);
                    }
                } finally {
                    readerBorrow.open();
                    Files.deleteIfExists(hold);
                }
            }
        }
    }

    public void retirementBarrierBelongsOnlyToTheWinningOperation() throws Exception {
        for (boolean atLend : new boolean[]{true, false}) {
            try (Bed bed = new Bed(atLend ? "retire-at-lend" : "retire-at-return")) {
                bed.setup();
                Path copy = bed.root.resolve("connector.1");
                Path end = copy.resolve("lease.after-retired-end.hold");
                Path before = copy.resolve("lease.after-lend.hold");
                // An ordinary replay succeeds without reaching the retired end barrier.
                bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, a -> null);
                Files.write(end, Collections.singleton("wait=15"));
                if (atLend) {
                    Files.write(before, Collections.singleton("wait=15"));
                }
                try {
                    Future<String> result = bed.worker("frame-operation", () -> {
                        try {
                            return bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, a -> {
                                Assert.assertFalse(atLend);
                                bed.files.factory.beforePoolClose(
                                        PoolCloseReason.RETIRE);
                                return "ok";
                            });
                        } catch (OperationFrame.Failure failure) {
                            return failure.error.getErrorDetail();
                        }
                    });
                    if (atLend) {
                        waitFor(() -> reached(copy, "lease.after-lend").size() == 1);
                        bed.files.factory.beforePoolClose(
                                PoolCloseReason.RETIRE);
                        Files.delete(before);
                    }
                    waitFor(() -> reached(copy, "lease.after-retired-end").size() == 1);
                    String event = reached(copy, "lease.after-retired-end").get(0);
                    Assert.assertTrue(event.contains("\tkey=" + id(bed.lease) + "\t"), event);
                    Assert.assertTrue(event.contains("\tattempt=1"), event);
                    Assert.assertEquals(bed.lease.reason(), StreamLease.Reason.RETIRED);
                    Path marker = Paths.get(URI.create(bed.path + ".lock"));
                    Assert.assertEquals(Files.exists(marker), atLend);
                    Files.delete(end);
                    Assert.assertEquals(result.get(5, TimeUnit.SECONDS), atLend ? "FILE:CONNECTION_ERROR" : "ok");
                    Assert.assertNull(bed.lease.lend());
                    bed.lease.retire();
                    Assert.assertEquals(reached(copy, "lease.after-retired-end").size(), 1);
                } finally {
                    Files.deleteIfExists(before);
                    Files.deleteIfExists(end);
                }
            }
        }
    }

    private static List<String> reached(Path folder, String point) {
        try {
            return Files.readAllLines(folder.resolve("events.log")).stream()
                    .filter(line -> line.contains("\tpoint=hold-reached\t") && line.contains("\tat=" + point + "\t"))
                    .collect(Collectors.toList());
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void repeatedMemberUsesItsSingleVerifiedLeaseInTheAttempt() throws Exception {
        try (Bed bed = new Bed("repeated-member")) {
            bed.setup();
            Path copy = bed.root.resolve("connector.1");
            Path events = copy.resolve("events.log");
            Path hold = copy.resolve("frame.after-verify-owner.hold");
            List<String> baseline = Files.readAllLines(events);
            Files.write(hold, Collections.singleton("wait=15"));
            try {
                Future<Void> first = bed.worker("frame-operation", () -> bed.run(bed.path, OperationFrame.Use.SOURCE,
                        OperationFrame.Lock.GUARD, attempt -> {
                            Assert.assertEquals(attempt.guardMember(bed.path), OperationFrame.Member.READY);
                            Assert.assertEquals(attempt.guardMember(bed.path), OperationFrame.Member.READY);
                            return null;
                        }));
                waitFor(() -> reached(copy, "frame.after-verify-owner").size() == 1);
                String hit = reached(copy, "frame.after-verify-owner").get(0);
                Assert.assertTrue(hit.contains("\tkey=" + id(bed.lease) + "\t"), hit);
                Assert.assertTrue(hit.contains("\tattempt=1"), hit);
                Files.delete(hold);
                first.get(5, TimeUnit.SECONDS);
                bed.run(bed.path, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, a -> null);
                long calls = Files.readAllLines(events).stream().skip(baseline.size())
                        .filter(line -> line.contains("\tpoint=lock.verify-owner\t")).count();
                Assert.assertEquals(calls, 2L);
                Assert.assertEquals(bed.lease.state(), StreamLease.State.REPLAY);
            } finally {
                Files.deleteIfExists(hold);
            }
        }
    }

    public void transportErrorInVerifyOwnerRetriesOnAFreshHandlerAndTakesNoLock() throws Exception {
        try (Bed bed = new Bed("verify-transport")) {
            // The file and its lock are reached through a scheme of this test, so the first read of the lock in
            // the check of the owner can lose its connection.
            String scheme = "lockfault";
            AtomicBoolean fault = new AtomicBoolean();
            List<FileSystemHandler> handlers = Collections.synchronizedList(new ArrayList<>());
            Options faulty = handler -> {
                handlers.add(handler);
                DefaultFileSystemManager manager = (DefaultFileSystemManager) handler.getFsManager();
                if (!manager.hasProvider(scheme)) {
                    manager.addProvider(scheme, new DefaultLocalFileProvider() {
                        @Override
                        public FileObject findFile(FileObject base, String uri, FileSystemOptions options)
                                throws FileSystemException {
                            if (uri.endsWith(".lock") && fault.compareAndSet(true, false)) {
                                FileSystemException lost = new FileSystemException("The lock read lost the connection");
                                lost.setFault(Fault.TRANSPORT);
                                throw lost;
                            }
                            return manager.resolveFile("file" + uri.substring(scheme.length()), options);
                        }
                    });
                }
            };
            Path source = Files.write(bed.root.resolve("file.bin"), new byte[]{23});
            Path marker = Paths.get(source + ".lock");
            String uri = scheme + source.toUri().toString().substring("file".length());
            StreamLease lease = bed.read(uri, true, faulty);
            try (InputStream stream = lease.getInputStream()) {
                Assert.assertEquals(stream.read(), 23);
                Assert.assertEquals(stream.read(), -1);
            }
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            String readLock = bed.facts.get("readLock").getAsString();
            byte[] owner = Files.readAllBytes(marker);
            Path events = bed.root.resolve("connector.1/events.log");
            int baseline = Files.readAllLines(events).size();
            JsonObject before = new JsonObject();
            bed.pool.inventory(before);
            handlers.clear();
            List<Integer> steps = Collections.synchronizedList(new ArrayList<>());
            fault.set(true);
            String result = bed.run(uri, OperationFrame.Use.SOURCE, OperationFrame.Lock.GUARD, 1, faulty, attempt -> {
                steps.add(attempt.number);
                Assert.assertEquals(id(attempt.lock(uri)), readLock);
                return "ok";
            });
            List<String> answers = new ArrayList<>();
            List<String> lines = Files.readAllLines(events);
            for (String line : lines.subList(baseline, lines.size())) {
                List<String> fields = Arrays.asList(line.split("\t"));
                if (fields.contains("point=lock.verify-owner")) {
                    Assert.assertTrue(fields.contains("handle=" + readLock), line);
                    answers.add(fields.stream().filter(f -> f.startsWith("answer=")).findFirst().get().substring(7));
                }
            }
            JsonArray checks = new JsonArray();
            answers.forEach(checks::add);
            bed.facts.add("verifyAnswers", checks);
            JsonObject after = new JsonObject();
            bed.pool.inventory(after);
            JsonArray loans = after.getAsJsonArray("borrowedLoans");
            int first = before.getAsJsonArray("borrowedLoans").size();
            Assert.assertEquals(result, "ok");
            Assert.assertFalse(fault.get());
            // The first attempt failed in the check of the owner, so only the retry ran the steps.
            Assert.assertEquals(steps, Collections.singletonList(2));
            Assert.assertEquals(answers, Arrays.asList("CHECK_FAILED", "OURS"));
            Assert.assertEquals(handlers.size(), 2);
            Assert.assertNotSame(handlers.get(0), handlers.get(1));
            Assert.assertEquals(loans.size(), first + 2);
            Assert.assertEquals(after.getAsJsonObject("loanFates").get(loans.get(first).getAsString()).toString(),
                    "[\"invalidated\"]");
            Assert.assertEquals(after.getAsJsonObject("loanFates").get(loans.get(first + 1).getAsString()).toString(),
                    "[\"returned\"]");
            // No new lock was taken, and the lost reply did not end the handle. Closing the bed ends it once.
            Assert.assertEquals(Files.readAllBytes(marker), owner);
            Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            Assert.assertFalse(bed.endEvents().containsKey(readLock));
        }
    }
}
