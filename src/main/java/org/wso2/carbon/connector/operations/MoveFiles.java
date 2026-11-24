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
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject.EntryRelation;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.log.UriMask;
import org.wso2.vfs.shared.write.AtomicWriter;
import org.wso2.vfs.shared.write.TempName;
import org.wso2.vfs.shared.write.TempNames;

public class MoveFiles extends FileTreeOperation {
    @Override
    Walk operation(MessageContext message) throws InvalidConfigurationException {
        return new Move(message, false);
    }

    static class Move extends Walk {
        final boolean rename;
        final boolean createParents;
        final boolean sourceMounted;
        final boolean targetMounted;
        final boolean updateTime;
        private AtomicWriter.Temp staging;

        Move(MessageContext message, boolean rename) throws InvalidConfigurationException {
            super(message, rename ? "renameFile" : "moveFiles", rename ? "path" : "sourcePath", "filePattern");
            this.rename = rename;
            createParents = flag(message, "createParentDirectories", false);
            sourceMounted = flag(message, "isSourceMounted", false);
            targetMounted = flag(message, "isTargetMounted", false);
            updateTime = flag(message, "updateLastModified", true);
            if (rename && newName == null) {
                throw new InvalidConfigurationException("The new name is required");
            }
        }

        @Override public OperationFrame.Retry retry() {
            return overwrite ? OperationFrame.Retry.MOVE : OperationFrame.Retry.MOVE_NO_REPLACE;
        }

        @Override boolean walksFolder() { return !rename; }
        @Override boolean removesSource() { return true; }
        @Override boolean canCreateParent() { return createParents; }

        @Override void noteSource(Attempt attempt, FileObject source) throws Exception {
            if (source.isFile()) {
                attempt.source(source.getContent().getSize(), source.getContent().getLastModifiedTime());
            }
        }

        @Override void change(Attempt attempt, FileObject source, FileObject target, String path, String targetPath)
                throws Exception {
            if (source.isFolder()) {
                RenameFiles.checkDescendants(attempt, source);
            }
            observe("move.after-source-lock", path);
            LockIo io = StreamLease.io(attempt.handler());
            if (staging != null) {
                discardTemp(attempt, staging);
                staging.close();
                staging = null;
            }
            String suffix = attempt.handler().getContext().getConfiguration().getTempFileSuffix();
            staging = attempt.handler().getContext().getWriter().prepare(io, targetPath);
            attempt.tempPath(staging.uri);
            TempName probe = TempNames.begin("vfsprobe", suffix);
            TempName first = TempNames.begin("vfsprobe", suffix);
            TempName second = TempNames.begin("vfsprobe", suffix);
            try {
                FileObject temp = io.resolve(staging.uri);
                FileObject parent = temp.getParent();
                MoveOptions options = new MoveOptions(overwrite, temp, probe.name(),
                        parent.resolveFile(first.name(), NameScope.CHILD),
                        parent.resolveFile(second.name(), NameScope.CHILD));
                if (!overwrite && options.needsTargetLock(source, target)) {
                    attempt.lockTarget(targetPath, OperationFrame.Lock.OWN);
                    observe("move.after-target-lock", targetPath);
                } else if (!source.getName().equals(target.getName())) {
                    attempt.lockTarget(targetPath, OperationFrame.Lock.NONE);
                }
                source.setIsMounted(sourceMounted);
                target.setIsMounted(targetMounted);
                target.setUpdateLastModified(updateTime);
                boolean differentName = !source.getName().equals(target.getName());
                EntryRelation relation = EntryRelation.DISTINCT;
                if (differentName && overwrite && attempt.hasLentLease(path)) {
                    relation = AbstractFileObject.compareEntries(source, target);
                }
                attempt.moving(path, targetPath);
                attempt.progress().reset("move");
                FileObjectUtils.getAbstractFileObject(source).moveTo(target, options);
                if (differentName && (relation == EntryRelation.DISTINCT
                        || relation == EntryRelation.UNKNOWN && !AtomicOps.existsNow(source))) {
                    attempt.removed(path);
                }
                staging.close();
                staging = null;
            } finally {
                second.end();
                first.end();
                probe.end();
            }
        }

        @Override void settle(Attempt attempt) throws Exception {
            String path = attempt.previous.path();
            observe("retry.before-decision", path);
            // A test can stop the retry here, before its looks decide the outcome.
            hold("retry.before-decision", path);
            if (AtomicOps.existsNow(attempt.handler().resolveFile(path))) {
                throw failure(Error.OUTCOME_UNCLEAR, "A file remains at the source", path);
            }
            if (attempt.previous.request() == Attempt.Request.RENAME) {
                if (attempt.previous.sourceSize() == null) {
                    throw failure(Error.OUTCOME_UNCLEAR, "The directory rename is unclear", path);
                }
                FileObject target = attempt.handler().resolveFile(inFlightTarget);
                AtomicOps ops = AtomicOps.of(target);
                StatNow stat = ops == null ? null : ops.statNow();
                if (stat == null || !stat.isThere() || stat.getSize() != attempt.previous.sourceSize()
                        || stat.getLastModifiedMillis() != attempt.previous.sourceModified()) {
                    throw failure(Error.OPERATION_ERROR, "The target does not match the missing source", path);
                }
                inferred = true;
                attempt.handler().getContext().getHealth().inferredMove();
                attempt.handler().getContext().getHealth().namedLog().warn("Inferred move from " + UriMask.mask(path)
                        + " to " + UriMask.mask(inFlightTarget));
            }
            attempt.removed(path);
        }

        @Override void finishTemp() {
            if (staging != null) {
                staging.close();
                staging = null;
            }
        }
    }
}
