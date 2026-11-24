/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.connector.operations;

import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.mediators.template.TemplateContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.ConnectionPool;

import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class FilePoolFixture implements AutoCloseable {
    public final String name = "files" + UUID.randomUUID().toString();
    final ConnectionHandler core = ConnectionHandler.getConnectionHandler();
    public final FileSystemHandlerFactory factory;
    final MessageContext message;

    public FilePoolFixture(String protocol, String... settings) throws Exception {
        this(protocol, null, settings);
    }

    public FilePoolFixture(String protocol, LongSupplier clock, String... settings) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("connectionType", protocol);
        values.put("name", name);
        values.put("INIT_CONFIG_KEY", name);
        values.put("fileLockScheme", "Local");
        values.put("workingDir", System.getProperty("java.io.tmpdir"));
        values.put("host", "127.0.0.1");
        values.put("port", "1");
        for (int i = 0; i < settings.length; i += 2) {
            values.put(settings[i], settings[i + 1]);
        }
        TemplateContext template = new TemplateContext("file.init", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        SynapseConfiguration configuration = new SynapseConfiguration();
        SynapseEnvironment environment = (SynapseEnvironment) Proxy.newProxyInstance(
                SynapseEnvironment.class.getClassLoader(), new Class<?>[]{SynapseEnvironment.class},
                (proxy, method, args) -> "getSynapseConfiguration".equals(method.getName()) ? configuration : null);
        message = (MessageContext) Proxy.newProxyInstance(MessageContext.class.getClassLoader(),
                new Class<?>[]{MessageContext.class}, (proxy, method, args) -> {
                    if ("getProperty".equals(method.getName()) && "_SYNAPSE_FUNCTION_STACK".equals(args[0])) {
                        return stack;
                    }
                    if ("getEnvironment".equals(method.getName())) {
                        return environment;
                    }
                    if ("getConfiguration".equals(method.getName())) {
                        return configuration;
                    }
                    return null;
                });
        new FileConfig().connect(message);
        FileSystemHandlerFactory created = (FileSystemHandlerFactory) core.getConnectionFactory("file", name);
        if (clock != null) {
            ConnectionConfiguration parsed = created.getContext().getConfiguration();
            close();
            parsed.getConfiguration().setBreakerClockForTesting(clock);
            created = new FileSystemHandlerFactory(parsed, "file", name);
            core.createConnection("file", name, created, parsed.getConfiguration(), message);
        }
        factory = created;
    }

    public FileSystemHandlerFactory redeploy() throws Exception {
        ConnectionConfiguration config = factory.getContext().getConfiguration();
        close();
        FileSystemHandlerFactory replacement = new FileSystemHandlerFactory(config, "file", name);
        core.createConnection("file", name, replacement, config.getConfiguration(), message);
        return replacement;
    }

    public static FileObject fileWithStream(InputStream controlled) {
        FileContent content = (FileContent) Proxy.newProxyInstance(FilePoolFixture.class.getClassLoader(),
                    new Class<?>[]{FileContent.class}, (proxy, method, args) -> {
                        if ("getInputStream".equals(method.getName())) {
                            return controlled;
                        }
                        throw new AssertionError(method);
                    });
        return (FileObject) Proxy.newProxyInstance(FilePoolFixture.class.getClassLoader(),
                    new Class<?>[]{FileObject.class}, (proxy, method, args) -> {
                        if ("getContent".equals(method.getName())) {
                            return content;
                        }
                        throw new AssertionError(method);
                    });
    }

    ConnectionLease borrow() throws Exception {
        return core.borrowConnection("file", name);
    }

    static FileSystemHandler handler(ConnectionLease lease) {
        return (FileSystemHandler) lease.getConnection();
    }

    static Object field(Object owner, Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    static ConnectionPool pool(ConnectionLease lease) throws ReflectiveOperationException {
        return (ConnectionPool) field(lease, ConnectionLease.class, "pool");
    }

    @Override
    public void close() {
        core.removeLocalEntryConnections(name);
    }
}
