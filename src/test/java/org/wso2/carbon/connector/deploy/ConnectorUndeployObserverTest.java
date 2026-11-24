/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
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

package org.wso2.carbon.connector.deploy;

import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.libraries.model.Library;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.operations.FilePoolFixture;
import org.wso2.integration.connector.core.connection.ConnectionHandler;

import java.lang.reflect.Proxy;
import javax.xml.namespace.QName;

/**
 * The observer knows the connector library by its package and name, not by the name of its zip, and the removal of
 * that library ends the file connections of the node.
 */
public class ConnectorUndeployObserverTest {

    private static Library library(String packageName, String name, String fileName) {
        QName qName = new QName(packageName, name);
        return (Library) Proxy.newProxyInstance(Library.class.getClassLoader(), new Class<?>[]{Library.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getQName": return qName;
                        case "getPackage": return packageName;
                        case "getFileName": return fileName;
                        case "toString": return qName.toString();
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return null;
                    }
                });
    }

    @Test
    public void connectorIsKnownByItsNameWhateverItsZipIsCalled() {
        Assert.assertTrue(ConnectorUndeployObserver.isFileConnector(library("org.wso2.carbon.connector", "file",
                "/tmp/carbonapps/app.car/mi-connector-file_7.0.0/mi-connector-file-7.0.0.zip")));
        Assert.assertTrue(ConnectorUndeployObserver.isFileConnector(library("org.wso2.carbon.connector", "file",
                "/tmp/carbonapps/app.car/file-connector_7.0.0/file-connector-7.0.0.zip")));
        Assert.assertFalse(ConnectorUndeployObserver.isFileConnector(library("org.wso2.carbon.connector", "email",
                "/tmp/carbonapps/app.car/file-connector_7.0.0/file-connector-7.0.0.zip")));
        Assert.assertFalse(ConnectorUndeployObserver.isFileConnector(library("org.wso2.carbon.other", "file",
                "/tmp/carbonapps/app.car/mi-connector-file-7.0.0.zip")));
    }

    @Test
    public void removalOfTheLibraryClosesTheConnectionsAndEndsTheObserver() throws Exception {
        Library connector = library("org.wso2.carbon.connector", "file",
                "/tmp/carbonapps/app.car/mi-connector-file_7.0.0/mi-connector-file-7.0.0.zip");
        Library other = library("org.wso2.carbon.connector", "email",
                "/tmp/carbonapps/app.car/mi-connector-email_2.0.0/mi-connector-email-2.0.0.zip");
        SynapseConfiguration configuration = new SynapseConfiguration();
        ConnectorUndeployObserver observer = new ConnectorUndeployObserver(configuration);
        configuration.registerObserver(observer);
        configuration.addSynapseLibrary(connector.getQName().toString(), connector);
        configuration.addSynapseLibrary(other.getQName().toString(), other);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionHandler core = ConnectionHandler.getConnectionHandler();
            Assert.assertTrue(core.checkIfConnectionExists("file", files.name));
            // The removal of another library leaves the file connections alone.
            configuration.removeSynapseLibrary(other.getQName().toString());
            Assert.assertTrue(core.checkIfConnectionExists("file", files.name));
            Assert.assertTrue(configuration.getObservers().contains(observer));
            // Synapse removes a library once, so its removal ends the connections and the observer.
            configuration.removeSynapseLibrary(connector.getQName().toString());
            Assert.assertFalse(core.checkIfConnectionExists("file", files.name));
            Assert.assertFalse(configuration.getObservers().contains(observer));
        }
    }
}
