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

import org.apache.commons.lang.StringUtils;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.carbon.connector.exception.FileServerConnectionException;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.utils.Const;

/**
 * Protocol specific file system setup strategy.
 */
public interface ProtocolBasedFileSystemSetup {

     String setupFileSystemHandler(FileSystemOptions fso, ConnectionConfiguration fsConfig)
            throws FileServerConnectionException;


     /**
      * Constructs VFS url based on configurations.
      *
      * @param fsConfig Input configs
      * @return Constructed url
      */
     default String constructVfsUrl(ConnectionConfiguration fsConfig) {
          StringBuilder sb = new StringBuilder();
          if (fsConfig.getRemoteServerConfig() != null) {
               String username = fsConfig.getRemoteServerConfig().getUsername();
               String password = fsConfig.getRemoteServerConfig().getPassword();
               String host = fsConfig.getRemoteServerConfig().getHost();
               int port = fsConfig.getRemoteServerConfig().getPort();
               if (StringUtils.isNotEmpty(username)) {
                    sb.append(username);
                    if (StringUtils.isNotEmpty(password)) {
                         sb.append(":").append(password);
                    }
                    sb.append("@");
               }
               sb.append(host).append(":").append(port);
          }
          if (StringUtils.isNotEmpty(fsConfig.getWorkingDir())) {
               sb.append(Const.FILE_SEPARATOR).append(fsConfig.getWorkingDir());
          }
          return sb.toString();
     }
}
