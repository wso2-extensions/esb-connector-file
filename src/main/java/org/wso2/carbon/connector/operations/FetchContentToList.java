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
package org.wso2.carbon.connector.operations;

import org.wso2.vfs.shared.log.UriMask;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.org.apache.commons.vfs2.FileObject;

public class FetchContentToList extends FileOperation {
    public FetchContentToList() {
        super("listFiles", Const.DIRECTORY_PATH);
    }

    @Override
    public void connect(MessageContext message) throws ConnectException {
        execute(message, null, true);
    }

    @Override
    void configure(Call call) {
        call.prepareGuard("matchingPattern");
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
        call.requireFolder(folder);
        List<String> contents = new ArrayList<>();
        collect(attempt, folder, call.guard(), call.sorter(), call.flag("recursive", false), contents);
        call.onSuccess = () -> call.message.setProperty(call.option("resultPropertyName", ""), contents);
    }

    private void collect(Attempt attempt, FileObject folder, SourceGuard guard, FileSorter sorter,
                         boolean recursive, List<String> contents) throws Exception {
        FileObject[] children = folder.getChildren();
        sorter.sort(children);
        for (FileObject child : children) {
            try (FileObject file = child) {
                if (file.isFolder()) {
                    if (recursive && !guard.reservedDirectory(file)) {
                        collect(attempt, file, guard, sorter, true, contents);
                    }
                } else if (guard.accepts(file)) {
                    String path = FileTreeOperation.Walk.uri(file);
                    if (attempt.guardMember(path) == OperationFrame.Member.READY) {
                        try (InputStream stream = file.getContent().getInputStream()) {
                            contents.add(Utils.readStream(stream));
                        }
                        attempt.releasePath(path);
                        attempt.memberDone();
                    }
                }
            }
        }
    }

    @Override
    protected void handleConnectorResponse(MessageContext message, String responseVariable, Boolean overwriteBody,
                                           JsonElement payload, Map<String, Object> headers,
                                           Map<String, Object> attributes) {
        if (responseVariable != null) {
            super.handleConnectorResponse(message, responseVariable, overwriteBody, payload, headers, attributes);
            return;
        }
        try {
            JsonUtil.getNewJsonPayload(((Axis2MessageContext) message).getAxis2MessageContext(), payload.toString(),
                    false, false);
        } catch (AxisFault failure) {
            String detail = "Error while setting the error payload: " + failure.getMessage();
            Object name = message.getProperty(Const.CONNECTION_NAME);
            if (name == null) {
                log.error(UriMask.mask(detail));
                return;
            }
            String connection = Utils.getTenantSpecificConnectionName(name.toString(), message);
            Object factory = ConnectionHandler.getConnectionHandler().getConnectionFactory(Const.CONNECTOR_NAME, connection);
            NamedLog connectionLog = factory instanceof FileSystemHandlerFactory
                    ? ((FileSystemHandlerFactory) factory).existingLog() : null;
            if (connectionLog == null) {
                connectionLog = NamedLog.connection(log, Utils.getDisplayConnectionName(name.toString(), message),
                        new RepeatLimiter());
            }
            connectionLog.error(detail);
        }
    }
}
