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

import org.wso2.carbon.connector.core.pool.ConnectionFactory;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;

public class SFTPConnectionFactory implements ConnectionFactory  {
    private ConnectionConfiguration connectionConfiguration;
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
        return true;
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
