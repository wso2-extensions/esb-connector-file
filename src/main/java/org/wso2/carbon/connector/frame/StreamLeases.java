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
package org.wso2.carbon.connector.frame;

import org.apache.synapse.MessageContext;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.VFS;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class StreamLeases {
    private static final String PROPERTY = "FILE_STREAM_LEASES";

    private final ConcurrentMap<String, ConcurrentMap<Object, StreamLease>> connections = new ConcurrentHashMap<>();

    private StreamLeases() {
    }

    public static StreamLeases find(MessageContext message) {
        return (StreamLeases) message.getProperty(PROPERTY);
    }

    public static StreamLeases forRead(MessageContext message) {
        synchronized (message) {
            StreamLeases holder = find(message);
            if (holder == null) {
                holder = new StreamLeases();
                message.setProperty(PROPERTY, holder);
            }
            return holder;
        }
    }

    public StreamLease get(String connection, String path) {
        ConcurrentMap<Object, StreamLease> files = connections.get(connection);
        return files == null ? null : files.get(key(path));
    }

    private static Object key(String path) {
        if (path.contains("://")) {
            try {
                // Name parsing uses the installed provider without opening a file or borrowing a handler.
                return VFS.getManager().resolveURI(path);
            } catch (FileSystemException invalidName) {
                // Leave resolution and error reporting to the operation.
            }
        }
        return path;
    }

    public void put(StreamLease lease) {
        connections.computeIfAbsent(lease.connection(), key -> new ConcurrentHashMap<>())
                .put(key(lease.getName()), lease);
    }

    public void failed(StreamLease.Owner owner, String path, StreamLease.Reason reason) {
        put(StreamLease.failed(owner, path, reason));
    }
}
