/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.connector.connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.exception.ConnectionSuspendedException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper for VFS operations that implements connection suspension logic
 * for FTP/FTPS protocols that bypass WSO2 ConnectionHandler framework.
 */
public class VFSConnectionWrapper {

    private static final Log log = LogFactory.getLog(VFSConnectionWrapper.class);

    // Connection state tracking per connection name
    private static final ConcurrentHashMap<String, ConnectionState> connectionStates = new ConcurrentHashMap<>();

    private final FileSystemManager fsManager;
    private final FileSystemOptions fsOptions;
    private final ConnectionConfiguration config;
    private final String connectionName;

    public VFSConnectionWrapper(FileSystemManager fsManager, FileSystemOptions fsOptions,
                               ConnectionConfiguration config, String connectionName) {
        this.fsManager = fsManager;
        this.fsOptions = fsOptions;
        this.config = config;
        this.connectionName = connectionName;

        // Initialize connection state if not exists
        connectionStates.computeIfAbsent(connectionName, k -> new ConnectionState(config));
    }

    /**
     * Resolve file with connection suspension logic
     *
     * @param path File path to resolve
     * @return FileObject
     * @throws FileSystemException if file resolution fails or connection is suspended
     */
    public FileObject resolveFile(String path) throws FileSystemException {
        // Input validation
        if (path == null || path.trim().isEmpty()) {
            throw new FileSystemException("File path cannot be null or empty");
        }

        ConnectionState state = connectionStates.get(connectionName);

        // Null safety check
        if (state == null) {
            log.error("Connection state not found for: " + connectionName);
            return fsManager.resolveFile(path, fsOptions);
        }

        // Check if connection is currently suspended
        if (state.isSuspended()) {
            long remainingTime = state.getRemainingSuspensionTime();
            if (remainingTime > 0) {
                log.warn("Connection '" + connectionName + "' is suspended for " + remainingTime + " more milliseconds");
                throw new ConnectionSuspendedException(connectionName, remainingTime);
            } else {
                // Suspension period expired, resume connection
                state.resume();
                log.info("Connection '" + connectionName + "' resumed after suspension period");
            }
        }

        try {
            // Attempt VFS operation
            FileObject fileObject = fsManager.resolveFile(path, fsOptions);

            // Reset failure count on successful operation
            state.resetFailures();

            return fileObject;

        } catch (FileSystemException e) {
            // Handle connection failure with suspension logic
            handleConnectionFailure(state, e);

            // For connection refused errors (server down), throw cleaner exception
            if (isConnectionRefusedError(e)) {
                throw new ConnectionSuspendedException(connectionName, "Server unavailable (Connection refused)");
            }

            // For other VFS errors, throw original exception
            throw e;
        }
    }

    /**
     * Check if the exception is a connection refused error (server down)
     */
    private boolean isConnectionRefusedError(FileSystemException e) {
        if (e.getCause() != null) {
            String causeMessage = e.getCause().getMessage();
            return causeMessage != null && causeMessage.toLowerCase().contains("connection refused");
        }
        return e.getMessage() != null && e.getMessage().toLowerCase().contains("could not connect");
    }

    /**
     * Handle connection failure and implement suspension logic
     */
    private void handleConnectionFailure(ConnectionState state, FileSystemException originalException) {
        if (!config.isSuspendOnConnectionFailure()) {
            return; // Suspension disabled
        }

        int currentFailures = state.incrementFailures();
        int retriesBeforeSuspension = config.getRetriesBeforeSuspension();

        log.warn(String.format("Connection '%s' failure count: %d (will suspend after %d failures)",
                               connectionName, currentFailures, retriesBeforeSuspension));

        if (currentFailures > retriesBeforeSuspension) {
            long suspensionDuration = state.calculateSuspensionDuration();
            state.suspend(suspensionDuration);

            log.warn(String.format("Connection '%s' suspended for %d milliseconds after %d failures",
                                 connectionName, suspensionDuration, currentFailures));
        }
    }


    /**
     * Get connection name for cleanup purposes
     */
    public String getConnectionName() {
        return connectionName;
    }

    /**
     * Remove connection state (cleanup)
     */
    public static void removeConnectionState(String connectionName) {
        if (connectionName != null) {
            ConnectionState removed = connectionStates.remove(connectionName);
            if (removed != null && log.isDebugEnabled()) {
                log.debug("Removed connection state for: " + connectionName);
            }
        }
    }


    /**
     * Inner class to track connection state and suspension logic
     */
    public static class ConnectionState {
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong suspendedUntil = new AtomicLong(0);
        private final AtomicInteger suspensionLevel = new AtomicInteger(0);

        private final ConnectionConfiguration config;

        public ConnectionState(ConnectionConfiguration config) {
            this.config = config;
        }

        public int incrementFailures() {
            return failureCount.incrementAndGet();
        }

        public void resetFailures() {
            failureCount.set(0);
            suspensionLevel.set(0);
        }

        public boolean isSuspended() {
            return System.currentTimeMillis() < suspendedUntil.get();
        }

        public long getRemainingSuspensionTime() {
            long remaining = suspendedUntil.get() - System.currentTimeMillis();
            return Math.max(0, remaining);
        }

        public void suspend(long duration) {
            suspendedUntil.set(System.currentTimeMillis() + duration);
            suspensionLevel.incrementAndGet();
        }

        public void resume() {
            suspendedUntil.set(0);
        }

        public long calculateSuspensionDuration() {
            long initialDuration = config.getSuspendInitialDuration();
            double progressionFactor = config.getSuspendProgressionFactor();
            long maxDuration = config.getSuspendMaximumDuration();

            int level = suspensionLevel.get();
            long duration = (long) (initialDuration * Math.pow(progressionFactor, level));

            return Math.min(duration, maxDuration);
        }

    }
}
