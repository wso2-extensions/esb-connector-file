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
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileServerProtocol;
import org.wso2.integration.connector.core.pool.Configuration;
import org.wso2.integration.connector.core.util.ConnectorUtils;
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

    private boolean isEncryptionEnabled =  false;
    
    private boolean fileCacheEnabled = true;
    private boolean suspendOnConnectionFailure = true;
    private int retriesBeforeSuspension = 0;
    private long suspendInitialDuration = 1000;
    private double suspendProgressionFactor = 1.0;
    private long suspendMaximumDuration = 300000;

    public ConnectionConfiguration(MessageContext messageContext) {

        this.configuration = ConnectorUtils.getPoolConfiguration(messageContext);
        // Set default values
        this.configuration.setExhaustedAction("WHEN_EXHAUSTED_BLOCK");
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

    public int getRetryCount() {
        return configuration.getRetryCount();
    }

    public void setRetryCount(int retryCount) {
        this.configuration.setRetryCount(retryCount);
    }

    public boolean isEncryptionEnabled() {
        return isEncryptionEnabled;
    }

    public void setEnableEncryption(String enableEncryption) throws InvalidConfigurationException {

        if (StringUtils.isNotEmpty(enableEncryption)) {
            if ("true".equalsIgnoreCase(enableEncryption) || "false".equalsIgnoreCase(enableEncryption)) {
                this.isEncryptionEnabled = Boolean.parseBoolean(enableEncryption);
            } else {
                throw new InvalidConfigurationException("Parameter 'enableEncryption' must be 'true' or 'false'.");
            }
        } else {
            this.isEncryptionEnabled = false;
        }
    }

    public boolean isFileCacheEnabled() {
        return fileCacheEnabled;
    }

    public void setFileCacheEnabled(String fileCacheEnabled) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(fileCacheEnabled)) {
            if ("true".equalsIgnoreCase(fileCacheEnabled) || "false".equalsIgnoreCase(fileCacheEnabled)) {
                this.fileCacheEnabled = Boolean.parseBoolean(fileCacheEnabled);
            } else {
                throw new InvalidConfigurationException("Parameter 'fileCacheEnabled' must be 'true' or 'false'.");
            }
        }
    }

    public boolean isSuspendOnConnectionFailure() {
        return suspendOnConnectionFailure;
    }

    public void setSuspendOnConnectionFailure(String suspendOnConnectionFailure) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(suspendOnConnectionFailure)) {
            if ("true".equalsIgnoreCase(suspendOnConnectionFailure) || "false".equalsIgnoreCase(suspendOnConnectionFailure)) {
                this.suspendOnConnectionFailure = Boolean.parseBoolean(suspendOnConnectionFailure);
            } else {
                throw new InvalidConfigurationException("Parameter 'suspendOnConnectionFailure' must be 'true' or 'false'.");
            }
        }
    }

    public int getRetriesBeforeSuspension() {
        return retriesBeforeSuspension;
    }

    public void setRetriesBeforeSuspension(String retriesBeforeSuspension) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(retriesBeforeSuspension)) {
            if (!StringUtils.isNumeric(retriesBeforeSuspension)) {
                throw new InvalidConfigurationException("Parameter 'retriesBeforeSuspension' does not contain a numeric value.");
            }
            this.retriesBeforeSuspension = Integer.parseInt(retriesBeforeSuspension);
        }
    }

    public long getSuspendInitialDuration() {
        return suspendInitialDuration;
    }

    public void setSuspendInitialDuration(String suspendInitialDuration) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(suspendInitialDuration)) {
            try {
                this.suspendInitialDuration = Long.parseLong(suspendInitialDuration);
            } catch (NumberFormatException e) {
                throw new InvalidConfigurationException("Parameter 'suspendInitialDuration' does not contain a valid numeric value.");
            }
        }
    }

    public double getSuspendProgressionFactor() {
        return suspendProgressionFactor;
    }

    public void setSuspendProgressionFactor(String suspendProgressionFactor) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(suspendProgressionFactor)) {
            try {
                this.suspendProgressionFactor = Double.parseDouble(suspendProgressionFactor);
            } catch (NumberFormatException e) {
                throw new InvalidConfigurationException("Parameter 'suspendProgressionFactor' does not contain a valid numeric value.");
            }
        }
    }

    public long getSuspendMaximumDuration() {
        return suspendMaximumDuration;
    }

    public void setSuspendMaximumDuration(String suspendMaximumDuration) throws InvalidConfigurationException {
        if (StringUtils.isNotEmpty(suspendMaximumDuration)) {
            try {
                this.suspendMaximumDuration = Long.parseLong(suspendMaximumDuration);
            } catch (NumberFormatException e) {
                throw new InvalidConfigurationException("Parameter 'suspendMaximumDuration' does not contain a valid numeric value.");
            }
        }
    }

}
