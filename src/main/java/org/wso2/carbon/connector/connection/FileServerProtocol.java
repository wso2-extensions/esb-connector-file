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

package org.wso2.carbon.connector.connection;

/**
 * Contains File server protocols
 * supported by the connector
 */
public enum FileServerProtocol {

    LOCAL("LOCAL", false),
    FTP("FTP", false),
    FTPS("FTPS", true),
    SFTP("SFTP", true),
    SMB2("SMB2", true),
    SMB("SMB", true);


    private final String name;
    private final boolean secure;

    /**
     * Creates an Email Protocol instance.
     *
     * @param name   the name of the protocol.
     * @param secure whether the protocol is secure or not.
     */
    FileServerProtocol(String name, boolean secure) {

        this.name = name;
        this.secure = secure;
    }

    /**
     * Get protocol name.
     *
     * @return name
     */
    public String getName() {

        return name;
    }

    /**
     * Check if protocol uses TLS/SSL level security.
     *
     * @return true if protocol is secured
     */
    public boolean isSecure() {

        return secure;
    }

}
