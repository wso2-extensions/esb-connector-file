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

import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.cache.LRUFilesCache;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.integration.connector.core.connection.ConnectionConfig;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.integration.connector.core.connection.Connection;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * Object that handles all file operations
 * attached to the connection. This contains
 * FileSystemManager with FileSystemOptions
 * configured.
 */
public class FileSystemHandler implements Connection {

    private final NamedLog log;

    private final StandardFileSystemManager fsManager;
    private final ConnectionContext context;
    private final FileSystemOptions fsOptions;
    private final ConnectionConfiguration connectionConfig;

    /**
     * Create a new FileSystemHandler object. This will contain a new FileSystemManager
     * and File System Options.
     *
     * @param context the owner of this handler
     * @throws FileServerConnectionException in case of an error setting FileSystemManager
     */
    FileSystemHandler(ConnectionContext context) throws FileServerConnectionException {
        this.context = context;
        this.log = context.getHealth().namedLog();
        this.connectionConfig = context.getConfiguration();
        try {

            this.fsManager = new StandardFileSystemManager();

            // Configure VFS caching at FileSystemManager level based on user preference
            if (connectionConfig.isFileCacheEnabled()) {
                fsManager.setFilesCache(new LRUFilesCache());
                if (log.isDebugEnabled()) {
                    log.debug("VFS file caching enabled using LRUFilesCache");
                }
            } else {
                fsManager.setFilesCache(new NullFilesCache());
                if (log.isDebugEnabled()) {
                    log.debug("VFS file caching disabled using NullFilesCache");
                }
            }

            fsManager.init();
            this.fsOptions = context.newOptions();
        } catch (FileSystemException e) {
            String errorMsg = "Unable to create FileSystemManager: " + e.getMessage();
            log.error(errorMsg);
            throw new FileServerConnectionException(UriMask.mask(errorMsg), e);
        }
    }

    /**
     * Sets connection configs to VFS API.
     * Be careful as FTP, FTPS, SFTP variables looks almost same.
     *
     * @param fso      FileSystemOptions to set the configs
     * @param fsConfig Configuration DTO
     */
    static String setupOptions(FileSystemOptions fso, ConnectionConfiguration fsConfig)
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

        return fileSystemSetup.setupFileSystemHandler(fso, fsConfig);
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
        return context.getBaseUri();
    }

    public FileObject resolveFile(String path) throws FileSystemException {
        FileSystemOptions options = fsOptions;
        if (connectionConfig.getProtocol() == FileServerProtocol.SFTP && path.indexOf('?') >= 0
                && ((SFTPConnectionConfig) connectionConfig.getRemoteServerConfig()).getAvoidPermissionCheck() == null) {
            String permission = UriParser.extractQueryParams(path).get("transport.vfs.AvoidPermissionCheck");
            if (permission != null) {
                options = (FileSystemOptions) fsOptions.clone();
                SftpFileSystemConfigBuilder.getInstance().setAvoidPermissionCheck(options, permission);
            }
        }
        return fsManager.resolveFile(path, options);
    }

    public void afterLocalCheck() {
        if (TestPoints.enabled()) {
            TestPoints.hold("local-check.after", context.getName(), "connection=" + context.getName());
        }
    }

    @Override
    public void connect(ConnectionConfig config) {
        throw new UnsupportedOperationException("Nothing to do when connect");
    }

    public ConnectionContext getContext() {
        return context;
    }

    int getOpenClientCount() {
        return fsManager.getOpenClientCount();
    }

    boolean isAliveLocally() {
        return connectionConfig.getProtocol() == FileServerProtocol.LOCAL
                || fsManager.isAliveLocally();
    }

    @Override
    public void close() {
        try {
            fsManager.abortAll();
        } catch (RuntimeException e) {
            log.warn("Could not abort the file system manager. " + e.getMessage());
        } finally {
            closeManager();
        }
    }

    private void closeManager() {
        try {
            fsManager.close();
        } catch (RuntimeException e) {
            log.warn("Could not close the file system manager. " + e.getMessage());
        }
    }

}
