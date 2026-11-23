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

import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

public class FileConsumerTest {
    @Test
    public void thresholdCountsOnlyReportedFailures() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "retriesBeforeSuspension", "1")) {
            ConnectionLease first = files.borrow();
            ConnectionPool pool = FilePoolFixture.pool(first);
            first.reportFailure();
            first.returnToPool();
            Assert.assertEquals(pool.getBreaker().getMode().name(), "CLOSED");
            ConnectionLease second = files.borrow();
            Assert.assertEquals(pool.getBreaker().getMode().name(), "CLOSED");
            second.reportFailure();
            second.invalidate();
            Assert.assertEquals(pool.getBreaker().getMode().name(), "OPEN");
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "retriesBeforeSuspension", "1")) {
            ConnectionContext context = files.factory.getContext();
            ConnectionLease first = context.borrowForStream();
            ConnectionLease second = context.borrowForStream();
            first.reportSuccess();
            second.reportSuccess();
            for (ConnectionLease core : new ConnectionLease[]{first, second}) {
                InputStream broken = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new SocketException("Connection lost after handover");
                    }
                };
                StreamLease lease = StreamLease.active(context, files.name, "application/octet-stream", 1, 0,
                        core, FilePoolFixture.fileWithStream(broken), null);
                context.publish(StreamLeases.forRead(files.message), lease, null);
                try {
                    lease.getInputStream().read();
                    Assert.fail("The transport failure was lost");
                } catch (SocketException expected) {
                    Assert.assertEquals(lease.reason(), StreamLease.Reason.TRANSPORT_ERROR);
                }
                Assert.assertEquals(FilePoolFixture.pool(core).getBreaker().getMode().name(),
                        core == first ? "CLOSED" : "OPEN");
            }
            Assert.assertEquals(FilePoolFixture.pool(first).getNumActive(), 0);
        }
    }

    @Test
    public void returnValidationRemainsIndependent() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionConfiguration config = files.factory.getContext().getConfiguration();
            files.close();
            AtomicInteger checks = new AtomicInteger();
            FileSystemHandlerFactory factory = new FileSystemHandlerFactory(config, "file", files.name) {
                @Override
                public boolean validateObject(Object value) {
                    checks.incrementAndGet();
                    return super.validateObject(value);
                }
            };
            config.getConfiguration().setTestOnBorrow(false);
            config.getConfiguration().setTestOnReturn(true);
            files.core.createConnection("file", files.name, factory, config.getConfiguration(), files.message);
            ConnectionLease lease = files.borrow();
            Assert.assertEquals(checks.get(), 0);
            lease.returnToPool();
            Assert.assertEquals(checks.get(), 1);
            ConnectionContext context = factory.getContext();
            ConnectionLease core = context.borrowForStream();
            StreamLease leaseStream = StreamLease.active(context, files.name, "application/octet-stream", 0, 0,
                    core, FilePoolFixture.fileWithStream(new ByteArrayInputStream(new byte[0])), null);
            context.publish(StreamLeases.forRead(files.message), leaseStream, null);
            try (InputStream reader = leaseStream.getInputStream()) {
                Assert.assertEquals(checks.get(), 1);
                Assert.assertEquals(reader.read(), -1);
            }
            Assert.assertEquals(checks.get(), 2);
            Assert.assertEquals(leaseStream.state(), StreamLease.State.REPLAY);
        }
    }

    @Test
    public void retirementClosesOnlyItsOwnContext() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionLease old = files.borrow();
            FileSystemHandler handler = FilePoolFixture.handler(old);
            ConnectionContext oldContext = handler.getContext();
            StreamLeases holder = StreamLeases.forRead(files.message);
            String path = "file:///stream/" + files.name;
            StreamLease replay = StreamLease.replay(oldContext, path, 1, 0,
                    oldContext.getShareLock().enterLocalOnly(path, 1, 0).handle);
            oldContext.publish(holder, replay, null);
            files.close();
            Assert.assertEquals(replay.reason(), StreamLease.Reason.RETIRED);
            Assert.assertSame(holder.get(oldContext.getName(), path), replay);
            new FileConfig().connect(files.message);
            ConnectionLease fresh = files.borrow();
            try {
                Assert.assertNotSame(FilePoolFixture.handler(fresh).getContext(), handler.getContext());
                Assert.assertTrue(handler.getContext().isClosed());
                Assert.assertFalse(FilePoolFixture.handler(fresh).getContext().isClosed());
                old.returnToPool();
                Assert.assertTrue(files.core.getConnectionFactory("file", files.name).validateObject(fresh.getConnection()));
            } finally {
                fresh.returnToPool();
            }
        }
    }

    @Test
    public void connectionHealthTracksTheBreakerWithoutAPollSchedule() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionLease lease = files.borrow();
            try {
                EndpointHealth health = FilePoolFixture.handler(lease).getContext().getHealth();
                Assert.assertEquals(health.kind(), HealthRegistry.Kind.CONNECTION);
                Method tick = EndpointHealth.class.getDeclaredMethod("refresh", boolean.class);
                tick.setAccessible(true);
                tick.invoke(health, false);
                Assert.assertEquals(health.state(), EndpointHealth.State.OK);
                health.breakerState(EndpointHealth.Breaker.OPEN);
                tick.invoke(health, false);
                Assert.assertEquals(health.state(), EndpointHealth.State.BREAKER_OPEN);
                health.breakerState(EndpointHealth.Breaker.CLOSED);
                tick.invoke(health, false);
                Assert.assertEquals(health.state(), EndpointHealth.State.OK);
            } finally {
                lease.returnToPool();
            }
        }
    }

    @Test
    public void ftpAndFtpsBoundsPreserveOmittedAndExplicitFields() throws Exception {
        for (String protocol : new String[]{"FTP","FTPS"}) {
            for (String value : new String[]{null, "0", "17500"}) {
                try (FilePoolFixture files = value == null ? new FilePoolFixture(protocol) :
                        new FilePoolFixture(protocol, "ftpConnectionTimeout", value, "ftpSocketTimeout", value)) {
                    ConnectionLease lease = files.borrow();
                    try {
                        EffectiveBounds b = EffectiveBounds.of(protocol.toLowerCase(java.util.Locale.ROOT),
                                FilePoolFixture.handler(lease).getFsOptions());
                        long expectedConnect = value == null ? 30000L : Long.parseLong(value);
                        long expectedSocket = value == null ? 60000L : Long.parseLong(value);
                        Assert.assertEquals(b.getConnectTimeout().getValue(), expectedConnect, protocol);
                        Assert.assertEquals(b.getSocketTimeout().getValue(), expectedSocket, protocol);
                        Assert.assertEquals(b.getConnectTimeout().getSource().name(), value == null ? "DEFAULT" : "ENDPOINT");
                    } finally {
                        lease.returnToPool();
                    }
                }
            }
        }
    }
}
