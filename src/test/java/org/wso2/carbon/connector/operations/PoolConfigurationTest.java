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
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.integration.connector.core.connection.PoolBusyException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PoolConfigurationTest {
    @Test
    public void fileConfigPreservesDecimalProgression() throws Exception {
        AtomicLong time = new AtomicLong();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", time::get, "suspendProgressionFactor", "1.5",
                "suspendInitialDuration", "1000", "suspendMaximumDuration", "300000",
                "retriesBeforeSuspension", "0")) {
            ConnectionLease lease = files.borrow();
            try {
                Assert.assertEquals(FilePoolFixture.pool(lease).getConfiguration().getOpenDurationProgressFactorAsDouble(),
                        1.5, 0.0);
                lease.reportFailure();
            } finally {
                lease.returnToPool();
            }
            ConnectionContext context = files.factory.getContext();
            for (long duration : new long[]{1000, 1500, 2250}) {
                time.addAndGet(duration - 1);
                try {
                    context.borrowForStream();
                    Assert.fail("A stream borrowed before the open period ended");
                } catch (IOException expected) {
                    Assert.assertEquals(FilePoolFixture.pool(lease).getBreaker().getMode().name(), "OPEN");
                }
                time.incrementAndGet();
                try {
                    context.borrowForStream();
                    Assert.fail("A stream took a trial admission");
                } catch (IOException expected) {
                    Assert.assertTrue(expected.getMessage().contains("trial"));
                }
                ConnectionLease trial = files.borrow();
                try {
                    Assert.assertTrue(trial.isTrial());
                    trial.reportFailure();
                } finally {
                    trial.invalidate();
                }
                Assert.assertEquals(FilePoolFixture.pool(lease).getNumActive(), 0);
            }
        }
    }

    @Test
    public void defaultsOnAllThreePools() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "sftpPoolConnectionAgedTimeout", "200");
             Rotation rotations = new Rotation(files)) {
            for (ConnectionPool pool : rotations.pools) {
                Assert.assertEquals(pool.getMaxWait(), 10000L);
                Assert.assertTrue(pool.getTestOnBorrow());
                Assert.assertTrue(pool.getTestWhileIdle());
                Assert.assertEquals(pool.getTimeBetweenEvictionRunsMillis(), 60000L);
                Assert.assertEquals(pool.getMinEvictableIdleTimeMillis(), 300000L);
                Assert.assertEquals(pool.getNumTestsPerEvictionRun(), -1);
            }
        }
    }

    @Test
    public void borrowWaitOfZeroOrLessTakesTheDefault() throws Exception {
        for (String wait : new String[]{"0", "-1"}) {
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "maxWaitTime", wait)) {
                ConnectionLease lease = files.borrow();
                try {
                    Assert.assertEquals(FilePoolFixture.pool(lease).getMaxWait(), 10000L, "maxWaitTime=" + wait);
                } finally {
                    lease.returnToPool();
                }
            }
        }
    }

    @Test
    public void activeLeasesSurviveRotationsWithConnectorDefaults() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "sftpPoolConnectionAgedTimeout", "200");
             Rotation rotations = new Rotation(files)) {
            ConnectionContext owner = files.factory.getContext();
            Assert.assertFalse(owner.isClosed());
            Assert.assertNotSame(rotations.pools[0], rotations.pools[1]);
            Assert.assertNotSame(rotations.pools[1], rotations.pools[2]);
            for (int i = 0; i < 3; i++) {
                Assert.assertSame(rotations.pools[i].getConnectionFactory(), files.factory);
                Assert.assertSame(rotations.pools[i].getBreaker(), rotations.pools[0].getBreaker());
                Assert.assertSame(FilePoolFixture.handler(rotations.leases[i]).getContext(), owner);
                Assert.assertEquals(rotations.pools[i].getNumActive(), 1);
            }
            for (int i = 0; i < 2; i++) {
                FileSystemHandler old = FilePoolFixture.handler(rotations.leases[i]);
                rotations.release(i);
                Assert.assertFalse(((DefaultFileSystemManager) old.getFsManager()).isAliveLocally());
                Assert.assertEquals(rotations.pools[2].getNumActive(), 1);
            }
            rotations.pools[2].setStrat(Instant.now());
            ConnectionLease next = files.borrow();
            try (FileObject dir = FilePoolFixture.handler(next).resolveFile(new java.io.File(
                    System.getProperty("java.io.tmpdir")).toURI().toString())) {
                Assert.assertTrue(dir.exists());
                Assert.assertSame(FilePoolFixture.pool(next), rotations.pools[2]);
            } finally {
                next.returnToPool();
            }
        }
    }

    @Test
    public void rotationMakesNoRemoteValidationOrDestroyCalls() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch listening = new CountDownLatch(1);
            Thread observer = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        try (Socket accepted = server.accept()) {
                            attempts.incrementAndGet();
                            listening.countDown();
                        }
                    }
                } catch (SocketException e) {
                    if (!server.isClosed()) {
                        throw new AssertionError(e);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            observer.start();
            try {
                try (Socket control = new Socket(server.getInetAddress(), server.getLocalPort())) {
                    Assert.assertTrue(listening.await(2, TimeUnit.SECONDS));
                }
                try (FilePoolFixture files = new FilePoolFixture("SFTP", "port", String.valueOf(server.getLocalPort()),
                        "sftpPoolConnectionAgedTimeout", "200"); Rotation rotations = new Rotation(files)) {
                    for (int i = 0; i < 3; i++) {
                        Assert.assertTrue(files.factory.validateObject(rotations.leases[i].getConnection()));
                    }
                    rotations.release(0);
                    rotations.release(1);
                    rotations.release(2);
                    rotations.pools[2].evict();
                    Assert.assertEquals(rotations.pools[2].getNumIdle(), 1);
                }
                Assert.assertEquals(attempts.get(), 1, "Only the control connection should reach the server");
            } finally {
                server.close();
                observer.join(2000);
                Assert.assertFalse(observer.isAlive());
            }
        }
    }

    @Test
    public void localOperationIsLimitedByThePool() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "maxActiveConnections", "1", "maxWaitTime", "60")) {
            ConnectionLease held = files.borrow();
            long start = System.nanoTime();
            try {
                files.borrow();
                Assert.fail("Expected a busy pool");
            } catch (PoolBusyException expected) {
                Assert.assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(50));
            } finally {
                held.returnToPool();
            }
            ConnectionLease next = files.borrow();
            next.returnToPool();
        }
    }

    private static final class Rotation implements AutoCloseable {
        final ConnectionLease[] leases = new ConnectionLease[3];
        final ConnectionPool[] pools = new ConnectionPool[3];

        Rotation(FilePoolFixture files) throws Exception {
            try {
                for (int i = 0; i < 3; i++) {
                    if (i > 0) {
                        pools[i - 1].setStrat(Instant.EPOCH);
                    }
                    leases[i] = files.borrow();
                    pools[i] = FilePoolFixture.pool(leases[i]);
                }
            } catch (Exception | Error e) {
                close();
                throw e;
            }
        }

        void release(int i) {
            leases[i].returnToPool();
            leases[i] = null;
        }

        @Override
        public void close() {
            for (int i = 0; i < leases.length; i++) {
                if (leases[i] != null) {
                    release(i);
                }
            }
        }
    }
}
