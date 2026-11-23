/*
 *  Copyright (c) 2023, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.connector.connection;

import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.util.PoolTestPoints;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.log.UriMask;
import org.wso2.vfs.shared.write.AtomicWriter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

public final class ConnectionContext implements StreamLease.Owner {
    private final ConnectionConfiguration configuration;
    private final String name;
    private final FileSystemOptions options;
    private final String baseUri;
    private final String maskedBaseUri;
    private final Set<FileSystemHandler> handlers = ConcurrentHashMap.newKeySet();
    private final HealthRegistry.ConnectionRegistration registration;
    private final ShareLock shareLock;
    private final AtomicWriter writer;
    private final AtomicBoolean renameWarning = new AtomicBoolean();
    private final AtomicBoolean timeWarning = new AtomicBoolean();
    private final Set<StreamLease> leases = new HashSet<>();
    private final ArrayDeque<Release> releases = new ArrayDeque<>();
    private final LongSupplier clock;
    private final long idleMs;
    private final BiFunction<HeldLock, LockIo, LockOutcome> release;
    private Thread reaper;
    private TestPoints.Handle poolState;
    private volatile boolean closed;

    ConnectionContext(ConnectionConfiguration configuration, String deployedKey, String name)
            throws FileServerConnectionException {
        this(configuration, deployedKey, name, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
                configuration.getStreamIdleTimeout(),
                HeldLock::releaseChecked);
    }

    ConnectionContext(ConnectionConfiguration configuration, String deployedKey, String name, LongSupplier clock,
                      long idleMs, BiFunction<HeldLock, LockIo, LockOutcome> release)
            throws FileServerConnectionException {
        this.clock = clock;
        this.idleMs = idleMs;
        this.release = release;
        this.configuration = configuration;
        this.name = name;
        options = (FileSystemOptions) configuration.getProviderOptions().clone();
        baseUri = FileSystemHandler.setupOptions(options, configuration);
        maskedBaseUri = UriMask.mask(baseUri);
        registration = HealthRegistry.registerConnection(deployedKey, name);
        try {
            shareLock = ShareLock.create(name, configuration.isAutoLockRelease(), configuration.getLockStaleAge(),
                    registration.health());
            writer = new AtomicWriter(name, registration.health(), shareLock, configuration.isUseTempFolder(),
                    configuration.getTempFolderName(), configuration.getTempFileSuffix());
        } catch (RuntimeException | Error failure) {
            registration.close();
            throw failure;
        }
        String scheme = configuration.getProtocol() == FileServerProtocol.LOCAL ? "file"
                : configuration.getProtocol().getName().toLowerCase(Locale.ROOT);
        LogFactory.getLog(ConnectionContext.class).info("Connection " + UriMask.mask(name) + " borrowWait="
                + configuration.getMaxWaitTime() + " lockWait=10000 fastFailure=5000 "
                + EffectiveBounds.of(scheme, options));
        if (configuration.getProtocol() == FileServerProtocol.SFTP) {
            SFTPConnectionConfig sftp = (SFTPConnectionConfig) configuration.getRemoteServerConfig();
            if (Integer.valueOf(150000).equals(sftp.getSessionTimeout())
                    && Integer.valueOf(100000).equals(sftp.getConnectionTimeout())) {
                registration.health().namedLog().warn("Connection " + name
                        + " sets both legacy SFTP timeouts. Remove them to use the provider defaults.");
            }
        }
    }

    FileSystemOptions newOptions() {
        return (FileSystemOptions) options.clone();
    }

    void add(FileSystemHandler handler) {
        handlers.add(handler);
    }

    void remove(FileSystemHandler handler) {
        handlers.remove(handler);
    }

    public int getOpenClientCount() {
        int total = 0;
        for (FileSystemHandler handler : handlers) {
            total += handler.getOpenClientCount();
        }
        return total;
    }

    public String getName() {
        return name;
    }

    public ConnectionConfiguration getConfiguration() {
        return configuration;
    }

    public String getBaseUri() {
        return baseUri;
    }

    public String getMaskedBaseUri() {
        return maskedBaseUri;
    }

    public EndpointHealth getHealth() {
        return registration.health();
    }

    public ShareLock getShareLock() {
        return shareLock;
    }

    public AtomicWriter getWriter() {
        return writer;
    }

    public boolean firstRenameWarning() {
        return renameWarning.compareAndSet(false, true);
    }

    public boolean firstTimeWarning() {
        return timeWarning.compareAndSet(false, true);
    }

    public boolean isClosed() {
        return closed;
    }


    private static final class Release {
        final HeldLock lock;
        final long deadline;

        Release(HeldLock lock, long deadline) {
            this.lock = lock;
            this.deadline = deadline;
        }
    }

    @Override
    public long now() {
        return clock.getAsLong();
    }

    @Override
    public long idleMs() {
        return idleMs;
    }

    @Override
    public ConnectionLease borrowForStream() throws IOException {
        ConnectionLease core;
        try {
            core = ConnectionHandler.getConnectionHandler().borrowConnection(Const.CONNECTOR_NAME, name);
        } catch (ConnectException error) {
            throw new IOException("Could not borrow a file connection", error);
        }
        if (core.isTrial()) {
            try {
                core.cancelTrial();
            } finally {
                core.returnToPool();
            }
            throw new IOException("A stream cannot use the trial connection");
        }
        if (((FileSystemHandler) core.getConnection()).getContext() != this) {
            core.returnToPool();
            throw new IOException("The connection was retired");
        }
        return core;
    }

    public void publish(StreamLeases holder, StreamLease lease, StreamLease.Lending previous) {
        register(lease);
        holder.put(lease);
        if (previous != null) {
            previous.moved();
        }
        if (closed) {
            lease.retire();
        }
    }

    void register(StreamLease lease) {
        synchronized (leases) {
            leases.add(lease);
            startReaper();
            leases.notifyAll();
        }
    }

    @Override
    public void ended(StreamLease lease, HeldLock lock, long deadline) {
        synchronized (leases) {
            leases.remove(lease);
            if (lock != null) {
                releases.addLast(new Release(lock, deadline));
                startReaper();
            }
            leases.notifyAll();
        }
    }

    @Override
    public void wakeReaper() {
        synchronized (leases) {
            leases.notifyAll();
        }
    }

    public int activeStreamCount() {
        synchronized (leases) {
            int count = 0;
            for (StreamLease lease : leases) {
                if (lease.state() == StreamLease.State.ACTIVE) {
                    count++;
                }
            }
            return count;
        }
    }

    void registerPoolState(FileSystemHandlerFactory factory) {
        String key = Const.CONNECTOR_NAME + ":" + name;
        poolState = TestPoints.snapshot("pool.state", key, () -> {
            StringBuilder line = new StringBuilder("context=").append(Integer.toUnsignedString(
                    System.identityHashCode(this)));
            PoolTestPoints.activeCounts(key, factory).forEach((id, active) ->
                    line.append(" pool=").append(id).append(" active=").append(active));
            synchronized (leases) {
                line.append(" queue=").append(releases.size()).append(" reaper=")
                        .append(reaper == null ? 0 : reaper.getId());
            }
            return line.toString();
        });
    }

    private void startReaper() {
        if (reaper == null) {
            reaper = new Thread(this::reap, "File stream reaper " + name);
            reaper.setDaemon(true);
            reaper.start();
        }
    }

    private void reap() {
        for (;;) {
            List<StreamLease> due = new ArrayList<>();
            synchronized (leases) {
                if (leases.isEmpty() && releases.isEmpty()) {
                    reaper = null;
                    leases.notifyAll();
                    return;
                }
                long time = now();
                long next = Long.MAX_VALUE;
                if (!closed) {
                    for (StreamLease lease : leases) {
                        long deadline = lease.nextDeadline(time);
                        if (deadline <= time) {
                            due.add(lease);
                        } else {
                            next = Math.min(next, deadline);
                        }
                    }
                }
                if (due.isEmpty() && releases.isEmpty()) {
                    try {
                        leases.wait(next == Long.MAX_VALUE ? 0 : next - time);
                    } catch (InterruptedException ignored) {
                        // Check the leases again.
                    }
                    continue;
                }
            }
            roundEvent("reaper.round.start");
            try {
                for (StreamLease lease : due) {
                    lease.expire(now());
                }
                releaseOne();
            } finally {
                roundEvent("reaper.round.end");
            }
        }
    }

    private void roundEvent(String point) {
        if (TestPoints.enabled()) {
            TestPoints.event(point, Const.CONNECTOR_NAME + ":" + name);
        }
    }

    private void releaseOne() {
        List<HeldLock> expired = new ArrayList<>();
        Release oldest;
        synchronized (leases) {
            long time = now();
            Iterator<Release> entries = releases.iterator();
            while (entries.hasNext()) {
                Release entry = entries.next();
                if (closed || time >= entry.deadline) {
                    expired.add(entry.lock);
                    entries.remove();
                }
            }
            oldest = releases.pollFirst();
        }
        for (HeldLock lock : expired) {
            lock.abandon();
        }
        if (oldest == null) {
            return;
        }

        ConnectionLease core;
        try {
            if (closed) {
                abandonQueue(oldest.lock);
                return;
            }
            core = borrowForStream();
        } catch (IOException error) {
            abandonQueue(oldest.lock);
            return;
        }
        boolean lost = false;
        try {
            FileSystemHandler handler = (FileSystemHandler) core.getConnection();
            LockOutcome result = release.apply(oldest.lock, StreamLease.io(handler));
            lost = result.classification != null && result.classification.getKind().isTransport();
        } finally {
            if (lost) {
                try {
                    core.invalidate();
                } finally {
                    abandonQueue(oldest.lock);
                }
            } else {
                core.returnToPool();
            }
        }
    }

    private void abandonQueue(HeldLock first) {
        List<Release> pending;
        synchronized (leases) {
            pending = new ArrayList<>(releases);
            releases.clear();
        }
        first.abandon();
        for (Release entry : pending) {
            entry.lock.abandon();
        }
    }

    void retire() {
        List<StreamLease> retiring;
        synchronized (leases) {
            closed = true;
            retiring = new ArrayList<>(leases);
            leases.notifyAll();
        }
        if (poolState != null) {
            poolState.remove();
        }
        try {
            for (StreamLease lease : retiring) {
                lease.retire();
            }
        } finally {
            registration.close();
        }
    }
}
