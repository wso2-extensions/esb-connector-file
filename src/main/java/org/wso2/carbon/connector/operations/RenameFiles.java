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

import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.vfs.shared.lock.LockOutcome;

public class RenameFiles extends MoveFiles {
    @Override
    Walk operation(MessageContext message) throws InvalidConfigurationException {
        return new Move(message, true);
    }

    static void checkDescendants(Attempt attempt, FileObject folder) throws Exception {
        for (FileObject child : folder.getChildren()) {
            String name = child.getName().getBaseName();
            if (name.endsWith(".lock")) {
                String marker = Walk.uri(child);
                String path = marker.substring(0, marker.length() - ".lock".length());
                LockOutcome result = attempt.handler().getContext().getShareLock()
                        .isLockedByOther(StreamLease.io(attempt.handler()), path);
                if (result.kind != LockOutcome.Kind.FREE && result.kind != LockOutcome.Kind.OURS) {
                    if (result.connectionLost()) {
                        attempt.discardHandler();
                    }
                    if (result.classification != null) {
                        throw new OperationFrame.Failure(Error.FILE_LOCKING_ERROR, result.classification);
                    }
                    throw Walk.failure(Error.FILE_LOCKING_ERROR, "The directory contains a locked file", path);
                }
            } else if (child.isFolder() && !child.isSymbolicLink()) {
                checkDescendants(attempt, child);
            }
        }
    }
}
