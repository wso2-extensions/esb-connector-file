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
 * Configurations common to any remote file server
 */
public abstract class RemoteServerConfig {

    //Host name/IP of file server to connect
    private String host;

    //Port of the file server to connect
    private int port;

    //Username used to connect to file server
    private String username;

    //password used to connect to file server
    private String password;

    /**
     * True if connector should treat the user
     * directory as the root directory
     */
    private boolean userDirIsRoot;

    //TODO: what abt these params?
    //int responseTimeout;
    //int reconnectAttemptInterval;
    //int maxConnectionRetryCount;

    public RemoteServerConfig() {
        this.userDirIsRoot = false;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) throws InvalidConfigurationException {
        if(StringUtils.isNotEmpty(host)) {
            this.host = host;
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'host' is not provided");
        }
    }

    public int getPort() {
        return port;
    }

    public void setPort(String port) throws InvalidConfigurationException {
        if(StringUtils.isNotEmpty(port)) {
            if (StringUtils.isNumeric(port)) {
                this.port = Integer.parseInt(port);
            } else {
                throw new InvalidConfigurationException("Parameter 'port' does not contain numeric value");
            }
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'port' is not provided");
        }
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) throws InvalidConfigurationException {
        if(StringUtils.isNotEmpty(username)) {
            this.username = username;
        } else {
            throw new InvalidConfigurationException("Mandatory Parameter 'userName' is not provided");
        }
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) throws InvalidConfigurationException {
        if(StringUtils.isNotEmpty(password)) {
            this.password = password;
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'password' is not provided ");
        }
    }

    public boolean isUserDirIsRoot() {
        return userDirIsRoot;
    }

    public void setUserDirIsRoot(String userDirIsRoot) {
        if(StringUtils.isNotEmpty(userDirIsRoot)) {
            this.userDirIsRoot = Boolean.parseBoolean(userDirIsRoot);
        }
    }
}
