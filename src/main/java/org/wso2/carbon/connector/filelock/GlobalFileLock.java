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

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.utils.Const;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;

/**
 * Represents file lock that can be acquired
 * cluster wide.
 */
public class GlobalFileLock implements FileLock {

    private String nameOfFileToLock;
    private String fileToLock;
    private String lockFiePath;
    private FileObject lockFileObject;
    private long expiryTime;

    private static final Log log = LogFactory.getLog(GlobalFileLock.class);

    GlobalFileLock(String fileToLock, long expiresIn, FileSystemHandler conHandler) throws FileSystemException {
        this.fileToLock = fileToLock;
        this.lockFiePath = getLockFilePath(fileToLock);
        this.expiryTime = System.currentTimeMillis() + expiresIn;
        lockFileObject = conHandler.getFsManager().resolveFile(lockFiePath, conHandler.getFsOptions());
    }

    private String getLockFilePath(String fileToLock) {
        //with extension
        nameOfFileToLock = fileToLock.substring(fileToLock.lastIndexOf(Const.FILE_SEPARATOR));
        String nameOfLockFile = nameOfFileToLock + Const.LOCK_FILE_EXTENSION;
        String parentFolderPath = fileToLock.substring(0, fileToLock.lastIndexOf(Const.FILE_SEPARATOR));
        return parentFolderPath + nameOfLockFile;
    }


    @Override
    public String getFilePath() {
        return fileToLock;
    }

    @Override
    public long getExpiryTime() {
        return expiryTime;
    }

    public boolean tryAndAcquire() {
        try {
            //TODO: if expiry time is written to the file, if expired other nodes also can delete it.
            if (lockFileObject.exists()) {
                if (log.isDebugEnabled()) {
                    log.debug("[FileConnector] Cannot obtain lock file for " + nameOfFileToLock);
                }
                return false;
            } else {
                lockFileObject.createFile();
                writeToLockFile(lockFileObject);
                if (log.isDebugEnabled()) {
                    log.debug("[FileConnector] Obtained lock file for " + nameOfFileToLock);
                }
                return true;
            }
        } catch (FileSystemException e) {
            log.error("[FileConnector] - File system exception while trying to check and"
                    + " create lock file " + lockFiePath, e);
            return false;
        }
    }

    public boolean release() {
        if (lockFileObject != null) {
            try {
                lockFileObject.delete();
                if (log.isDebugEnabled()) {
                    log.debug("Removed lock file " + lockFiePath);
                }
            } catch (FileSystemException e) {
                log.error("[FileConnector] - File system exception while removing lock file " + lockFiePath);
                return false;
            }
        }
        return true;
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
            long pid = ProcessHandle.current().pid();
            processId = String.valueOf(pid);
        } catch (Exception e) {
            log.error("[FileConnector] Error while getting information to write to lock file.", e);
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
}
