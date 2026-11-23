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

import org.apache.commons.logging.LogFactory;
import org.apache.commons.pool.BaseObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.WriterAppender;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FilesCache;
import org.wso2.org.apache.commons.vfs2.cache.LRUFilesCache;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;

import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FileHandlerTest {
    @Test
    public void releasePoolLibraryIsUnique() throws Exception {
        for (Class<?> cls : new Class<?>[]{BaseObjectPool.class, GenericObjectPool.class}) {
            List<URL> resources = Collections.list(cls.getClassLoader().getResources(
                    cls.getName().replace('.', '/') + ".class"));
            Assert.assertEquals(resources.size(), 1, resources.toString());
            URL jar = cls.getProtectionDomain().getCodeSource().getLocation();
            byte[] bytes = Files.readAllBytes(Paths.get(jar.toURI()));
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hash.append(String.format("%02x", value));
            }
            System.out.println(cls.getName() + " " + jar + " " + hash);
            Assert.assertEquals(hash.toString(), "55abc4ca9981fda86138faead7af1d3fe2d6d6e4e8cf62e943f1eb877591142f");
        }
    }

    @Test
    public void evictionPreservesTheBreakerAndEmitsNoError() throws Exception {
        StringWriter errors = new StringWriter();
        WriterAppender capture = new WriterAppender(new SimpleLayout(), errors);
        capture.setThreshold(Level.ERROR);
        Logger logger = Logger.getRootLogger();
        logger.addAppender(capture);
        AtomicLong clock = new AtomicLong();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", clock::get, "minEvictionTime", "1",
                "suspendInitialDuration", "5000")) {
            ConnectionLease first = files.borrow();
            ConnectionLease second = files.borrow();
            ConnectionPool pool = FilePoolFixture.pool(first);
            CircuitBreaker breaker = pool.getBreaker();
            Object before = state(breaker);
            first.returnToPool();
            evict(pool);
            Assert.assertFalse(alive(FilePoolFixture.handler(first)));
            Assert.assertSame(state(breaker), before);
            ConnectionLease fresh = files.borrow();
            try (FileObject dir = FilePoolFixture.handler(fresh).resolveFile(new File(".").toURI().toString())) {
                Assert.assertTrue(dir.exists());
                fresh.reportSuccess();
            } finally {
                fresh.returnToPool();
            }
            second.reportFailure();
            Assert.assertEquals(breaker.getMode().name(), "OPEN");
            before = state(breaker);
            second.returnToPool();
            evict(pool);
            Assert.assertFalse(alive(FilePoolFixture.handler(second)));
            Assert.assertSame(state(breaker), before);
            clock.addAndGet(5000);
            ConnectionLease trial = files.borrow();
            try {
                Assert.assertEquals(breaker.getMode().name(), "HALF_OPEN");
                trial.reportFailure();
            } finally {
                trial.returnToPool();
            }
            Assert.assertEquals(breaker.getMode().name(), "OPEN");
            Assert.assertEquals(errors.toString(), "");
            LogFactory.getLog(FileSystemHandler.class).error("eviction log capture control");
            Assert.assertTrue(errors.toString().contains("eviction log capture control"));
        } finally {
            logger.removeAppender(capture);
            capture.close();
        }
    }

    private static Object state(CircuitBreaker breaker) throws Exception {
        Method snapshot = CircuitBreaker.class.getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        return snapshot.invoke(breaker);
    }

    private static void evict(ConnectionPool pool) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pool.getNumIdle() > 0 && System.nanoTime() < deadline) {
            pool.evict();
            Thread.sleep(2);
        }
        Assert.assertEquals(pool.getNumIdle(), 0);
    }

    private static boolean alive(FileSystemHandler handler) {
        return ((DefaultFileSystemManager) handler.getFsManager()).isAliveLocally();
    }

    @Test
    public void actualCacheIsOffOrEvictsOlderNames() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            try (FilePoolFixture files = enabled ? new FilePoolFixture("LOCAL", "fileCacheEnabled", "true")
                    : new FilePoolFixture("LOCAL")) {
                ConnectionLease lease = files.borrow();
                try {
                    FileSystemHandler handler = FilePoolFixture.handler(lease);
                    FilesCache cache = handler.getFsManager().getFilesCache();
                    Assert.assertEquals(cache.getClass(), enabled ? LRUFilesCache.class : NullFilesCache.class);
                    FileName first = null;
                    FileSystem fs = null;
                    File root = new File("cache-check");
                    for (int i = 0; i < 220; i++) {
                        try (FileObject file = handler.resolveFile(new File(root, "entry" + i).toURI().toString())) {
                            fs = file.getFileSystem();
                            if (first == null) {
                                first = file.getName();
                            }
                            if (enabled) {
                                Assert.assertSame(cache.getFile(fs, file.getName()), file);
                                Map<?, ?> systems = (Map<?, ?>) FilePoolFixture.field(cache, LRUFilesCache.class,
                                        "fileSystemCache");
                                Map<?, ?> entries = (Map<?, ?>) systems.get(fs);
                                Assert.assertTrue(entries.size() > 0 && entries.size() <= 100);
                            } else {
                                Assert.assertNull(cache.getFile(fs, file.getName()));
                            }
                            if (i == 100) {
                                Assert.assertNull(cache.getFile(fs, first));
                            }
                        }
                    }
                    Assert.assertNull(cache.getFile(fs, first));
                } finally {
                    lease.returnToPool();
                }
            }
        }
    }

    @Test
    public void oneContextPerLifetimeWithPrivateHandlerOptions() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionLease first = files.borrow();
            ConnectionLease other = files.borrow();
            ConnectionContext owner = files.factory.getContext();
            try {
                Assert.assertSame(FilePoolFixture.handler(first).getContext(), owner);
                Assert.assertSame(FilePoolFixture.handler(other).getContext(), owner);
                Assert.assertNotSame(FilePoolFixture.handler(first).getFsManager(), FilePoolFixture.handler(other).getFsManager());
                Assert.assertNotSame(FilePoolFixture.handler(first).getFsOptions(), FilePoolFixture.handler(other).getFsOptions());
                files.factory.beforePoolClose(PoolCloseReason.AGE);
                Assert.assertFalse(owner.isClosed());
                Assert.assertTrue(owner.firstTimeWarning());
                Assert.assertFalse(owner.firstTimeWarning());
                Assert.assertTrue(owner.firstRenameWarning());
                Assert.assertFalse(owner.firstRenameWarning());
                files.close();
                Assert.assertTrue(owner.isClosed());
            } finally {
                first.returnToPool();
                other.returnToPool();
            }
        }
    }

    @Test
    public void endpointTimeoutsReachTheProvider() throws Exception {
        try (FilePoolFixture defaults = new FilePoolFixture("SFTP");
             FilePoolFixture direct = new FilePoolFixture("SFTP", "sftpConnectionTimeout", "15000",
                     "sftpSessionTimeout", "20000", "sftpKeepAliveCount", "2");
             FilePoolFixture legacy = new FilePoolFixture("SFTP", "sftpConnectionTimeout", "100000",
                     "sftpSessionTimeout", "150000")) {
            sftpBounds(defaults, 30000, 30000, 3);
            sftpBounds(direct, 15000, 20000, 2);
            sftpBounds(legacy, 100000, 150000, 1);
        }
    }

    private static void sftpBounds(FilePoolFixture files, int connect, int session, int count) throws Exception {
        ConnectionLease lease = files.borrow();
        try {
            EffectiveBounds bounds = EffectiveBounds.of("sftp", FilePoolFixture.handler(lease).getFsOptions());
            Assert.assertEquals(bounds.getConnectTimeout().getValue(), (long) connect);
            Assert.assertEquals(bounds.getSessionTimeout().getValue(), (long) session);
            Assert.assertEquals(bounds.getKeepAliveCount().getValue(), (long) count);
        } finally {
            lease.returnToPool();
        }
    }
}
