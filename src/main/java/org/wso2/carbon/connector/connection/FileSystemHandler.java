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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.sftp.IdentityInfo;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.core.connection.Connection;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FTPConnectionConfig;
import org.wso2.carbon.connector.pojo.FTPSConnectionConfig;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.wso2.carbon.connector.utils.FileConnectorConstants;

import java.io.File;

/**
 * Object that handles all file operations
 * attached to the connection. This contains
 * FileSystemManager with FileSystemOptions
 * configured
 */
public class FileSystemHandler implements Connection {

    private static final Log log = LogFactory.getLog(FileSystemHandler.class);

    private FileSystemManager fsManager;
    private FileSystemOptions fsOptions;
    private ConnectionConfiguration connectionConfig;

    /**
     * URL constructed adding file protocol,
     * username, password and the base directory path
     * [protocol prefix]://[ username[: password]@] hostname[: port][ base-path]
     */
    private String baseDirectoryPath;

    public FileSystemHandler(ConnectionConfiguration fsConfig) throws FileServerConnectionException {
        try {

            this.fsManager = new StandardFileSystemManager();
            //need this to get host,port etc when performing operations
            this.connectionConfig = fsConfig;
            ((StandardFileSystemManager) fsManager).init();
            this.fsOptions = new FileSystemOptions();
            setupFSO(fsOptions, fsConfig);
        } catch (FileSystemException e) {
            String errorMsg = "Unable to create FileSystemManager: " + e.getMessage();
            log.error(errorMsg, e);
            throw new FileServerConnectionException(errorMsg, e);
        }
    }

    //TODO: DO null checks for non mandatories? if we have defaults we do not have to

    /**
     * Set connection configs to VFS API
     * Be careful as FTP, FTPS, SFTP variables looks almost same
     *
     * @param fso      FileSystemOptions to set the configs
     * @param fsConfig Configuration DTO
     */
    private void setupFSO(FileSystemOptions fso, ConnectionConfiguration fsConfig)
            throws FileServerConnectionException {

        switch (fsConfig.getProtocol()) {

            case LOCAL:

                baseDirectoryPath = FileConnectorConstants.LOCAL_FILE_PROTOCOL_PREFIX
                        + setupBaseDirectoryPath(fsConfig);

                break;

            case FTP:

                FTPConnectionConfig ftpConnectionConfig = (FTPConnectionConfig) fsConfig.getRemoteServerConfig();
                FtpFileSystemConfigBuilder ftpConfigBuilder = FtpFileSystemConfigBuilder.getInstance();
                ftpConfigBuilder.setPassiveMode(fso, ftpConnectionConfig.isPassive());
                ftpConfigBuilder.setConnectTimeout(fso, ftpConnectionConfig.getConnectionTimeout());
                ftpConfigBuilder.setSoTimeout(fso, ftpConnectionConfig.getSocketTimeout());
                ftpConfigBuilder.setUserDirIsRoot(fso, ftpConnectionConfig.isUserDirIsRoot());

                baseDirectoryPath = FileConnectorConstants.FTP_PROTOCOL_PREFIX
                        + setupBaseDirectoryPath(fsConfig);

                break;

            case FTPS:

                FTPSConnectionConfig ftpsConnectionConfig = (FTPSConnectionConfig) fsConfig.getRemoteServerConfig();
                FtpsFileSystemConfigBuilder ftpsConfigBuilder = FtpsFileSystemConfigBuilder.getInstance();

                //Set FTP configs
                ftpsConfigBuilder.setPassiveMode(fso, ftpsConnectionConfig.isPassive());
                ftpsConfigBuilder.setConnectTimeout(fso, ftpsConnectionConfig.getConnectionTimeout());
                ftpsConfigBuilder.setSoTimeout(fso, ftpsConnectionConfig.getSocketTimeout());
                ftpsConfigBuilder.setUserDirIsRoot(fso, ftpsConnectionConfig.isUserDirIsRoot());

                //Set FTPS specific configs
                ftpsConfigBuilder.setDataChannelProtectionLevel(fso, ftpsConnectionConfig.getFtpsProtectionLevel());
                ftpsConfigBuilder.setFtpsMode(fso, ftpsConnectionConfig.getFtpsMode());
                ftpsConfigBuilder.setKeyStore(fso, ftpsConnectionConfig.getKeyStore());
                ftpsConfigBuilder.setKeyStorePW(fso, ftpsConnectionConfig.getKeyStorePassword());
                ftpsConfigBuilder.setTrustStore(fso, ftpsConnectionConfig.getTrustStorePassword());
                ftpsConfigBuilder.setTrustStorePW(fso, ftpsConnectionConfig.getTrustStorePassword());

                baseDirectoryPath = FileConnectorConstants.FTPS_PROTOCOL_PREFIX
                        + setupBaseDirectoryPath(fsConfig);
                break;

            case SFTP:

                SFTPConnectionConfig sftpConnectionConfig = (SFTPConnectionConfig) fsConfig.getRemoteServerConfig();
                SftpFileSystemConfigBuilder sftpConfigBuilder = SftpFileSystemConfigBuilder.getInstance();

                try {
                    //TODO:this is deprecated in latest. any plan to migrate? Backward compatibility?
                    sftpConfigBuilder.setTimeout(fso, sftpConnectionConfig.getSessionTimeout());

                    if (sftpConnectionConfig.isStrictHostKeyChecking()) {
                        sftpConfigBuilder.setStrictHostKeyChecking(fso, "yes");
                    } else {
                        sftpConfigBuilder.setStrictHostKeyChecking(fso, "no");
                    }
                    String privateKeyFilePath = sftpConnectionConfig.getPrivateKeyFilePath();
                    if (StringUtils.isNotEmpty(privateKeyFilePath)) {
                        File sftpIdentity = new File(privateKeyFilePath);
                        IdentityInfo identityInfo = new IdentityInfo(sftpIdentity);
                        sftpConfigBuilder.setIdentityInfo(fso, identityInfo);
                    }
                    sftpConfigBuilder.setIdentityPassPhrase(fso, sftpConnectionConfig.getPrivateKeyPassword());
                } catch (FileSystemException e) {
                    throw new FileServerConnectionException("[" + fsConfig.getConnectionName()
                            + "] Error while setting fso options", e);
                }

                baseDirectoryPath = FileConnectorConstants.SFTP_PROTOCOL_PREFIX
                        + setupBaseDirectoryPath(fsConfig);
                break;
            default:
                throw new IllegalStateException("Unexpected protocol value: " + fsConfig.getProtocol());
        }
    }

    private String setupBaseDirectoryPath(ConnectionConfiguration fsConfig) {
        StringBuilder sb = new StringBuilder();
        if(fsConfig.getRemoteServerConfig() != null) {
            sb.append(fsConfig.getRemoteServerConfig().getUsername())
                    .append(":")
                    .append(fsConfig.getRemoteServerConfig().getPassword())
                    .append("@")
                    .append(fsConfig.getRemoteServerConfig().getHost())
                    .append(":")
                    .append(fsConfig.getRemoteServerConfig().getPort());
        }
        if(StringUtils.isNotEmpty(fsConfig.getWorkingDir())) {
            sb.append(fsConfig.getWorkingDir());
        }
        return sb.toString();
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
}
