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

import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FTPConnectionConfig;
import org.wso2.carbon.connector.pojo.FTPSConnectionConfig;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.RemoteServerConfig;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;

/**
 * Initializes the file connection based on provided configs
 * at config/init.xml. Required input validations also
 * done here.
 */
public class FileConfig extends AbstractConnector implements ManagedLifecycle {

    private static final String OPERATION_NAME = "init";

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        //do nothing on deployment - configs unknown by that time
    }

    @Override
    public void destroy() {
        //TODO: check. Seems we need to fix connector core
        ConnectionHandler.getConnectionHandler().
                shutdownConnections(FileConnectorConstants.CONNECTOR_NAME);
    }

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String connectorName = FileConnectorConstants.CONNECTOR_NAME;
        String connectionName = (String) ConnectorUtils.
                lookupTemplateParamater(messageContext, FileConnectorConstants.CONNECTION_NAME);
        try {
            ConnectionConfiguration configuration = getConnectionConfigFromContext(messageContext);

            ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
            if (!handler.checkIfConnectionExists(connectorName, connectionName)) {
                FileSystemHandler fileSystemHandler = new FileSystemHandler(configuration);
                handler.createConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName, fileSystemHandler);
            }
        } catch (InvalidConfigurationException e) {
            FileConnectorUtils.setErrorPropertiesToMessage(messageContext, Error.INVALID_CONFIGURATION);
            FileOperationResult result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.INVALID_CONFIGURATION,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException("[" + connectionName + "]Failed to initiate file connector configuration.", e, messageContext);
        } catch (FileServerConnectionException e) {
            //TODO: do we retry here?
            FileConnectorUtils.setErrorPropertiesToMessage(messageContext, Error.CONNECTION_ERROR);
            FileOperationResult result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.CONNECTION_ERROR,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException("[" + connectionName + "]Failed to connect to configured file server.", e, messageContext);
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
                lookupTemplateParamater(msgContext, FileConnectorConstants.CONNECTION_NAME);
        String protocol = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.PROTOCOL);
        String workingDir = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.WORKING_DIR);
        String maxFailureRetryCount = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.MAX_FAILURE_RETRY_COUNT);

        ConnectionConfiguration connectionConfig = new ConnectionConfiguration();
        connectionConfig.setConnectionName(connectionName);
        connectionConfig.setProtocol(protocol);
        connectionConfig.setWorkingDir(workingDir);
        connectionConfig.setMaxFailureRetryCount(maxFailureRetryCount);

        if (connectionConfig.isRemote()) {
            connectionConfig.setRemoteServerConfig(getRemoteServerConfig(msgContext, connectionConfig));
        }

        return connectionConfig;
    }

    private RemoteServerConfig getRemoteServerConfig(MessageContext msgContext, ConnectionConfiguration configuration)
            throws InvalidConfigurationException {

        RemoteServerConfig remoteServerConfig;

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
            default:
                throw new InvalidConfigurationException("Unknown File Connector protocol");
        }

        return remoteServerConfig;

    }

    private void setCommonRemoteServerConfigs(MessageContext msgContext, RemoteServerConfig remoteServerConfig)
            throws InvalidConfigurationException {
        String protocol = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.PROTOCOL);
        String host = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.HOST);
        String port = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.PORT);
        String userName = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.USERNAME);
        String password = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.PASSWORD);
        String userDirIsRoot = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.USERDIR_IS_ROOT);

        remoteServerConfig.setProtocol(protocol);
        remoteServerConfig.setHost(host);
        remoteServerConfig.setPort(port);
        remoteServerConfig.setUsername(userName);
        remoteServerConfig.setPassword(password);
        remoteServerConfig.setUserDirIsRoot(userDirIsRoot);
    }

    private void setFTPConnectionConfigsFromContext(MessageContext msgContext, FTPConnectionConfig config)
            throws InvalidConfigurationException {
        String isPassive = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.IS_PASSIVE);
        String connectionTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.CONNECTION_TIMEOUT);
        String socketTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.SOCKET_TIMEOUT);
        config.setPassive(isPassive);
        config.setConnectionTimeout(connectionTimeout);
        config.setSocketTimeout(socketTimeout);

    }

    private void setFTPSConnectionConfigsFromContext(MessageContext msgContext, FTPSConnectionConfig config)
            throws InvalidConfigurationException {
        String keyStorePath = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.KEYSTORE_PATH);
        String keyStorePassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.KEYSTORE_PASSWORD);
        String trustStorePath = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.TRUSTSTORE_PATH);
        String trustStorePassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.TRUSTSTORE_PASSWORD);
        String implicitModeEnabled = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.IMPLICIT_MODE_ENABLED);
        String channelProtectionLevel = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.CHANNEL_PROTECTION_LEVEL);

        setFTPConnectionConfigsFromContext(msgContext, config);
        config.setKeyStore(keyStorePath);
        config.setKeyStorePassword(keyStorePassword);
        config.setTrustStore(trustStorePath);
        config.setTrustStorePassword(trustStorePassword);
        config.setFtpsModetMode(implicitModeEnabled);
        config.setProtectionMode(channelProtectionLevel);

    }

    private void setSFTPConnectionConfigsFromContext(MessageContext msgContext, SFTPConnectionConfig config)
            throws InvalidConfigurationException {
        String sftpConnectionTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.SFTP_CONNECTION_TIMEOUT);
        String sftpSessionTimeout = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.SFTP_SESSION_TIMEOUT);
        String strictHostKeyChecking = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.STRICT_HOST_KEY_CHECKING);
        String privateKeyFilePath = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.PRIVATE_KEY_FILE_PATH);
        String privateKeyPassword = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, FileConnectorConstants.PRIVATE_KEY_PASSWORD);

        config.setConnectionTimeout(sftpConnectionTimeout);
        config.setSessionTimeout(sftpSessionTimeout);
        config.setStrictHostKeyChecking(strictHostKeyChecking);
        config.setPrivateKeyFilePath(privateKeyFilePath);
        config.setPrivateKeyPassword(privateKeyPassword);
    }
}
