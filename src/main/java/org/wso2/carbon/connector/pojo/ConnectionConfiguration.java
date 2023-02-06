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
import org.wso2.carbon.connector.connection.FileServerProtocol;
import org.wso2.carbon.connector.core.pool.Configuration;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.utils.Const;

/**
 * Configuration parameters used to
 * establish a connection to the file server
 */
public class ConnectionConfiguration {

    private String connectionName;

    private Configuration configuration;

    private FileServerProtocol protocol;
    /**
     * Defaults to user's home dir. All the file paths
     * used at connector operations should be specified
     * respect to this path
     */
    private String workingDir;

    private int maxFailureRetryCount = 0;

    private boolean isClusterLockingEnabled = false;

    /**
     * Configs related to remote server
     * (only set when isRemote=true)
     */
    private RemoteServerConfig remoteServerConfig;
    private boolean isRemote = false;

    private long poolConnectionAgedTimeout;

    public ConnectionConfiguration() {

        this.configuration = new Configuration();
        // Set default values
        this.configuration.setExhaustedAction("WHEN_EXHAUSTED_FAIL");
        this.configuration.setTestOnBorrow(true);
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(connectionName)) {
            this.connectionName = connectionName;
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'connectionName' is not set.");
        }
    }

    public boolean isRemote() {
        return isRemote;
    }

    public FileServerProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(protocol)) {
            this.protocol = FileServerProtocol.valueOf(protocol);
            if (!this.protocol.equals(FileServerProtocol.LOCAL)) {
                isRemote = true;
            }
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'protocol' is not set.");
        }
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        if(StringUtils.isNotEmpty(workingDir)) {
            this.workingDir = workingDir;
        }
    }

    public boolean isClusterLockingEnabled() {
        return isClusterLockingEnabled;
    }

    public void setClusterLockingEnabled(String fileLockScope) throws InvalidConfigurationException {
        if(StringUtils.isEmpty(fileLockScope)) {
            throw new InvalidConfigurationException("Parameter 'fileLockScheme' contains empty value.");
        }
        if(fileLockScope.equals(Const.LOCAL_FILE_LOCK_SCHEME)) {
            isClusterLockingEnabled = false;
        } else if (fileLockScope.equals(Const.CLUSTER_FILE_LOCK_SCHEME)) {
            isClusterLockingEnabled = true;
        } else {
            throw new InvalidConfigurationException("Parameter 'fileLockScheme' contains an unknown value.");
        }
    }

    public int getMaxFailureRetryCount() {
        return maxFailureRetryCount;
    }

    public void setMaxFailureRetryCount(String maxFailureRetryCount) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(maxFailureRetryCount)) {
            if(!StringUtils.isNumeric(maxFailureRetryCount)) {
                throw new InvalidConfigurationException("Parameter 'protocol' does not contain a numeric value.");
            }
            this.maxFailureRetryCount = Integer.parseInt(maxFailureRetryCount);
        }
    }

    public RemoteServerConfig getRemoteServerConfig() {
        return remoteServerConfig;
    }

    public void setRemoteServerConfig(RemoteServerConfig remoteServerConfig) {
        this.remoteServerConfig = remoteServerConfig;
    }
    public int getMaxActiveConnections() {

        return configuration.getMaxActiveConnections();
    }

    public void setMaxActiveConnections(int maxActiveConnections) {

        this.configuration.setMaxActiveConnections(maxActiveConnections);
    }

    public int getMaxIdleConnections() {

        return configuration.getMaxIdleConnections();
    }

    public void setMaxIdleConnections(int maxIdleConnections) {

        this.configuration.setMaxIdleConnections(maxIdleConnections);
    }

    public long getMaxWaitTime() {

        return configuration.getMaxWaitTime();
    }

    public void setMaxWaitTime(long maxWaitTime) {

        this.configuration.setMaxWaitTime(maxWaitTime);
    }

    public long getMinEvictionTime() {

        return configuration.getMinEvictionTime();
    }

    public void setMinEvictionTime(long minEvictionTime) {

        this.configuration.setMinEvictionTime(minEvictionTime);
    }

    public long getEvictionCheckInterval() {

        return configuration.getEvictionCheckInterval();
    }

    public void setEvictionCheckInterval(long evictionCheckInterval) {

        this.configuration.setEvictionCheckInterval(evictionCheckInterval);
    }

    public String getExhaustedAction() {

        return configuration.getExhaustedAction();
    }

    public void setExhaustedAction(String exhaustedAction) {

        this.configuration.setExhaustedAction(exhaustedAction);
    }

    public Configuration getConfiguration() {

        return configuration;
    }

    public void setConfiguration(Configuration configuration) {

        this.configuration = configuration;
    }

    public long getPoolConnectionAgedTimeout() {
        return poolConnectionAgedTimeout;
    }

    public void setPoolConnectionAgedTimeout(long poolConnectionAgedTimeout) {
        this.configuration.setPoolConnectionAgedTimeout(poolConnectionAgedTimeout);
        this.poolConnectionAgedTimeout = poolConnectionAgedTimeout;
    }
}
