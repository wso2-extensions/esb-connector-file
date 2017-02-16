/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.connector.util;

/**
 * This class represents all the constants related to file connector.
 * @since 2.0.6
 */
public final class FileConstants {
    /**
     * Location of the file to do action to that file.
     */
    public static final String FILE_LOCATION = "source";
    /**
     * Content type of the file.
     */
    public static final String CONTENT_TYPE = "contentType";
    /**
     * Using Pattern, filter the files.
     */
    public static final String FILE_PATTERN = "filePattern";
    /**
     * New location to send the file.
     */
    public static final String NEW_FILE_LOCATION = "destination";
    /**
     * Content to input.
     */
    public static final String CONTENT = "inputContent";
    /**
     * Encoding method to a file.
     */
    public static final String ENCODING = "encoding";
    /**
     * Namespace of OOM element.
     */
    public static final String NAMESPACE = "ns";
    /**
     * Giving Response Result.
     */
    public static final String RESULT = "result";
    /**
     * Tag of the OOM element.
     */
    public static final String FILE = "file";
    /**
     * Tag in the Namaspace of OOM elemnet.
     */
    public static final String FILECON = "http://org.wso2.esbconnectors.FileConnector";
    /**
     * Default buffer size
     */
    public static final int BUFFER_SIZE = 4096;
    /**
     * Default encoding method.
     */
    public static final String DEFAULT_ENCODING = "UTF8";
    /**
     * Sets the timeout value on Jsch(Java Secure Channel) session.
     */
    public static final String SET_TIME_OUT = "setTimeout";
    /**
     * Sets the passive mode to enter into passive mode.
     */
    public static final String SET_PASSIVE_MODE = "setPassiveMode";
    /**
     * Sets the socket timeout for the FTP client.
     */
    public static final String SET_SO_TIMEOUT = "setSoTimeout";
    /**
     * Sets the host key checking to use.
     */
    public static final String SET_STRICT_HOST_KEY_CHECKING = "setStrictHostKeyChecking";
    /**
     * Sets the whether to use the user directory as root.
     */
    public static final String SET_USER_DIRISROOT = "setUserDirIsRoot";
    /**
     * Default value of timeout.
     */
    public static final int TIME_OUT = 100000;
    /**
     * Start tag of the response.
     */
    public static final String START_TAG = "<result><success>";
    /**
     * End tag of the response.
     */
    public static final String END_TAG = "</success></result>";
    /**
     * Start tag of the file exist response.
     */
    public static final String FILE_EXIST_START_TAG = "<result><fileExist>";
    /**
     * End tag of the file exist response.
     */
    public static final String FILE_EXIST_END_TAG = "</fileExist></result>";
    /**
     * Whether you are searching recursively (The possible values are True or False).
     */
    public static final String RECURSIVE_SEARCH = "recursiveSearch";
    /**
     * The proxy host name.
     */
    public static final String PROXY_HOST = "proxyHost";
    /**
     * The port number of the proxy.
     */
    public static final String PROXY_PORT = "proxyPort";
    /**
     * Username of the proxy.
     */
    public static final String PROXY_USERNAME = "proxyUsername";
    /**
     * Password of the proxy.
     */
    public static final String PROXY_PASSWORD = "proxyPassword";
    /**
     * The FTP server name.
     */
    public static final String FTP_SERVER = "ftpServer";
    /**
     * The port number of the FTP server.
     */
    public static final String FTP_PORT = "ftpPort";
    /**
     * The time to wait between sending control connection keep alive messages when processing file upload or download.
     */
    public static final String KEEP_ALIVE_TIMEOUT = "keepAliveTimeout";
    /**
     * The time to wait for control keep-alive message replies.
     */
    public static final String CONTROL_KEEP_ALIVE_REPLY_TIMEOUT = "controlKeepAliveReplyTimeout";
    /**
     * Username of the FTP server.
     */
    public static final String FTP_USERNAME = "ftpUsername";
    /**
     * Password of the FTP server.
     */
    public static final String FTP_PASSWORD = "ftpPassword";
    /**
     * The Location of the target path.
     */
    public static final String TARGET_PATH = "targetPath";
    /**
     * The name of the file.
     */
    public static final String TARGET_FILE = "targetFile";
    /**
     * Set the file type to be transferred.
     */
    public static final String BINARY_TRANSFER = "binaryTransfer";
    /**
     * Set the current data connection mode to either ACTIVE_LOCAL_DATA_CONNECTION_MODE or PASSIVE_LOCAL_DATA_CONNECTION_MODE.
     */
    public static final String LOCAL_ACTIVE = "localActive";
    /**
     * Specify whether the streaming facility enabled or not.
     */
    public static final String STREAMING = "streaming";
    /**
     * Address to send the file.
     */
    public static final String ADDRESS = "address";
    /**
     * Specify whether append to the already available response file
     */
    public static final String APPEND = "append";
    /**
     * Default value to the response file.
     */
    public static final String DEFAULT_RESPONSE_FILE = "/response.xml";
}