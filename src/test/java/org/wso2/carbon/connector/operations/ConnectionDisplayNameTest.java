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
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.vfs.shared.health.EndpointHealth;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;

/**
 * The name of a connection in its pool and the name that health, metrics and logs show.
 */
public class ConnectionDisplayNameTest {

    @DataProvider(name = "tenants")
    public Object[][] tenants() {
        return new Object[][]{{null, false}, {"carbon.super", false}, {"tenant.example", true}};
    }

    // The pool keeps the tenant specific key. Health shows the configured name under the super tenant, and the tenant
    // specific key under any other tenant, so two tenants never share one health entry.
    @Test(dataProvider = "tenants")
    public void healthShowsTheConfiguredNameOnlyUnderTheSuperTenant(String tenant, boolean qualified)
            throws Exception {
        String name = "files" + UUID.randomUUID();
        String key = tenant == null ? name : name + "@" + tenant;
        String shown = qualified ? key : name;
        MessageContext message = message(name, tenant);
        ConnectionHandler core = ConnectionHandler.getConnectionHandler();
        try {
            new FileConfig().connect(message);
            FileSystemHandlerFactory factory = (FileSystemHandlerFactory) core.getConnectionFactory(
                    Const.CONNECTOR_NAME, key);
            Assert.assertNotNull(factory);
            ConnectionContext context = factory.getContext();
            Assert.assertEquals(context.getName(), key);
            Assert.assertEquals(healthName(context.getHealth()), shown);
            Assert.assertEquals(Utils.getTenantSpecificConnectionName(name, message), key);
            Assert.assertEquals(Utils.getDisplayConnectionName(name, message), shown);
        } finally {
            core.removeLocalEntryConnections(name);
        }
    }

    private static String healthName(EndpointHealth health) throws ReflectiveOperationException {
        Field field = EndpointHealth.class.getDeclaredField("name");
        field.setAccessible(true);
        return (String) field.get(health);
    }

    // A message of the init operation of a local connection, with the given tenant domain.
    private static MessageContext message(String name, String tenant) {
        Map<String, String> values = new HashMap<>();
        values.put("connectionType", "LOCAL");
        values.put("name", name);
        values.put("INIT_CONFIG_KEY", name);
        values.put("fileLockScheme", "Local");
        values.put("workingDir", System.getProperty("java.io.tmpdir"));
        TemplateContext template = new TemplateContext("file.init", Collections.emptyList());
        template.setMappedValues(values);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        SynapseConfiguration configuration = new SynapseConfiguration();
        SynapseEnvironment environment = (SynapseEnvironment) Proxy.newProxyInstance(
                SynapseEnvironment.class.getClassLoader(), new Class<?>[]{SynapseEnvironment.class},
                (proxy, method, args) -> "getSynapseConfiguration".equals(method.getName()) ? configuration : null);
        return (MessageContext) Proxy.newProxyInstance(MessageContext.class.getClassLoader(),
                new Class<?>[]{MessageContext.class}, (proxy, method, args) -> {
                    if ("getProperty".equals(method.getName()) && "_SYNAPSE_FUNCTION_STACK".equals(args[0])) {
                        return stack;
                    }
                    if ("getProperty".equals(method.getName()) && Const.TENANT_INFO_DOMAIN.equals(args[0])) {
                        return tenant;
                    }
                    if ("getEnvironment".equals(method.getName())) {
                        return environment;
                    }
                    if ("getConfiguration".equals(method.getName())) {
                        return configuration;
                    }
                    return null;
                });
    }
}
