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

package org.wso2.carbon.connector.utils;

/**
 * Contains constants used in File Connector
 */
public final class FileConnectorConstants {

    public static final String CONNECTOR_NAME = "file";

    //Template Parameters

    public static final String PROPERTY_ERROR_CODE = "ERROR_CODE";
    public static final String PROPERTY_ERROR_MESSAGE = "ERROR_MESSAGE";


    public static final String CONNECTION_NAME = "name";
    public static final String PROTOCOL = "connectionType";
    public static final String WORKING_DIR = "workingDir";
    public static final String MAX_FAILURE_RETRY_COUNT = "maxFailureRetryCount";

    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String USERDIR_IS_ROOT = "userDirIsRoot";

    public static final String IS_PASSIVE = "isPassive";
    public static final String CONNECTION_TIMEOUT = "ftpConnectionTimeout";
    public static final String SOCKET_TIMEOUT = "ftpSocketTimeout";
    public static final String KEYSTORE_PATH = "keyStorePath";
    public static final String KEYSTORE_PASSWORD = "keyStorePassword";
    public static final String TRUSTSTORE_PATH = "trustStorePath";
    public static final String TRUSTSTORE_PASSWORD = "trustStorePassword";
    public static final String IMPLICIT_MODE_ENABLED = "implicitModeEnabled";
    public static final String CHANNEL_PROTECTION_LEVEL = "channelProtectionLevel";

    public static final String SFTP_CONNECTION_TIMEOUT = "sftpConnectionTimeout";
    public static final String SFTP_SESSION_TIMEOUT = "sftpSessionTimeout";
    public static final String STRICT_HOST_KEY_CHECKING = "strictHostKeyChecking";
    public static final String PRIVATE_KEY_FILE_PATH = "privateKeyFilePath";
    public static final String PRIVATE_KEY_PASSWORD = "privateKeyPassword";

    public static final String DIRECTORY_PATH = "directoryPath";
    public static final String FILE_OR_DIRECTORY_PATH = "path";
    public static final String LOCAL_FILE_PROTOCOL_PREFIX = "file://";
    public static final String FTP_PROTOCOL_PREFIX = "ftp://";
    public static final String FTPS_PROTOCOL_PREFIX = "ftps://";
    public static final String SFTP_PROTOCOL_PREFIX = "sftp://";

    public static final String FILE_ELEMENT = "file";
    public static final String STATUS_CODE = "HTTP_SC";
    public static final Object HTTP_STATUS_500 = "500";
    public static final int UNZIP_BUFFER_SIZE = 4096;

    public static final String YES = "yes";
    public static final String OVERWRITE = "Overwrite";
    public static final String FILE = "file";
}
