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

package org.wso2.carbon.connector.deploy;


import org.apache.synapse.config.AbstractSynapseObserver;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.libraries.model.Library;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.utils.Const;

import java.io.File;

/**
 * Listen for library un deploy events
 * and cleanup connections created by the connector.
 */
public class ConnectorUndeployObserver extends AbstractSynapseObserver {

    private SynapseConfiguration synapseConfiguration;
    private String connectorName = Const.CONNECTOR_NAME;

    public ConnectorUndeployObserver(SynapseConfiguration synapseConfiguration) {
        this.synapseConfiguration = synapseConfiguration;
    }

    @Override
    public void synapseLibraryRemoved(Library library) {
        String libraryPath = library.getFileName();
        // No need to use File.separator here since libraryPath we are getting from synapse is formatted with "/"
        String libraryName = libraryPath.substring(libraryPath.lastIndexOf("/"));
        if (libraryName.contains(Const.CONNECTOR_LIBRARY_NAME)
                && library.getPackage().equals(Const.CONNECTOR_LIBRARY_PACKAGE_TYPE)) {
            if (log.isDebugEnabled()) {
                log.debug("File Connector is being un deployed. Closing all file connections created.");
            }
            ConnectionHandler.getConnectionHandler().
                    shutdownConnections(Const.CONNECTOR_NAME);
            synapseConfiguration.unregisterObserver(this);
        }
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConnectorUndeployObserver that = (ConnectorUndeployObserver) o;
        return connectorName.equals(that.connectorName);
    }

    public int hashCode() {
        return connectorName.hashCode();
    }
}
