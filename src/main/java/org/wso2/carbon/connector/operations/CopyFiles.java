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
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.write.AtomicWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class CopyFiles extends FileTreeOperation {
    @Override
    Walk operation(MessageContext message) throws InvalidConfigurationException {
        return new Copy(message);
    }

    static class Copy extends Walk {
        private final boolean atomic;
        private AtomicWriter.Temp staged;
        private boolean transferred;

        Copy(MessageContext message) throws InvalidConfigurationException {
            super(message, "copyFiles", "sourcePath", "sourceFilePattern");
            atomic = flag(message, "atomicWrite", true);
        }

        @Override public OperationFrame.Retry retry() {
            return overwrite ? OperationFrame.Retry.SAFE : OperationFrame.Retry.CREATE_NEW;
        }

        @Override void change(Attempt attempt, FileObject source, FileObject target, String path, String targetPath)
                throws Exception {
            LockIo io = StreamLease.io(attempt.handler());
            attempt.lockTarget(targetPath, atomic ? OperationFrame.Lock.LOCAL : OperationFrame.Lock.WHOLE);
            if (!atomic) {
                if (!overwrite && AtomicOps.existsNow(target)) {
                    throw failure(Error.FILE_ALREADY_EXISTS, "The target already exists", targetPath);
                }
                attempt.progress().reset("copy");
                try (OutputStream output = target.getContent().getOutputStream(false)) {
                    attempt.commitCalled();
                    transfer(source, output, attempt);
                }
                return;
            }
            AtomicWriter writer = attempt.handler().getContext().getWriter();
            boolean reuse = staged != null && transferred && overwrite && targetPath.equals(staged.target)
                    && AtomicOps.existsNow(io.resolve(staged.uri));
            if (!reuse) {
                if (staged != null) {
                    discardTemp(attempt, staged);
                    finishTemp();
                }
                attempt.progress().reset("prepare");
                staged = writer.prepare(io, targetPath);
                attempt.tempPath(staged.uri);
                attempt.progress().reset("transfer");
                try {
                    writer.transfer(io, staged, output -> transfer(source, output, attempt));
                } catch (IOException failure) {
                    if (!transport(attempt, failure)) {
                        discardTemp(attempt, staged);
                    }
                    throw failure;
                }
                transferred = true;
            }
            try {
                if (!attempt.hasShare(targetPath)) {
                    attempt.acquireCommitLock(targetPath);
                }
            } catch (OperationFrame.Failure failure) {
                if (!attempt.handlerDiscarded()) {
                    discardTemp(attempt, staged);
                }
                throw failure;
            }
            attempt.progress().reset("commit");
            attempt.commitCalled();
            AtomicWriter.Result result = writer.commit(io, attempt.lock(targetPath), staged,
                    overwrite ? CommitStep.Mode.OVERWRITE : CommitStep.Mode.CREATE_NEW, false, 1, 0, true, null);
            if (result.connectionLost()) {
                attempt.discardHandler();
            }
            switch (result.outcome) {
                case COMMITTED: finishTemp(); return;
                case TARGET_EXISTS: throw failure(Error.FILE_ALREADY_EXISTS, "The target already exists", targetPath);
                case LOCK_BUSY: throw failure(Error.FILE_LOCKING_ERROR, "The target is locked", targetPath);
                default:
                    if (result.failure != null) {
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, result.failure);
                    }
                    throw failure(Error.OPERATION_ERROR, result.outcome.toString(), targetPath);
            }
        }

        private void transfer(FileObject source, OutputStream output, Attempt attempt) throws IOException {
            byte[] buffer = new byte[8192];
            try (InputStream input = source.getContent().getInputStream()) {
                int read;
                try {
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        attempt.progress().reset("transfer");
                    }
                } catch (IOException error) {
                    // The source and the temp file are on two clients of this connection. The abort makes both
                    // closes fail at once, so a cut is detected once and not again in each close.
                    if (transport(attempt, error)) {
                        ((DefaultFileSystemManager) attempt.handler().getFsManager()).abortAll();
                    }
                    throw error;
                }
            }
        }

        @Override void finishTemp() {
            if (staged != null) {
                staged.close();
                staged = null;
                transferred = false;
            }
        }
    }
}
