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

import java.time.Duration;
import org.apache.commons.lang.StringUtils;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.provider.sftp.IdentityInfo;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.wso2.carbon.connector.utils.Const;

import java.io.File;

/**
 * Sets up SFTP based file system.
 */
public class SFTPFileSystemSetup implements ProtocolBasedFileSystemSetup {

    @Override
    public String setupFileSystemHandler(FileSystemOptions fso, ConnectionConfiguration fsConfig)
            throws FileServerConnectionException {

        SFTPConnectionConfig sftpConnectionConfig = (SFTPConnectionConfig) fsConfig.getRemoteServerConfig();
        SftpFileSystemConfigBuilder sftpConfigBuilder = SftpFileSystemConfigBuilder.getInstance();
        String baseUri = Const.SFTP_PROTOCOL_PREFIX + constructVfsUrl(fsConfig);
        try {
            if (sftpConnectionConfig.getSessionTimeout() != null) {
                sftpConfigBuilder.setSessionTimeout(fso, Duration.ofMillis(sftpConnectionConfig.getSessionTimeout()));
            }
            if (sftpConnectionConfig.getConnectionTimeout() != null) {
                sftpConfigBuilder.setConnectTimeout(fso, Duration.ofMillis(sftpConnectionConfig.getConnectionTimeout()));
            }
            if (sftpConnectionConfig.getKeepAliveCount() != null) {
                sftpConfigBuilder.setKeepAliveCountMax(fso, sftpConnectionConfig.getKeepAliveCount());
            }
            sftpConfigBuilder.setUserDirIsRoot(fso, sftpConnectionConfig.isUserDirIsRoot());
            
            // Set SFTP path from root if enabled (overrides userDirIsRoot for absolute path access)
            if (sftpConnectionConfig.isSftpPathFromRoot()) {
                sftpConfigBuilder.setUserDirIsRoot(fso, false); // false = paths are relative to filesystem root
            }

            if (sftpConnectionConfig.isStrictHostKeyChecking()) {
                sftpConfigBuilder.setStrictHostKeyChecking(fso, "yes");
            } else {
                sftpConfigBuilder.setStrictHostKeyChecking(fso, "no");
            }

            String privateKeyFilePath = sftpConnectionConfig.getPrivateKeyFilePath();
            if (StringUtils.isNotEmpty(privateKeyFilePath)) {
                IdentityInfo identityInfo;
                File sftpIdentity = new File(privateKeyFilePath);
                String keyPassphrase = sftpConnectionConfig.getPrivateKeyPassword();
                if (StringUtils.isNotEmpty(keyPassphrase)) {
                    identityInfo = new IdentityInfo(sftpIdentity, keyPassphrase.getBytes());
                    sftpConfigBuilder.setIdentityPassPhrase(fso, keyPassphrase);
                } else {
                    identityInfo = new IdentityInfo(sftpIdentity);
                }
                sftpConfigBuilder.setIdentityInfo(fso, identityInfo);
            }

            String permission = sftpConnectionConfig.getAvoidPermissionCheck();
            if (permission == null) {
                permission = UriParser.extractQueryParams(baseUri).get("transport.vfs.AvoidPermissionCheck");
            }
            sftpConfigBuilder.setAvoidPermissionCheck(fso, permission == null ? "true" : permission);
        } catch (FileSystemException e) {
            throw new FileServerConnectionException("[" + fsConfig.getConnectionName()
                    + "] Error while setting fso options", e);
        }

        return baseUri;

    }
}
