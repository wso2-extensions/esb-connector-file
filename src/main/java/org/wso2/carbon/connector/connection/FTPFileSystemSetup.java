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

import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FTPConnectionConfig;
import org.wso2.carbon.connector.utils.FileConnectorConstants;

/**
 * Sets up FTP based file system.
 */
public class FTPFileSystemSetup implements ProtocolBasedFileSystemSetup {

    @Override
    public String setupFileSystemHandler(FileSystemOptions fso, ConnectionConfiguration fsConfig) {

        FTPConnectionConfig ftpConnectionConfig = (FTPConnectionConfig) fsConfig.getRemoteServerConfig();
        FtpFileSystemConfigBuilder ftpConfigBuilder = FtpFileSystemConfigBuilder.getInstance();
        ftpConfigBuilder.setPassiveMode(fso, ftpConnectionConfig.isPassive());
        ftpConfigBuilder.setConnectTimeout(fso, ftpConnectionConfig.getConnectionTimeout());
        ftpConfigBuilder.setSoTimeout(fso, ftpConnectionConfig.getSocketTimeout());
        ftpConfigBuilder.setUserDirIsRoot(fso, ftpConnectionConfig.isUserDirIsRoot());

        return FileConnectorConstants.FTP_PROTOCOL_PREFIX + constructVfsUrl(fsConfig);
    }
}
