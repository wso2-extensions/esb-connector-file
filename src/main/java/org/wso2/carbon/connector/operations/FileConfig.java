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

import org.wso2.vfs.shared.log.UriMask;
import com.google.gson.JsonObject;
import org.apache.axis2.AxisFault;
import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.connector.connection.FileServerProtocol;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileType;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

import java.time.Duration;
import java.util.function.Consumer;
import org.apache.synapse.ManagedLifecycle;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.apache.synapse.libraries.model.Library;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.integration.connector.core.pool.Configuration;
import org.wso2.carbon.connector.deploy.ConnectorUndeployObserver;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FTPConnectionConfig;
import org.wso2.carbon.connector.pojo.FTPSConnectionConfig;
import org.wso2.carbon.connector.pojo.RemoteServerConfig;
import org.wso2.carbon.connector.pojo.SFTPConnectionConfig;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;

import javax.xml.namespace.QName;

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
        TestPoints.copy("connector", FileConfig.class, EndpointHealth.class);
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
        String displayName = Utils.getDisplayConnectionName(connectionName, messageContext);
        NamedLog connectionLog = displayName == null ? null :
                NamedLog.connection(log, displayName, new RepeatLimiter());
        try {
            ConnectionConfiguration configuration = getConnectionConfigFromContext(messageContext, connectionLog);

            ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
            handler.createConnection(connectorName, tenantSpecificConnectionName,
                    new FileSystemHandlerFactory(configuration, deployedKey(messageContext), tenantSpecificConnectionName,
                            displayName), configuration.getConfiguration(), messageContext);
        } catch (InvalidConfigurationException e) {

            String errorDetail = "[" + connectionName + "]Failed to initiate file connector configuration.";
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, connectionLog);

        }
    }

    private String deployedKey(MessageContext messageContext) {
        for (Library library : messageContext.getConfiguration().getSynapseLibraries().values()) {
            if (library.getLibClassLoader() == FileConfig.class.getClassLoader()) {
                QName name = library.getQName();
                return "{" + name.getNamespaceURI() + "}" + name.getLocalPart();
            }
        }
        return Const.CONNECTOR_NAME;
    }

    /**
     * Create a configuration object from connector
     * init parameters provided.
     *
     * @param msgContext MessageContext to lookup values of parameters
     * @return Created config object
     * @throws InvalidConfigurationException If any input validation failed
     */
    private ConnectionConfiguration getConnectionConfigFromContext(MessageContext msgContext, NamedLog connectionLog)
            throws InvalidConfigurationException {

        String connectionName = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.CONNECTION_NAME);
        String protocol = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.PROTOCOL);
        String workingDir = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, Const.WORKING_DIR);
        String fileLockScheme = Utils.lookUpStringParam(msgContext, Const.FILE_LOCK_SCHEME, Const.LOCAL_FILE_LOCK_SCHEME);
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
        if (connectionConfig.getMaxWaitTime() <= 0) {
            warn(connectionLog, "maxWaitTime " + connectionConfig.getMaxWaitTime()
                    + " gives a borrow wait with no end. Using 10000 ms.");
            connectionConfig.setMaxWaitTime(10000L);
        }
        if (sftpPoolConnectionAgedTimeout != null) {
            try {
                connectionConfig.setPoolConnectionAgedTimeout(
                        Math.multiplyExact(Long.parseLong(sftpPoolConnectionAgedTimeout), 1000L));
            } catch (NumberFormatException | ArithmeticException invalidAge) {
                warn(connectionLog, "Invalid sftpPoolConnectionAgedTimeout. Ignoring the value.");
            }
        }
        if (retryCount != null) {
            try {
                connectionConfig.setRetryCount(Integer.parseInt(retryCount));
            } catch (NumberFormatException numberFormatException) {
                warn(connectionLog, "Invalid retryCount. Ignoring the value.");
            }
        }
        connectionConfig.setConnectionName(connectionName);
        connectionConfig.setProtocol(protocol);
        connectionConfig.setWorkingDir(workingDir);
        connectionConfig.setClusterLockingEnabled(fileLockScheme);
        if (StringUtils.isNotEmpty(maxFailureRetryCount)) {
            warn(connectionLog, "maxFailureRetryCount is deprecated and has no effect.");
        }
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
            config.setOpenDurationProgressFactor(connectionConfig.getSuspendProgressionFactor());
            config.setMaxOpenDurationMillis(connectionConfig.getSuspendMaximumDuration());
            
            if (connectionLog != null && connectionLog.isDebugEnabled()) {
                connectionLog.debug("Circuit breaker enabled" +
                    ", failureThreshold: " + connectionConfig.getRetriesBeforeSuspension() +
                    ", initialDuration: " + connectionConfig.getSuspendInitialDuration() +
                    ", progressionFactor: " + connectionConfig.getSuspendProgressionFactor() +
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

        RemoteServerConfig remoteServerConfig;
        FileSystemOptions options = configuration.getProviderOptions();

        switch (configuration.getProtocol()) {
            case FTP:
                remoteServerConfig = new FTPConnectionConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                setFTPConnectionConfigsFromContext(msgContext, (FTPConnectionConfig) remoteServerConfig);
                setFtpOptions(msgContext, options, FtpFileSystemConfigBuilder.getInstance());
                break;
            case FTPS:
                remoteServerConfig = new FTPSConnectionConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                setFTPSConnectionConfigsFromContext(msgContext, (FTPSConnectionConfig) remoteServerConfig);
                setFtpOptions(msgContext, options, FtpsFileSystemConfigBuilder.getInstance());
                break;
            case SFTP:
                remoteServerConfig = new SFTPConnectionConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                setSFTPConnectionConfigsFromContext(msgContext, (SFTPConnectionConfig) remoteServerConfig);
                setSftpOptions(msgContext, options);
                break;
            case SMB:
            case SMB2:
                if (configuration.getProtocol() == FileServerProtocol.SMB2) {
                    setSmb2Options(msgContext, options);
                }
                remoteServerConfig = new RemoteServerConfig();
                setCommonRemoteServerConfigs(msgContext, remoteServerConfig);
                break;
            default:
                throw new InvalidConfigurationException("Unknown File Connector protocol");
        }

        return remoteServerConfig;

    }

    private static void setFtpOptions(MessageContext message, FileSystemOptions options,
                                      FtpFileSystemConfigBuilder builder) throws InvalidConfigurationException {
        String type = Utils.lookUpStringParam(message, "ftpTransferType", "BINARY");
        if ("ASCII".equalsIgnoreCase(type)) {
            builder.setFileType(options, FtpFileType.ASCII);
        } else if ("BINARY".equalsIgnoreCase(type)) {
            builder.setFileType(options, FtpFileType.BINARY);
        } else {
            throw new InvalidConfigurationException("Parameter 'ftpTransferType' must be BINARY or ASCII.");
        }
        setTimeout(message, "ftpDataTimeout", value -> builder.setDataTimeout(options, value));
        setTimeout(message, "writeStallTimeout", value -> builder.setWriteStallTimeout(options, value));
    }

    private static void setSftpOptions(MessageContext message, FileSystemOptions options)
            throws InvalidConfigurationException {
        SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        setOption(message, "sftpKeyExchangeAlgorithms", value -> builder.setKeyExchangeAlgorithm(options, value));
        setOption(message, "sftpHostKeyAlgorithms", value -> builder.setHostKeyAlgorithms(options, value));
        setOption(message, "sftpCiphers", value -> builder.setCiphers(options, value));
        setOption(message, "sftpMacs", value -> builder.setMacs(options, value));
        setOption(message, "sftpPubkeyAcceptedAlgorithms", value -> builder.setPubkeyAcceptedAlgorithms(options, value));
        setTimeout(message, "writeStallTimeout", value -> builder.setWriteStallTimeout(options, value));
    }

    private static void setSmb2Options(MessageContext message, FileSystemOptions options)
            throws InvalidConfigurationException {
        setTimeout(message, "smb2ReadTimeout", value -> Smb2FileSystemConfigBuilder.getInstance().setReadTimeout(options, value));
        setTimeout(message, "smb2WriteTimeout", value -> Smb2FileSystemConfigBuilder.getInstance().setWriteTimeout(options, value));
        setTimeout(message, "smb2TransactTimeout", value -> Smb2FileSystemConfigBuilder.getInstance().setTransactTimeout(options, value));
        setTimeout(message, "writeStallTimeout", value -> Smb2FileSystemConfigBuilder.getInstance().setWriteStallTimeout(options, value));
    }

    private static void setOption(MessageContext message, String name, Consumer<String> apply) {
        String value = Utils.lookUpStringParam(message, name, null);
        if (value != null) {
            apply.accept(value);
        }
    }

    private static void setTimeout(MessageContext message, String name, Consumer<Duration> apply)
            throws InvalidConfigurationException {
        String value = Utils.lookUpStringParam(message, name, null);
        if (value != null) {
            try {
                int millis = Integer.parseInt(value);
                if (millis < 0) {
                    throw new NumberFormatException();
                }
                apply.accept(Duration.ofMillis(millis));
            } catch (NumberFormatException e) {
                throw new InvalidConfigurationException("Parameter '" + name + "' must be a nonnegative number of milliseconds.");
            }
        }
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
        config.setKeepAliveCount((String) ConnectorUtils.lookupTemplateParamater(msgContext, "sftpKeepAliveCount"));
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
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail, NamedLog connectionLog) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        FileOperationResult result = new FileOperationResult(OPERATION_NAME, false, error, e.getMessage());
        JsonObject resultJSON = generateOperationResult(msgCtx, result);
        try {
            JsonUtil.getNewJsonPayload(((Axis2MessageContext)msgCtx).getAxis2MessageContext(), resultJSON.toString(),
                    false, false);
        } catch (AxisFault axisFault) {
            String detail = "Error while setting the error payload: " + axisFault.getMessage();
            if (connectionLog == null) {
                log.error(UriMask.mask(detail));
            } else {
                connectionLog.error(detail);
            }
        }
        handleException(errorDetail, new InvalidConfigurationException(UriMask.mask(e.getMessage())), msgCtx);
    }

    private void warn(NamedLog connectionLog, String message) {
        if (connectionLog == null) {
            log.warn(UriMask.mask(message));
        } else {
            connectionLog.warn(message);
        }
    }

    public static String encodePassword(String password) throws UnsupportedEncodingException {
        String encodedPassword = null;
        if (StringUtils.isNotEmpty(password)) {
            encodedPassword = URLEncoder.encode(password, "UTF-8");
        }
        return URLEncoder.encode(password, "UTF-8");
    }
}
