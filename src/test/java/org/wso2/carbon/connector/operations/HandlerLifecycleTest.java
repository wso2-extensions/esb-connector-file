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

import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class HandlerLifecycleTest {
    @Test
    public void eachProtocolReturnsAndInvalidatesThroughItsFactory() throws Exception {
        for (String protocol : new String[]{"FTP", "FTPS", "LOCAL", "SFTP", "SMB2"}) {
            try (FilePoolFixture files = new FilePoolFixture(protocol)) {
                ConnectionConfiguration config = files.factory.getContext().getConfiguration();
                files.close();
                Map<Object, Integer> returns = new IdentityHashMap<>();
                Map<Object, Integer> invalidations = new IdentityHashMap<>();
                FileSystemHandlerFactory factory = new FileSystemHandlerFactory(config, "file", files.name) {
                    @Override
                    public void passivateObject(Object handler) {
                        super.passivateObject(handler);
                        returns.merge(handler, 1, Integer::sum);
                    }

                    @Override
                    public void destroyObject(Object handler) {
                        super.destroyObject(handler);
                        invalidations.merge(handler, 1, Integer::sum);
                    }
                };
                files.core.createConnection("file", files.name, factory, config.getConfiguration(), files.message);
                ConnectionLease success = files.borrow();
                ConnectionLease failed = files.borrow();
                FileSystemHandler kept = FilePoolFixture.handler(success);
                FileSystemHandler discarded = FilePoolFixture.handler(failed);
                Assert.assertNotSame(kept, discarded);
                Assert.assertTrue(factory.validateObject(kept));
                Assert.assertTrue(factory.validateObject(discarded));
                success.returnToPool();
                failed.invalidate();
                Assert.assertEquals(returns.size(), 1, protocol);
                Assert.assertEquals(invalidations.size(), 1, protocol);
                Assert.assertEquals(returns.get(kept), Integer.valueOf(1), protocol);
                Assert.assertEquals(invalidations.get(discarded), Integer.valueOf(1), protocol);
                Assert.assertFalse(((DefaultFileSystemManager) discarded.getFsManager()).isAliveLocally(), protocol);
                ConnectionLease again = files.borrow();
                try {
                    Assert.assertSame(again.getConnection(), kept, protocol);
                } finally {
                    again.returnToPool();
                }
            }
        }
    }

    @Test
    public void interruptedOwnerInvalidatesAfterItsTransportUnwinds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicLong writeThrew = new AtomicLong();
        AtomicLong destroyed = new AtomicLong();
        AtomicLong writerThread = new AtomicLong();
        AtomicLong destroyThread = new AtomicLong();
        AtomicReference<FileSystemHandler> active = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FilePoolFixture files = new FilePoolFixture("SFTP")) {
            ConnectionConfiguration config = files.factory.getContext().getConfiguration();
            files.close();
            FileSystemHandlerFactory observing = new FileSystemHandlerFactory(config, "file", files.name) {
                @Override
                public void destroyObject(Object object) {
                    Assert.assertSame(object, active.get());
                    Assert.assertTrue(writeThrew.get() != 0);
                    destroyThread.set(Thread.currentThread().getId());
                    super.destroyObject(object);
                    destroyed.set(System.nanoTime());
                }
            };
            files.core.createConnection("file", files.name, observing, config.getConfiguration(), files.message);
            Future<Void> writer = executor.submit(() -> {
                ConnectionLease lease = files.borrow();
                FileSystemHandler handler = FilePoolFixture.handler(lease);
                active.set(handler);
                writerThread.set(Thread.currentThread().getId());
                DefaultFileSystemManager manager = (DefaultFileSystemManager) handler.getFsManager();
                // The in-memory endpoint completes its pending write only when the manager cancels it.
                manager.newSocketGroup().add(new Socket() {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        closed.countDown();
                    }
                });
                entered.countDown();
                try {
                    if (!closed.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("the manager did not cancel the endpoint");
                    }
                    throw new SocketException("Closed test transport");
                } catch (SocketException expected) {
                    writeThrew.set(System.nanoTime());
                    lease.reportFailure();
                    lease.invalidate();
                }
                return null;
            });
            Assert.assertTrue(entered.await(3, TimeUnit.SECONDS));
            DefaultFileSystemManager manager = (DefaultFileSystemManager) active.get().getFsManager();
            long start = System.nanoTime();
            manager.abortAll();
            long returned = System.nanoTime();
            writer.get(3, TimeUnit.SECONDS);
            Assert.assertNotEquals(writerThread.get(), Thread.currentThread().getId());
            Assert.assertEquals(destroyThread.get(), writerThread.get());
            Assert.assertTrue(start <= writeThrew.get() && writeThrew.get() <= destroyed.get());
            Assert.assertTrue(returned - start <= TimeUnit.SECONDS.toNanos(2));
            Assert.assertTrue(destroyed.get() - start <= TimeUnit.SECONDS.toNanos(2));
            Assert.assertFalse(manager.isAliveLocally());
        } finally {
            closed.countDown();
            executor.shutdownNow();
        }
    }

    @Test(groups = "testbuild")
    public void checkedBorrowHoldsOnItsOwnConnection() throws Exception {
        Path target = Files.createDirectories(Paths.get("target", "points"));
        Path root = Files.createTempDirectory(target, "borrow-").toAbsolutePath();
        String old = System.getProperty("vfs.testpoints.dir");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            System.setProperty("vfs.testpoints.dir", root.toString());
            new FileConfig().init(files.message.getEnvironment());
            FileSystemHandler warm = Utils.getFileSystemHandler(files.name);
            files.core.returnConnection("file", files.name, warm);
            Path copy = root.resolve("connector.1");
            Assert.assertTrue(Files.isRegularFile(copy.resolve("copy")));
            Files.write(copy.resolve("local-check.after.hold"),
                    ("connection=" + files.name + "\n").getBytes(StandardCharsets.UTF_8));
            Future<Void> owner = executor.submit(() -> {
                FileSystemHandler handler = Utils.getFileSystemHandler(files.name);
                files.core.returnConnection("file", files.name, handler);
                return null;
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                Path reached = null;
                while (reached == null && System.nanoTime() < deadline) {
                    try (Stream<Path> paths = Files.list(copy)) {
                        reached = paths.filter(p -> p.getFileName().toString().startsWith("local-check.after@")
                                && p.getFileName().toString().endsWith(".reached")).findFirst().orElse(null);
                    }
                    if (reached == null) {
                        Thread.sleep(10);
                    }
                }
                Assert.assertNotNull(reached, "the real borrow did not reach the hold");
                Assert.assertTrue(new String(Files.readAllBytes(reached), StandardCharsets.UTF_8)
                        .contains("connection=" + files.name));
                Assert.assertFalse(owner.isDone());
                String filename = reached.getFileName().toString().replace(".reached", ".release");
                Files.createFile(copy.resolve(filename));
                owner.get(3, TimeUnit.SECONDS);
            } finally {
                Files.write(copy.resolve("release.all"), new byte[0]);
                executor.shutdown();
                Assert.assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            if (old == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", old);
            }
        }
    }
}
