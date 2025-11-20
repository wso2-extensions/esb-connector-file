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
public final class Const {

    public static final String CONNECTOR_NAME = "file";

    //Template Parameters

    public static final String PROPERTY_ERROR_CODE = "ERROR_CODE";
    public static final String PROPERTY_ERROR_MESSAGE = "ERROR_MESSAGE";


    public static final String CONNECTION_NAME = "name";
    public static final String PROTOCOL = "connectionType";
    public static final String WORKING_DIR = "workingDir";
    public static final String FILE_LOCK_SCHEME = "fileLockScheme";
    public static final String MAX_FAILURE_RETRY_COUNT = "maxFailureRetryCount";
    public static final String RETRY_COUNT = "retryCount";
    public static final String SET_AVOID_PERMISSION = "setAvoidPermission";

    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String ENCODE_PASSWORD = "encodePassword";
    public static final String ENABLE_ENCRYPTION = "enableEncryption";
    public static final String DISK_SHARE_ACCESS_MASK = "diskShareAccessMask";
    public static final String DISK_SHARE_ACCESS_MASK_MAX_ALLOWED = "MAXIMUM_ALLOWED";
    public static final String USERDIR_IS_ROOT = "userDirIsRoot";

    public static final String IS_PASSIVE = "isPassive";
    public static final String CONNECTION_TIMEOUT = "ftpConnectionTimeout";
    public static final String SOCKET_TIMEOUT = "ftpSocketTimeout";
    public static final String KEYSTORE_PATH = "keyStorePath";
    public static final String KEYSTORE_PASSWORD = "keyStorePassword";
    public static final String KEY_PASSWORD = "keyPassword";
    public static final String TRUSTSTORE_PATH = "trustStorePath";
    public static final String TRUSTSTORE_PASSWORD = "trustStorePassword";
    public static final String IMPLICIT_MODE_ENABLED = "implicitModeEnabled";
    public static final String CHANNEL_PROTECTION_LEVEL = "channelProtectionLevel";

    public static final String SFTP_CONNECTION_TIMEOUT = "sftpConnectionTimeout";
    public static final String SFTP_SESSION_TIMEOUT = "sftpSessionTimeout";
    public static final String SFTP_POOL_CONNECTION_AGED_TIMEOUT = "sftpPoolConnectionAgedTimeout";
    public static final String STRICT_HOST_KEY_CHECKING = "strictHostKeyChecking";
    public static final String PRIVATE_KEY_FILE_PATH = "privateKeyFilePath";
    public static final String PRIVATE_KEY_PASSWORD = "privateKeyPassword";

    public static final String DIRECTORY_PATH = "directoryPath";
    public static final String FILE_OR_DIRECTORY_PATH = "path";
    public static final String LOCAL_FILE_PROTOCOL_PREFIX = "file://";
    public static final String FTP_PROTOCOL_PREFIX = "ftp://";
    public static final String FTPS_PROTOCOL_PREFIX = "ftps://";
    public static final String SFTP_PROTOCOL_PREFIX = "sftp://";
    public static final String SMB_PROTOCOL_PREFIX = "smb://";
    public static final String SMB2_PROTOCOL_PREFIX = "smb2://";
    //FILE.Separator is not needed for VFS. Windows also support /
    public static final String FILE_SEPARATOR = "/";

    public static final String FILE_ELEMENT = "file";
    public static final String LAST_MODIFIED_TIME_ELEMENT = "lastModifiedTime";
    public static final String SIZE_ELEMENT = "size";
    public static final String CONTENT_TYPE_ELEMENT = "contentType";
    public static final String CONTENT_ENCODING_ELEMENT = "contentEncoding";
    public static final String STATUS_CODE = "HTTP_SC";
    public static final Object HTTP_STATUS_500 = "500";
    public static final int UNZIP_BUFFER_SIZE = 4096;
    public static final int ZIP_BUFFER_SIZE = 4096;
    public static final long DEFAULT_LOCK_TIMEOUT = 30000;

    public static final String YES = "yes";
    public static final String OVERWRITE = "Overwrite";
    public static final String FILE = "file";

    //file read - property constants
    public static final String FILE_LAST_MODIFIED_TIME = "FILE_LAST_MODIFIED_TIME";
    public static final String FILE_SIZE = "FILE_SIZE";
    public static final String FILE_IS_DIR = "FILE_IS_DIR";
    public static final String FILE_PATH = "FILE_PATH";
    public static final String FILE_URL = "FILE_URL";
    public static final String FILE_NAME = "FILE_NAME";
    public static final String FILE_NAME_WITHOUT_EXTENSION = "FILE_NAME_WITHOUT_EXTENSION";


    public static final CharSequence NEW_LINE = "\n";
    public static final String DEFAULT_ENCODING = "UTF-8";
    public static final String SET_CHARACTER_ENCODING = "setCharacterEncoding";
    public static final String CONTENT_TYPE_BINARY = "application/binary";
    public static final String CONTENT_TYPE_TEXT = "text/plain";
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String MESSAGE_TYPE = "messageType";
    public static final String ZIP_FILE_EXTENSION = ".zip";
    public static final String LOCK_FILE_EXTENSION = ".lock";

    public static final String CONTENT_TYPE_AUTOMATIC = "Automatic";

    public static final String MESSAGE_BODY = "Message Body";

    public static final String MESSAGE_PROPERTY = "Message Property";
    public static final String TENANT_INFO_DOMAIN = "tenant.info.domain";

    public static final String MATCH_ALL_REGEX = ".*";
    public static final String EMPTY_STRING = "";
    public static final CharSequence CONNECTOR_LIBRARY_NAME = "file-connector";
    public static final String CONNECTOR_LIBRARY_PACKAGE_TYPE = "org.wso2.carbon.connector";
    public static final String LOCAL_FILE_LOCK_SCHEME = "Local";
    public static final String CLUSTER_FILE_LOCK_SCHEME = "Cluster";

    public static final String MAX_RETRY_PARAM = "maxRetries";
    public static final String RETRY_DELAY_PARAM = "retryDelay";

    public static final String FILE_CONNECTION_TEST = "FILE_CONNECTION_TEST";
    public static final String IS_VALID_CONNECTION = "isValidConnection";

    // SFTP-specific parameters  
    public static final String SFTP_PATH_FROM_ROOT = "sftpPathFromRoot";
}
