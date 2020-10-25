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
import org.wso2.carbon.connector.exception.InvalidConfigurationException;

/**
 * Configurations for FTP connection. VFS reference:
 * https://commons.apache.org/proper/commons-vfs/commons-vfs2/apidocs/org/apache/commons/vfs2/provider/ftp/FtpFileSystemConfigBuilder.html
 */
public class FTPConnectionConfig extends RemoteServerConfig {

    //vfs.passive. True if to enter into passive mode
    private boolean isPassive;

    //timeout for the initial control connection
    //TODO:decide default
    private int connectionTimeout;

    //the socket timeout for the FTP client
    //TODO:decide default
    private int socketTimeout;

    public FTPConnectionConfig() {
        super();
        isPassive = true;   //TODO: should default = true?
        connectionTimeout = 100000;
        socketTimeout = 150000;
    }

    public boolean isPassive() {
        return isPassive;
    }

    public void setPassive(String isPassive) {
        if (StringUtils.isNotEmpty(isPassive)) {
            this.isPassive = Boolean.parseBoolean(isPassive);
        }
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(String connectionTimeout) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(connectionTimeout)) {
            if (StringUtils.isNumeric(connectionTimeout)) {
                this.connectionTimeout = Integer.parseInt(connectionTimeout);
            } else {
                throw new InvalidConfigurationException("Parameter 'connectionTimeout' needs to be numeric");
            }

        }
    }

    public int getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(String socketTimeout) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(socketTimeout)) {
            if (StringUtils.isNumeric(socketTimeout)) {
                this.socketTimeout = Integer.parseInt(socketTimeout);
            } else {
                throw new InvalidConfigurationException("Parameter 'socketTimeout' needs to be numeric");
            }
        }
    }
}
