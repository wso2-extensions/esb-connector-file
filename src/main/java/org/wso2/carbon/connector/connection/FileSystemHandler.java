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

package org.wso2.carbon.connector.connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.integration.connector.core.connection.ConnectionConfig;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.integration.connector.core.connection.Connection;
import org.wso2.carbon.connector.filelock.FileLockManager;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;

/**
 * Object that handles all file operations
 * attached to the connection. This contains
 * FileSystemManager with FileSystemOptions
 * configured.
 */
public class FileSystemHandler implements Connection {

    private static final Log log = LogFactory.getLog(FileSystemHandler.class);

    private FileSystemManager fsManager;
    private FileSystemOptions fsOptions;
    private FileLockManager fileLockManager;

    /**
     * URL constructed adding file protocol,
     * username, password and the base directory path
     * [protocol prefix]://[ username[: password]@] hostname[: port][ base-path]
     */
    private String baseDirectoryPath;

    /**
     * Create a new FileSystemHandler object. This will contain a new FileSystemManager
     * and File System Options.
     *
     * @param fsConfig Connection config object
     * @throws FileServerConnectionException in case of an error setting FileSystemManager
     */
    public FileSystemHandler(ConnectionConfiguration fsConfig) throws FileServerConnectionException {
        try {

            this.fsManager = new StandardFileSystemManager();
            ((StandardFileSystemManager) fsManager).init();
            this.fsOptions = new FileSystemOptions();
            setupFSO(fsOptions, fsConfig);
            fileLockManager = new FileLockManager(this, fsConfig.isClusterLockingEnabled());
        } catch (FileSystemException e) {
            String errorMsg = "Unable to create FileSystemManager: " + e.getMessage();
            log.error(errorMsg, e);
            throw new FileServerConnectionException(errorMsg, e);
        }
    }

    /**
     * Sets connection configs to VFS API.
     * Be careful as FTP, FTPS, SFTP variables looks almost same.
     *
     * @param fso      FileSystemOptions to set the configs
     * @param fsConfig Configuration DTO
     */
    private void setupFSO(FileSystemOptions fso, ConnectionConfiguration fsConfig)
            throws FileServerConnectionException {

        ProtocolBasedFileSystemSetup fileSystemSetup;

        switch (fsConfig.getProtocol()) {
            case LOCAL:
                fileSystemSetup = new LocalFileSystemSetup();
                break;
            case FTP:
                fileSystemSetup = new FTPFileSystemSetup();
                break;
            case FTPS:
                fileSystemSetup = new FTPSFileSystemSetup();
                break;
            case SFTP:
                fileSystemSetup = new SFTPFileSystemSetup();
                break;
            case SMB:
                fileSystemSetup = new SMBFileSystemSetup();
                break;
            case SMB2:
                fileSystemSetup = new SMB2FileSystemSetup();
                break;
            default:
                throw new IllegalStateException("Unexpected protocol value: " + fsConfig.getProtocol());
        }

        baseDirectoryPath = fileSystemSetup.setupFileSystemHandler(fso, fsConfig);
    }

    /**
     * Get FileSystemManager object configured for the connection
     *
     * @return FileSystemManager
     */
    public FileSystemManager getFsManager() {
        return fsManager;
    }

    /**
     * Get FileSystemOptions object configured for the connection
     *
     * @return FileSystemOptions object
     */
    public FileSystemOptions getFsOptions() {
        return fsOptions;
    }

    /**
     * Get base directory path for the connection.
     * [protocol prefix]://[ username[: password]@] hostname[: port][ base-path]
     *
     * @return base directory path as String
     */
    public String getBaseDirectoryPath() {
        return baseDirectoryPath;
    }

    /**
     * Get FileLockManager used to lock files
     * on the connection.
     *
     * @return FileLockStore
     */
    public FileLockManager getFileLockManager() {
        return fileLockManager;
    }

    @Override
    public void connect(ConnectionConfig config) {
        throw new UnsupportedOperationException("Nothing to do when connect");
    }

    @Override
    public void close() {
        ((StandardFileSystemManager) fsManager).close();
        fileLockManager.releaseAllLocks();
    }
}
