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
import org.wso2.carbon.connector.utils.Error;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;

public class DeleteFileOrFolder extends FileTreeOperation {
    @Override
    Walk operation(MessageContext message) throws InvalidConfigurationException {
        return new Delete(message);
    }

    static final class Delete extends Walk {
        Delete(MessageContext message) throws InvalidConfigurationException {
            super(message, "deleteFile", "path", "matchingPattern");
        }

        @Override public OperationFrame.Retry retry() { return OperationFrame.Retry.DELETE; }
        @Override public OperationFrame.Use use(String path) { return OperationFrame.Use.DELETE; }
        @Override boolean removesSource() { return true; }

        @Override void change(Attempt attempt, FileObject source, FileObject target, String path, String unused)
                throws Exception {
            attempt.destructive(Attempt.Request.DELETE, path);
            // A test can stop the delete here, right before its request. The progress clock starts after it.
            hold("delete.before-request", path);
            attempt.progress().reset("delete");
            if (!source.delete()) {
                throw failure(Error.OPERATION_ERROR, "The file could not be deleted", path);
            }
            attempt.removed(path);
        }

        @Override void settle(Attempt attempt) throws Exception {
            String path = attempt.previous.path();
            observe("retry.before-decision", path);
            // A test can stop the retry here, before its looks decide the outcome.
            hold("retry.before-decision", path);
            if (AtomicOps.existsNow(attempt.handler().resolveFile(path))) {
                throw failure(Error.OUTCOME_UNCLEAR, "A file remains at the source", path);
            }
            attempt.removed(path);
        }
    }
}
