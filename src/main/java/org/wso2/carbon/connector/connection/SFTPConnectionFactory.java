/*
 *  Copyright (c) 2023, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.connector.connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.integration.connector.core.pool.ConnectionFactory;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;

public class SFTPConnectionFactory implements ConnectionFactory  {
    private ConnectionConfiguration connectionConfiguration;
    private static final Log log = LogFactory.getLog(SFTPConnectionFactory.class);
    public SFTPConnectionFactory(ConnectionConfiguration connectionConfiguration) {
        this.connectionConfiguration = connectionConfiguration;
    }
    @Override
    public Object makeObject() throws Exception {
        FileSystemHandler fileSystemConnection = new FileSystemHandler(connectionConfiguration);
        return fileSystemConnection;
    }

    @Override
    public void destroyObject(Object connection) throws Exception {
        ((FileSystemHandler) connection).close();
    }

    @Override
    public boolean validateObject(Object connection) {
        try {
            FileSystemHandler fileSystemHandlerConnection = (FileSystemHandler) connection;
            String filePath = fileSystemHandlerConnection.getBaseDirectoryPath();
            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();

            // Use try-with-resources statement for the FileObject to ensure it's properly closed
            try (FileObject fileObject = fsManager.resolveFile(filePath, fso)) {
                // Attempt to perform a simple operation on the root directory.
                // Checking for existence is a minimal, non-intrusive operation.
                return fileObject.exists(); // This throws an exception if the connection is not valid
            }
        } catch (Throwable e) {
            // Log connection validation failures without stack trace for common issues
            if (e.getCause() instanceof java.net.ConnectException) {
                log.debug("SFTP connection validation failed: Connection refused");
            } else if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                log.debug("SFTP connection validation failed: " + e.getMessage());
            } else {
                // Only log full stack trace for unexpected errors
                log.debug("Error while validating SFTP connection", e);
            }
            return false;
        }
    }

    @Override
    public void activateObject(Object o) throws Exception {
        // Nothing to do here
    }

    @Override
    public void passivateObject(Object o) throws Exception {
        // Nothing to do here
    }
}
