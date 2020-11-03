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

package org.wso2.carbon.connector.utils;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a lock file obtained before reading or writing to a file.
 * If you are processing xyz.xml create a file called xyz.xml.lock
 * (with the CREATE_NEW mode so you don't clobber an existing one).
 * Once you are done, delete it. If you fail to create the file because
 * it already exists, that means that another process is working on it.
 * It might be useful to write information to the lockfile which is useful
 * in debugging, such as the server name and PID. You will also have to
 * have some way of cleaning up abandoned lock files since that won't
 * occur automatically.
 */
public class FileLock {

    private String nameOfFileToLock;
    private String fullPath;
    private FileObject fileObject;
    private volatile AtomicBoolean acquired = new AtomicBoolean(false);
    private Timer expiryTimer = new java.util.Timer();

    private static final Log log = LogFactory.getLog(FileLock.class);

    /**
     * Create lock file object. This will handle
     * lock file creation and removal.
     *
     * @param fileToLock File path to obtain lock for
     */
    public FileLock(String fileToLock) {
        fullPath = getLockFilePath(fileToLock);
    }

    /**
     * Create lock file object. This will handle lock
     * file creation and removal.
     *
     * @param fileToLock File object to obtain lock for
     */
    public FileLock(FileObject fileToLock) {
        fullPath = getLockFilePath(fileToLock);
    }

    /**
     * Acquire lock for the file using supplied FileSystemManager.
     *
     * @param manager    FileSystemManager
     * @param options    FileSystemOptions
     * @param expiryTime Time in milliseconds this lock expires. Lock is released on expiry.
     * @return True if lock is acquired by the app
     * @throws FileSystemException in case of creating lock file
     */
    public boolean acquireLock(FileSystemManager manager, FileSystemOptions options, long expiryTime) throws FileSystemException {
        if (acquired.compareAndSet(false, true)) {
            fileObject = manager.resolveFile(fullPath, options);
            //TODO: if expiry time is written to the file, if expired other nodes also can delete it.
            if (fileObject.exists()) {
                if (log.isDebugEnabled()) {
                    log.debug("[FileConnector] Cannot obtain lock file for " + nameOfFileToLock);
                }
                return false;
            } else {
                fileObject.createFile();
                writeToLockFile(fileObject);
                scheduleExpiry(expiryTime);
                if (log.isDebugEnabled()) {
                    log.debug("[FileConnector] Obtained lock file for " + nameOfFileToLock);
                }
                return true;
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[FileConnector] Invalid state. Cannot obtain lock file for " + nameOfFileToLock);
            }
            return false;
        }
    }

    /**
     * Release the acquired lock
     *
     * @return true if releasing lock is successful.
     * False if lock was not in desired state.
     * @throws FileSystemException in case of lock file removal error.
     */
    public boolean releaseLock() throws FileSystemException {
        if (acquired.compareAndSet(true, false)) {
            fileObject.delete();
            expiryTimer.cancel();
            return true;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[FileConnector] Invalid state. Cannot release lock file for " + nameOfFileToLock);
            }
            return false;
        }
    }

    private String getLockFilePath(String fileToLock) {
        //with extension
        nameOfFileToLock = fileToLock.substring(fileToLock.lastIndexOf(Const.FILE_SEPARATOR));
        String nameOfLockFile = nameOfFileToLock + Const.LOCK_FILE_EXTENSION;
        String parentFolderPath = fileToLock.substring(0, fileToLock.lastIndexOf(Const.FILE_SEPARATOR));
        return parentFolderPath + nameOfLockFile;
    }

    private String getLockFilePath(FileObject fileToLock) {
        nameOfFileToLock = fileToLock.getName().getBaseName();
        String nameOfLockFile = nameOfFileToLock + Const.LOCK_FILE_EXTENSION;
        String parentFolderPath = null;
        try {
            parentFolderPath = fileToLock.getParent().getURL().getPath();
        } catch (FileSystemException e) {
            log.error("[FileConnector] Error while getting parent folder url of lock file " + nameOfFileToLock);
        }
        return parentFolderPath + nameOfLockFile;
    }

    /**
     * Write additional information to lock file.
     * This is useful for Debug.
     *
     * @param lockFile lock file to write information to
     */
    private void writeToLockFile(FileObject lockFile) {

        String hostName = "";
        String hostAddress = "";
        String processId = "";
        try {
            hostName = InetAddress.getLocalHost().getHostName();
            hostAddress = InetAddress.getLocalHost().getHostAddress();
            java.lang.management.RuntimeMXBean runtime =
                    java.lang.management.ManagementFactory.getRuntimeMXBean();
            java.lang.reflect.Field jvm = runtime.getClass().getDeclaredField("jvm");
            jvm.setAccessible(true);
            sun.management.VMManagement mgmt =
                    (sun.management.VMManagement) jvm.get(runtime);
            java.lang.reflect.Method pid_method =
                    mgmt.getClass().getDeclaredMethod("getProcessId");
            pid_method.setAccessible(true);

            int pid = (Integer) pid_method.invoke(mgmt);
            processId = Integer.toString(pid);
        } catch (Exception e) {
            log.error("[FileConnector] Error while getting information to write to lock file.");
        }
        String lockFileContent = hostName +
                Const.NEW_LINE +
                hostAddress +
                Const.NEW_LINE +
                processId;

        try (OutputStream outputStream = lockFile.getContent().getOutputStream()) {
            IOUtils.write(lockFileContent, outputStream);
            if (log.isDebugEnabled()) {
                log.debug("[FileConnector]Lock file written " + lockFile.getName().getBaseName());
            }
        } catch (IOException e) {
            log.error("[FileConnector] Error while writing information to write to lock file "
                    + lockFile.getName().getBaseName() + ".");
        }
    }

    /**
     * Expire the lock. It will try 3 times most to remove the .lock file.
     *
     * @param milliseconds Expiry time in milli seconds
     */
    private void scheduleExpiry(long milliseconds) {
        expiryTimer.schedule(
                new java.util.TimerTask() {
                    @Override
                    public void run() {
                        boolean lockReleased = false;
                        int tryCount = 0;
                        while ((!lockReleased) && (tryCount < 3)) {
                            try {
                                releaseLock();
                                lockReleased = true;
                            } catch (Exception e) {
                                log.error("[FileConnector] Error while releasing lock upon expiry. Retrying max 3 times.");
                            } finally {
                                tryCount = tryCount + 1;
                            }
                        }
                        expiryTimer.cancel();
                    }
                },
                milliseconds
        );
    }

}
