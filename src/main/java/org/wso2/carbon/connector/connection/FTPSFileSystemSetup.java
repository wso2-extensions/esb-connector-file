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
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FTPSConnectionConfig;
import org.wso2.carbon.connector.utils.Const;

/**
 * Sets up FTPS based file system.
 */
public class FTPSFileSystemSetup implements ProtocolBasedFileSystemSetup {

    @Override
    public String setupFileSystemHandler(FileSystemOptions fso, ConnectionConfiguration fsConfig) {

        FTPSConnectionConfig ftpsConnectionConfig = (FTPSConnectionConfig) fsConfig.getRemoteServerConfig();
        FtpsFileSystemConfigBuilder ftpsConfigBuilder = FtpsFileSystemConfigBuilder.getInstance();

        //Set FTP configs
        ftpsConfigBuilder.setPassiveMode(fso, ftpsConnectionConfig.isPassive());
        ftpsConfigBuilder.setConnectTimeout(fso, ftpsConnectionConfig.getConnectionTimeout());
        ftpsConfigBuilder.setSoTimeout(fso, ftpsConnectionConfig.getSocketTimeout());
        ftpsConfigBuilder.setUserDirIsRoot(fso, ftpsConnectionConfig.isUserDirIsRoot());

        //Set FTPS specific configs
        if (ftpsConnectionConfig.getFtpsProtectionLevel() != null) {
            ftpsConfigBuilder.setDataChannelProtectionLevel(fso, ftpsConnectionConfig.getFtpsProtectionLevel());
        }

        ftpsConfigBuilder.setFtpsMode(fso, ftpsConnectionConfig.getFtpsMode());

        if (StringUtils.isNotEmpty(ftpsConnectionConfig.getKeyStore())) {
            ftpsConfigBuilder.setKeyStore(fso, ftpsConnectionConfig.getKeyStore());
        }

        if (StringUtils.isNotEmpty(ftpsConnectionConfig.getKeyStorePassword())) {
            ftpsConfigBuilder.setKeyStorePW(fso, ftpsConnectionConfig.getKeyStorePassword());
        }

        if (StringUtils.isNotEmpty(ftpsConnectionConfig.getTrustStore())) {
            ftpsConfigBuilder.setTrustStore(fso, ftpsConnectionConfig.getTrustStore());
        }

        if (StringUtils.isNotEmpty(ftpsConnectionConfig.getTrustStorePassword())) {
            ftpsConfigBuilder.setTrustStorePW(fso, ftpsConnectionConfig.getTrustStorePassword());
        }

        if (StringUtils.isNotEmpty(ftpsConnectionConfig.getKeyPassword())) {
            ftpsConfigBuilder.setKeyPW(fso, ftpsConnectionConfig.getKeyPassword());
        }

        return Const.FTPS_PROTOCOL_PREFIX + constructVfsUrl(fsConfig);
    }
}
