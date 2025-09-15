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

package org.wso2.carbon.connector.pojo;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.provider.ftps.FtpsDataChannelProtectionLevel;
import org.apache.commons.vfs2.provider.ftps.FtpsMode;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;

/**
 * Connection configs for FTPS file server connection
 * Inherits FTP and common remote server configs.
 * Reference: https://commons.apache.org/proper/commons-vfs/commons-vfs2/apidocs/org/apache/commons/vfs2/provider/ftps/FtpsFileSystemConfigBuilder.html
 */
public class FTPSConnectionConfig extends FTPConnectionConfig {

    private String keyStore;
    private String keyStorePassword;
    private String keyPassword;
    private String trustStore;
    private String trustStorePassword;

    private FtpsMode ftpsMode;    //vfs.implicit. Defaults to "explicit" if not defined
    private FtpsDataChannelProtectionLevel channelProtectionLevel;  //vfs.protection


    public FTPSConnectionConfig() {
        super();
        ftpsMode = FtpsMode.EXPLICIT;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        if (!StringUtils.isEmpty(keyStore)) {
            this.keyStore = keyStore;
        }
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        if (!StringUtils.isEmpty(keyStorePassword)) {
            this.keyStorePassword = keyStorePassword;
        }
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        if (!StringUtils.isEmpty(trustStore)) {
            this.trustStore = trustStore;
        }
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        if (!StringUtils.isEmpty(trustStorePassword)) {
            this.trustStorePassword = trustStorePassword;
        }
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        if (!StringUtils.isEmpty(keyPassword)) {
            this.keyPassword = keyPassword;
        }
    }

    public FtpsMode getFtpsMode() {
        return ftpsMode;
    }

    public void setFtpsModetMode(String implicitModeEnabled) {
        if (!StringUtils.isEmpty(implicitModeEnabled)) {
            boolean implicitModeOn = Boolean.parseBoolean(implicitModeEnabled);
            if(implicitModeOn) {
                this.ftpsMode = FtpsMode.IMPLICIT;
            }
        }
    }

    public FtpsDataChannelProtectionLevel getFtpsProtectionLevel() {
        return channelProtectionLevel;
    }

    public void setProtectionMode(String protectionMode) throws InvalidConfigurationException {
        if (!StringUtils.isEmpty(protectionMode)) {
            if ("P".equalsIgnoreCase(protectionMode)) {
                this.channelProtectionLevel = FtpsDataChannelProtectionLevel.P;
            } else if ("C".equalsIgnoreCase(protectionMode)) {
                this.channelProtectionLevel = FtpsDataChannelProtectionLevel.C;
            } else if ("S".equalsIgnoreCase(protectionMode)) {
                this.channelProtectionLevel = FtpsDataChannelProtectionLevel.S;
            } else if ("E".equalsIgnoreCase(protectionMode)) {
                this.channelProtectionLevel = FtpsDataChannelProtectionLevel.E;
            } else {
                throw new InvalidConfigurationException("Parameter 'channelProtectionLevel' "
                        + "does not contain valid input");
            }
        }
    }
}
