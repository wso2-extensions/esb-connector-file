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

import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps File Locks and manage
 * locking. Concurrency is supported.
 */
public class FileLockStore {

    private static final Log log = LogFactory.getLog(FileLockStore.class);
    private static final long DEFAULT_EXPIRY_CHECK_INTERVAL = 15000;
    private final ConcurrentHashMap<String, FileLock> lockMap = new ConcurrentHashMap<>(5);
    private Timer expiryTimer = new java.util.Timer();

    private TimerTask timerTask;

    FileLockStore() {
        scheduleExpiry();
    }

    boolean add(FileLock fileLock) {
        FileLock previous = lockMap.putIfAbsent(fileLock.getFilePath(), fileLock);
        return previous == null;
    }

    FileLock remove(String lockedFile) {
        return lockMap.remove(lockedFile);
    }


    void releaseAllLocks() {
        for (Iterator<Map.Entry<String, FileLock>> iter = lockMap.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<String, FileLock> lockEntry = iter.next();
            FileLock fileLock = lockEntry.getValue();
            tryReleaseLock(fileLock);
            iter.remove();
        }
        if (timerTask != null) {
            timerTask.cancel();
        }
        expiryTimer.cancel();
    }

    private boolean isLockExpired(FileLock lock) {
        long expiryTime = lock.getExpiryTime();
        return (System.currentTimeMillis() > expiryTime);
    }

    /**
     * Try and releases lock. It will try 3 times most
     * to release the lock file.
     *
     * @param lock File lock to release
     */
    private void tryReleaseLock(FileLock lock) {
        boolean lockReleased = false;
        int tryCount = 0;
        while ((!lockReleased) && (tryCount < 3)) {
            lockReleased = lock.release();
            if (!lockReleased) {
                log.warn("Lock for file " + lock.getFilePath() + " is not released. Retrying...");
            }
            tryCount = tryCount + 1;
        }
        if (!lockReleased) {
            log.error("Lock for file " + lock.getFilePath() + " is not released. Giving up");
        }
    }

    /**
     * Task to check and expire the file locks.
     */
    private void scheduleExpiry() {
        expiryTimer.scheduleAtFixedRate(
                timerTask = new java.util.TimerTask() {
                    @Override
                    public void run() {
                        if(log.isDebugEnabled()) {
                            log.debug("[FileConnector] Running File Lock Expiry Checker");
                        }
                        for (Iterator<Map.Entry<String, FileLock>> iter = lockMap.entrySet().iterator(); iter.hasNext(); ) {
                            Map.Entry<String, FileLock> lockEntry = iter.next();
                            FileLock fileLock = lockEntry.getValue();
                            if (isLockExpired(fileLock)) {
                                log.warn("[FileConnector] File Lock Expiry Checker "
                                        + "- lock is expired and will be released file =  " + fileLock.getFilePath());
                                tryReleaseLock(fileLock);
                                iter.remove();
                            }
                        }
                    }
                },0, DEFAULT_EXPIRY_CHECK_INTERVAL
        );
    }

}
