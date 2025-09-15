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

package org.wso2.carbon.connector.operations;

import com.google.gson.JsonObject;
import org.apache.axis2.AxisFault;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.connection.SFTPConnectionFactory;
import org.wso2.carbon.connector.connection.SMBConnectionFactory;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.integration.connector.core.pool.Configuration;
import org.wso2.carbon.connector.deploy.ConnectorUndeployObserver;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FTPConnectionConfig;
import org.wso2.carbon.connector.pojo.FTPSConnectionConfig;
import org.wso2.carbon.connector.pojo.RemoteServerConfig;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import static org.wso2.carbon.connector.utils.Const.IS_VALID_CONNECTION;
import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Initializes the file connection based on provided configs
 * at config/init.xml. Required input validations also
 * done here.
 */
public class FileConfig extends AbstractConnectorOperation implements ManagedLifecycle {

    private static final String OPERATION_NAME = "init";

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        SynapseConfiguration synapseConfig = synapseEnvironment.getSynapseConfiguration();
        synapseConfig.registerObserver(new ConnectorUndeployObserver(synapseConfig));
    }

    @Override
    public void destroy() {
        throw new UnsupportedOperationException("Destroy method of Config init is not supposed to be called");
    }

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {
        connect(messageContext);
    }

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String connectorName = Const.CONNECTOR_NAME;
        String connectionName = (String) ConnectorUtils.
                lookupTemplateParamater(messageContext, Const.CONNECTION_NAME);
        String tenantSpecificConnectionName = Utils.getTenantSpecificConnectionName(connectionName, messageContext);
        try {
            ConnectionConfiguration configuration = getConnectionConfigFromContext(messageContext);

            ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
            if ("SFTP".equalsIgnoreCase(configuration.getProtocol().getName())) {
                try {
                    handler.createConnection(Const.CONNECTOR_NAME, tenantSpecificConnectionName,
                            new SFTPConnectionFactory(configuration), configuration.getConfiguration(), messageContext);
                } catch (NoSuchMethodError e) {
                    handler.createConnection(Const.CONNECTOR_NAME, tenantSpecificConnectionName,
                            new SFTPConnectionFactory(configuration), configuration.getConfiguration());
                }
            } else if ("SMB".equalsIgnoreCase(configuration.getProtocol().getName())
                    || "SMB2".equalsIgnoreCase(configuration.getProtocol().getName())) {
                try {
                    handler.createConnection(Const.CONNECTOR_NAME, tenantSpecificConnectionName,
                            new SMBConnectionFactory(configuration), configuration.getConfiguration(), messageContext);
                } catch (NoSuchMethodError e) {
                    handler.createConnection(Const.CONNECTOR_NAME, tenantSpecificConnectionName,
                            new SMBConnectionFactory(configuration), configuration.getConfiguration());
                }
            } else if (!handler.checkIfConnectionExists(connectorName, tenantSpecificConnectionName)) {
                FileSystemHandler fileSystemHandler = new FileSystemHandler(configuration);
                try {
                    handler.createConnection(Const.CONNECTOR_NAME, tenantSpecificConnectionName, fileSystemHandler
                            , messageContext);
                } catch (NoSuchMethodError e) {
                    handler.createConnection(Const.CONNECTOR_NAME, tenantSpecificConnectionName, fileSystemHandler);
                }
            }
        } catch (InvalidConfigurationException e) {

            String errorDetail = "[" + connectionName + "]Failed to initiate file connector configuration.";
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (FileServerConnectionException e) {

            String errorDetail = "[" + connectionName + "]Failed to connect to configured file server.";
            handleError(messageContext, e, Error.CONNECTION_ERROR, errorDetail);
        }
    }

    /**
     * Create a configuration object from connector
     * init parameters provided.
     *
     * @param msgContext MessageContext to lookup values of parameters
     * @return Created config object
     * @throws InvalidConfigurationException If any input validation failed
     */
    private ConnectionConfiguration getConnectionConfigFromContext(MessageContext msgContext)
            throws InvalidConfigurationException {

        String connectionName = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.CONNECTION_NAME);
        String protocol = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PROTOCOL);
        String workingDir = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.WORKING_DIR);
        String fileLockScheme = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.FILE_LOCK_SCHEME);
        String maxFailureRetryCount = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.MAX_FAILURE_RETRY_COUNT);
        String sftpPoolConnectionAgedTimeout = (String) ConnectorUtils.lookupTemplateParamater(msgContext, Const.SFTP_POOL_CONNECTION_AGED_TIMEOUT);

        String retryCount = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.RETRY_COUNT);
        String enableEncryption = (String) ConnectorUtils.lookupTemplateParamater(msgContext, Const.ENABLE_ENCRYPTION);
        
        // Connection suspension parameters (handled by connector core)
        String suspendOnConnectionFailure = (String) ConnectorUtils.lookupTemplateParamater(msgContext, "suspendOnConnectionFailure");
        String retriesBeforeSuspension = (String) ConnectorUtils.lookupTemplateParamater(msgContext, "retriesBeforeSuspension");
        String suspendInitialDuration = (String) ConnectorUtils.lookupTemplateParamater(msgContext, "suspendInitialDuration");
        String suspendProgressionFactor = (String) ConnectorUtils.lookupTemplateParamater(msgContext, "suspendProgressionFactor");
        String suspendMaximumDuration = (String) ConnectorUtils.lookupTemplateParamater(msgContext, "suspendMaximumDuration");
        
        // File caching parameter (handled by VFS library)
        String fileCacheEnabled = (String) ConnectorUtils.lookupTemplateParamater(msgContext, "fileCacheEnabled");

        ConnectionConfiguration connectionConfig = new ConnectionConfiguration(msgContext);
        if (sftpPoolConnectionAgedTimeout != null) {
            try {
                connectionConfig.setPoolConnectionAgedTimeout(Long.parseLong(sftpPoolConnectionAgedTimeout));
            } catch (NumberFormatException numberFormatException) {
                log.warn("Ignore setting SFTP_POOL_CONNECTION_AGED_TIMEOUT property. Since the value is not set " +
                        "properly");
            }
        }
        if (retryCount != null) {
            try {
                connectionConfig.setRetryCount(Integer.parseInt(retryCount));
            } catch (NumberFormatException numberFormatException) {
                log.warn("Ignore setting RETRY_COUNT property. Since the value is not set properly");
            }
        }
        connectionConfig.setConnectionName(connectionName);
        connectionConfig.setProtocol(protocol);
        connectionConfig.setWorkingDir(workingDir);
        connectionConfig.setClusterLockingEnabled(fileLockScheme);
        connectionConfig.setMaxFailureRetryCount(maxFailureRetryCount);
        connectionConfig.setEnableEncryption(enableEncryption);
        
        // Set file caching configuration (handled by VFS)
        connectionConfig.setFileCacheEnabled(fileCacheEnabled);
        
        // Set connection suspension parameters
        connectionConfig.setSuspendOnConnectionFailure(suspendOnConnectionFailure);
        connectionConfig.setRetriesBeforeSuspension(retriesBeforeSuspension);
        connectionConfig.setSuspendInitialDuration(suspendInitialDuration);
        connectionConfig.setSuspendProgressionFactor(suspendProgressionFactor);
        connectionConfig.setSuspendMaximumDuration(suspendMaximumDuration);
        
        // Configure circuit breaker if suspension is enabled
        if (connectionConfig.isSuspendOnConnectionFailure()) {
            Configuration config = connectionConfig.getConfiguration();
            config.setCircuitBreakerEnabled(true);
            config.setFailureThreshold(connectionConfig.getRetriesBeforeSuspension());
            config.setOpenDurationMillis(connectionConfig.getSuspendInitialDuration());
            config.setOpenDurationProgressFactor((int) connectionConfig.getSuspendProgressionFactor());
            config.setMaxOpenDurationMillis(connectionConfig.getSuspendMaximumDuration());
            
            if (log.isDebugEnabled()) {
                log.debug("Circuit breaker enabled for connection: " + connectionName + 
                    ", failureThreshold: " + connectionConfig.getRetriesBeforeSuspension() +
                    ", initialDuration: " + connectionConfig.getSuspendInitialDuration() +
                    ", progressionFactor: " + (int) connectionConfig.getSuspendProgressionFactor() +
                    ", maxDuration: " + connectionConfig.getSuspendMaximumDuration());
            }
        }

        if (connectionConfig.isRemote()) {
            connectionConfig.setRemoteServerConfig(getRemoteServerConfig(msgContext, connectionConfig));
        }

        return connectionConfig;
    }

    private RemoteServerConfig getRemoteServerConfig(MessageContext msgContext, ConnectionConfiguration configuration)
            throws InvalidConfigurationException {

        RemoteServerConfig remoteServerConfig = null;

        switch (configuration.getProtocol()) {
            case FTP:
                remoteServerConfig = new FTPConnectionConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                setFTPConnectionConfigsFromContext(msgContext, (FTPConnectionConfig) remoteServerConfig);
                configuration.setRemoteServerConfig(remoteServerConfig);
                break;
            case FTPS:
                remoteServerConfig = new FTPSConnectionConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                setFTPSConnectionConfigsFromContext(msgContext, (FTPSConnectionConfig) remoteServerConfig);
                configuration.setRemoteServerConfig(remoteServerConfig);
                break;
            case SFTP:
                remoteServerConfig = new SFTPConnectionConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                setSFTPConnectionConfigsFromContext(msgContext, (SFTPConnectionConfig) remoteServerConfig);
                configuration.setRemoteServerConfig(remoteServerConfig);
                break;
            case SMB:
            case SMB2:
                remoteServerConfig = new RemoteServerConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                configuration.setRemoteServerConfig(remoteServerConfig);
                break;
            default:
                throw new InvalidConfigurationException("Unknown File Connector protocol");
        }

        return remoteServerConfig;

    }

    private void setCommonRemoteServerConfigs(MessageContext msgContext, RemoteServerConfig remoteServerConfig)
            throws InvalidConfigurationException {
        String protocol = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PROTOCOL);
        String host = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.HOST);
        String port = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PORT);
        String userName = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.USERNAME);
        String password = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PASSWORD);
        String passwordEncodingRequired = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.ENCODE_PASSWORD);
        String userDirIsRoot = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.USERDIR_IS_ROOT);

        log.info("passwordEncodingRequired: " + passwordEncodingRequired);

        remoteServerConfig.setProtocol(protocol);
        remoteServerConfig.setHost(host);
        remoteServerConfig.setPort(port);
        remoteServerConfig.setUsername(userName);
        try {
            if (Boolean.parseBoolean(passwordEncodingRequired)) {
                password = encodePassword(password);
            }
            remoteServerConfig.setPassword(password);
        } catch (UnsupportedEncodingException e) {
            handleException("Error while encoding password", e, msgContext);
        }
        remoteServerConfig.setUserDirIsRoot(userDirIsRoot);
    }

    private void setFTPConnectionConfigsFromContext(MessageContext msgContext, FTPConnectionConfig config)
            throws InvalidConfigurationException {
        String isPassive = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.IS_PASSIVE);
        String connectionTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.CONNECTION_TIMEOUT);
        String socketTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.SOCKET_TIMEOUT);
        config.setPassive(isPassive);
        config.setConnectionTimeout(connectionTimeout);
        config.setSocketTimeout(socketTimeout);

    }

    private void setFTPSConnectionConfigsFromContext(MessageContext msgContext, FTPSConnectionConfig config)
            throws InvalidConfigurationException {
        String keyStorePath = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.KEYSTORE_PATH);
        String keyStorePassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.KEYSTORE_PASSWORD);
        String keyPassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.KEY_PASSWORD);
        String trustStorePath = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.TRUSTSTORE_PATH);
        String trustStorePassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.TRUSTSTORE_PASSWORD);
        String implicitModeEnabled = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.IMPLICIT_MODE_ENABLED);
        String channelProtectionLevel = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.CHANNEL_PROTECTION_LEVEL);

        setFTPConnectionConfigsFromContext(msgContext, config);
        config.setKeyStore(keyStorePath);
        config.setKeyStorePassword(keyStorePassword);
        config.setKeyPassword(keyPassword);
        config.setTrustStore(trustStorePath);
        config.setTrustStorePassword(trustStorePassword);
        config.setFtpsModetMode(implicitModeEnabled);
        config.setProtectionMode(channelProtectionLevel);

    }

    private void setSFTPConnectionConfigsFromContext(MessageContext msgContext, SFTPConnectionConfig config)
            throws InvalidConfigurationException {
        String sftpConnectionTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.SFTP_CONNECTION_TIMEOUT);
        String sftpSessionTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.SFTP_SESSION_TIMEOUT);
        String strictHostKeyChecking = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.STRICT_HOST_KEY_CHECKING);
        String privateKeyFilePath = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PRIVATE_KEY_FILE_PATH);
        String privateKeyPassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PRIVATE_KEY_PASSWORD);
        String setAvoidPermission = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.SET_AVOID_PERMISSION);
        String sftpPathFromRoot = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.SFTP_PATH_FROM_ROOT);

        config.setConnectionTimeout(sftpConnectionTimeout);
        config.setSessionTimeout(sftpSessionTimeout);
        config.setStrictHostKeyChecking(strictHostKeyChecking);
        config.setPrivateKeyFilePath(privateKeyFilePath);
        config.setPrivateKeyPassword(privateKeyPassword);
        config.setAvoidPermissionCheck(setAvoidPermission);
        config.setSftpPathFromRoot(sftpPathFromRoot);
    }

    /**
     * Sets error to context and handle.
     *
     * @param msgCtx      Message Context to set info
     * @param e           Exception associated
     * @param error       Error code
     * @param errorDetail Error detail
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        FileOperationResult result = new FileOperationResult(OPERATION_NAME, false, error, e.getMessage());
        JsonObject resultJSON = generateOperationResult(msgCtx, result);
        try {
            JsonUtil.getNewJsonPayload(((Axis2MessageContext)msgCtx).getAxis2MessageContext(), resultJSON.toString(),
                    false, false);
        } catch (AxisFault axisFault) {
            log.error("Error while setting the error payload", axisFault);
        }
        handleException(errorDetail, e, msgCtx);
    }

    public static String encodePassword(String password) throws UnsupportedEncodingException {
        String encodedPassword = null;
        if (StringUtils.isNotEmpty(password)) {
            encodedPassword = URLEncoder.encode(password, "UTF-8");
        }
        return URLEncoder.encode(password, "UTF-8");
    }
}
