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

import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.integration.connector.core.connection.PoolCloseListener;
import org.wso2.integration.connector.core.connection.PoolCloseReason;
import org.wso2.integration.connector.core.pool.ConnectionFactory;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

public class FileSystemHandlerFactory implements ConnectionFactory, PoolCloseListener {
    private final ConnectionConfiguration configuration;
    private final String deployedKey;
    private final String name;
    private final String displayName;
    private final RepeatLimiter initLimiter;
    private ConnectionContext context;
    private boolean retired;

    public FileSystemHandlerFactory(ConnectionConfiguration configuration, String deployedKey, String name) {
        this(configuration, deployedKey, name, name);
    }

    /**
     * @param name        the tenant specific key of the connection in the pool.
     * @param displayName the name that health, metrics and logs show.
     */
    public FileSystemHandlerFactory(ConnectionConfiguration configuration, String deployedKey, String name,
                                    String displayName) {
        this(configuration, deployedKey, name, displayName, new RepeatLimiter());
    }

    /**
     * @param initLimiter the repeat limiter of the warnings that each init of this connection logs.
     */
    public FileSystemHandlerFactory(ConnectionConfiguration configuration, String deployedKey, String name,
                                    String displayName, RepeatLimiter initLimiter) {
        this.configuration = configuration;
        this.deployedKey = deployedKey;
        this.name = name;
        this.displayName = displayName;
        this.initLimiter = initLimiter;
    }

    public RepeatLimiter getInitLimiter() {
        return initLimiter;
    }

    public synchronized NamedLog existingLog() {
        return context == null ? null : context.getHealth().namedLog();
    }

    public synchronized ConnectionContext getContext() throws FileServerConnectionException {
        if (context == null) {
            if (retired) {
                throw new FileServerConnectionException("The file connection was retired");
            }
            context = new ConnectionContext(configuration, deployedKey, name, displayName);
            if (TestPoints.enabled()) {
                context.registerPoolState(this);
            }
        }
        return context;
    }

    @Override
    public FileSystemHandler makeObject() throws FileServerConnectionException {
        ConnectionContext owner = getContext();
        FileSystemHandler handler = new FileSystemHandler(owner);
        owner.add(handler);
        return handler;
    }

    @Override
    public void destroyObject(Object connection) {
        FileSystemHandler handler = (FileSystemHandler) connection;
        try {
            handler.close();
        } finally {
            handler.getContext().remove(handler);
        }
    }

    @Override
    public boolean validateObject(Object connection) {
        return ((FileSystemHandler) connection).isAliveLocally();
    }

    @Override
    public synchronized void beforePoolClose(PoolCloseReason reason) {
        if (reason == PoolCloseReason.RETIRE && !retired) {
            retired = true;
            if (context != null) {
                context.retire();
            }
        }
    }

    @Override
    public void activateObject(Object connection) {
    }

    @Override
    public void passivateObject(Object connection) {
    }
}
