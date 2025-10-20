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

package org.wso2.carbon.connector.filelock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.carbon.connector.connection.FileSystemHandler;

/**
 * Manages file locks.
 */
public final class FileLockManager {

    private FileSystemHandler conHandler;
    private boolean isClustered;
    private FileLockStore lockStore;

    private static final Log log = LogFactory.getLog(FileLockManager.class);

    /**
     * Creates a FileLockManager.
     * It will be attached to the connection.
     *
     * @param conHandler  Connection handler
     * @param isClustered True if you need to enable cluster-wide locks
     *                    for the files.
     */
    public FileLockManager(FileSystemHandler conHandler, boolean isClustered) {
        this.conHandler = conHandler;
        this.isClustered = isClustered;
        this.lockStore = new FileLockStore();
    }

    /**
     * Try and acquire a lock for the file. This marks
     * that the file is processed by the operation. Make
     * sure to release it after done with the operation.
     *
     * @param filePath   Path of the file to acquire lock for
     * @param expiryTime After how many milliseconds lock should expire.
     *                   After expiry some other process can acquire it.
     * @return True if lock is acquired.
     */
    public boolean tryAndAcquireLock(String filePath, long expiryTime) {
        return acquireLock(filePath, expiryTime);
    }

    /**
     * Try several times and acquire a lock for the file. This marks
     * that the file is processed by the operation. Make
     * sure to release it after done with the operation.
     *
     * @param filePath Path of the file to acquire lock for
     * @param expiryTime After how many milliseconds lock should expire
     * @param maxRetryTimes Maximum times to try to get the lock
     * @param retryInterval Time between two failure tries to get the lock
     * @return True if lock is acquired.
     */
    public boolean tryAndAcquireLock(String filePath, long expiryTime, int maxRetryTimes, int retryInterval) {
        int tryCount = 0;
        boolean lockAcquired = false;
        while (tryCount < maxRetryTimes) {
            lockAcquired = acquireLock(filePath, expiryTime);
            if (lockAcquired) {
                break;
            } else {
                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            tryCount = tryCount + 1;
        }
        return lockAcquired;
    }

    /**
     * Release the lock for the file.
     *
     * @param filePath Path of the file to release lock for
     */
    public void releaseLock(String filePath) {
        if (isClustered) {
            releaseGlobal(filePath);
        } else {
            releaseLocal(filePath);
        }
    }

    public void releaseAllLocks() {
        lockStore.releaseAllLocks();
    }


    private boolean acquireLock(String filePath, long expiresIn) {
        boolean lockAcquired;
        if (log.isDebugEnabled()) {
            log.debug("[FileConnector] Trying to acquire file lock for " + filePath);
        }
        if (isClustered) {
            lockAcquired = tryAndAcquireGlobal(filePath, expiresIn);
        } else {
            lockAcquired = tryAndAcquireLocal(filePath, expiresIn);
        }
        if (log.isDebugEnabled()) {
            log.debug("[FileConnector] lock acquire state for " + filePath + " is = " + lockAcquired);
        }
        return lockAcquired;
    }

    private boolean tryAndAcquireLocal(String filePath, long expiresIn) {
        FileLock localLock = new LocalFileLock(filePath, expiresIn);
        return lockStore.add(localLock);
    }

    private void releaseLocal(String filePath) {
        lockStore.remove(filePath);
        if(log.isDebugEnabled()) {
            log.debug("Released local lock for filePath " + filePath);
        }
    }

    private boolean tryAndAcquireGlobal(String filePath, long expiresIn) {
        GlobalFileLock globalLock;
        boolean globalLockAcquired = false;
        try {
            globalLock = new GlobalFileLock(filePath, expiresIn, conHandler);
        } catch (FileSystemException e) {
            log.error("[FileConnector] Failed to create GlobalFileLock ", e);
            return false;
        }

        boolean locallyAcquired = lockStore.add(globalLock);
        if (locallyAcquired) {
            globalLockAcquired = globalLock.tryAndAcquire();
            if (!globalLockAcquired) {
                lockStore.remove(filePath);
                if(log.isDebugEnabled()) {
                    log.debug("[FileConnector] Released local lock as failed to acquire global lock");
                }
            }
        }

        return globalLockAcquired;
    }

    private void releaseGlobal(String filePath) {
        //get object and call release
        FileLock globalLock = lockStore.remove(filePath);
        if (globalLock != null) {
            globalLock.release();
        }
    }

}
