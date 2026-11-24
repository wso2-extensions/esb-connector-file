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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.log.UriMask;
import org.wso2.vfs.shared.write.AtomicWriter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

abstract class FileTreeOperation extends OperationFrame.Mediator {
    abstract Walk operation(MessageContext message) throws InvalidConfigurationException;

    @Override
    public final void execute(MessageContext message, String responseVariable, Boolean overwriteBody)
            throws ConnectException {
        Walk walk;
        String connection;
        try {
            walk = operation(message);
            connection = Utils.getConnectionName(message);
        } catch (InvalidConfigurationException | IllegalArgumentException invalid) {
            String name = this instanceof RenameFiles ? "renameFile" : this instanceof MoveFiles ? "moveFiles"
                    : this instanceof CopyFiles ? "copyFiles" : "deleteFile";
            FileOperationResult failure = new FileOperationResult(name, false, Error.INVALID_CONFIGURATION,
                    invalid.getMessage());
            handleConnectorResponse(message, responseVariable, overwriteBody,
                    Utils.generateOperationResult(message, failure), null, null);
            String detail = UriMask.mask(invalid.getMessage());
            handleException(detail, new InvalidConfigurationException(detail), message);
            return;
        }
        try {
            runFrame(message, connection, walk.retries, walk, walk.name, responseVariable, overwriteBody);
            JsonObject result = Utils.generateOperationResult(message, new FileOperationResult(walk.name, true));
            walk.resultDetails(result);
            handleConnectorResponse(message, responseVariable, overwriteBody, result, null, null);
        } finally {
            walk.finishTemp();
        }
    }

    abstract static class Walk implements OperationFrame.Operation<Void> {
        final String name;
        final String newName;
        final boolean overwrite;
        final int retries;
        private final String relative;
        private final String destination;
        private final String access;
        private final boolean own;
        private final boolean includeParent;
        private final Pattern pattern;
        private final String namedMovePattern;
        private final AdvancedFileFilter filter;
        private final long interval;
        private String root;
        private String targetDir;
        private SourceGuard guard;
        private boolean folder;
        private boolean ordinarySingleDelete;
        boolean inferred;
        private boolean confirmed;
        private final Set<String> finished = new LinkedHashSet<>();
        private final Set<String> moved = new LinkedHashSet<>();
        private final Map<String, String> left = new LinkedHashMap<>();
        private OperationFrame.Failure firstFailure;
        private int deleted;
        private String inFlight;
        private String inFlightName;
        String inFlightTarget;

        Walk(MessageContext message, String name, String sourceParam, String patternParam)
                throws InvalidConfigurationException {
            this.name = name;
            relative = Utils.lookUpStringParam(message, sourceParam);
            destination = sourceParam.equals("sourcePath") ? Utils.lookUpStringParam(message, "targetPath") : null;
            newName = option(message, "renameTo");
            overwrite = flag(message, "overwrite", false);
            includeParent = flag(message, "includeParent", false);
            own = flag(message, "enableLock", false);
            access = option(message, Const.DISK_SHARE_ACCESS_MASK);
            String legacy = option(message, patternParam);
            String include = option(message, "includeFiles");
            String exclude = option(message, "excludeFiles");
            String maxAge = option(message, "maxFileAge");
            String minAge = option(message, "minFileAge");
            String stability = option(message, "timeBetweenSizeCheck");
            interval = stability == null ? 0 : Long.parseLong(stability);
            boolean advanced = include != null || exclude != null || minAge != null || maxAge != null || stability != null;
            boolean advancedCopyOrMove = advanced && ("copyFiles".equals(name) || "moveFiles".equals(name));
            pattern = legacy == null || advancedCopyOrMove ? null : Pattern.compile(legacy);
            namedMovePattern = advanced && "moveFiles".equals(name) ? legacy : null;
            filter = new AdvancedFileFilter(option(message, "fileFilterType"), include, exclude, maxAge, minAge);
            retries = Integer.parseInt(Utils.lookUpStringParam(message, "maxRetries", "1"));
        }

        static String option(MessageContext message, String key) {
            return Utils.lookUpStringParam(message, key, null);
        }

        static boolean flag(MessageContext message, String key, boolean fallback) {
            return Utils.lookUpBooleanParam(message, key, fallback);
        }

        static String uri(FileObject file) {
            return file.getName().getRootURI() + escape(file.getName().getPath().substring(1));
        }

        static String escape(String name) {
            return name.replace("?", "%3F").replace("#", "%23");
        }

        // The name of a folder in filesLeft. The source folder itself has no relative name, so it shows its address,
        // with the password masked, because the response can reach a client.
        static String label(String path, String relative) {
            return relative.isEmpty() ? UriMask.mask(path) : relative;
        }

        static String childPath(String parent, String child) {
            int query = parent.indexOf('?');
            String path = query < 0 ? parent : parent.substring(0, query);
            return path + (path.endsWith("/") ? "" : "/") + child + (query < 0 ? "" : parent.substring(query));
        }

        @Override public void bind(String base) {
            root = base + relative;
            targetDir = destination == null ? null : base + destination;
        }

        @Override public LinkedHashMap<String, OperationFrame.Lock> paths() {
            LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
            paths.put(root, OperationFrame.Lock.NONE);
            return paths;
        }

        @Override public OperationFrame.Use use(String path) { return OperationFrame.Use.SOURCE; }
        boolean walksFolder() { return true; }
        boolean removesSource() { return false; }
        boolean canCreateParent() { return true; }
        boolean deleting() { return use(root) == OperationFrame.Use.DELETE; }

        @Override public void options(FileSystemHandler handler) {
            Utils.addDiskShareAccessMaskToFSO(handler.getFsOptions(), access, handler.getContext().getHealth().namedLog());
            ConnectionConfiguration config = handler.getContext().getConfiguration();
            guard = new SourceGuard(pattern, filter, interval, config.getTempFolderName(), config.getTempFileSuffix(),
                    new ErrorClassifier(handler.getContext().getHealth().namedLog()));
        }

        @Override public Void removed(String path) throws OperationFrame.Failure {
            if (!deleting()) {
                throw failure(Error.ILLEGAL_PATH, "This message removed the source", path);
            }
            return null;
        }

        @Override public Void steps(Attempt attempt) throws Exception {
            if (attempt.previous.looksOnly()) {
                try {
                    settle(attempt);
                    success(inFlight, inFlightName);
                    attempt.memberDone();
                } catch (OperationFrame.Failure failure) {
                    if (!folder || failure.transportFailure()) {
                        throw failure;
                    }
                    confirmed |= attempt.previous.committed();
                    recordFailure(inFlight, inFlightName, failure);
                }
                if (!folder) {
                    return null;
                }
                finishTemp();
            } else if (folder && inFlight != null && inFlight.equals(attempt.previous.member())) {
                FileObject member = attempt.handler().resolveFile(inFlight);
                if (AtomicOps.existsNow(member)) {
                    FileObject target = inFlightTarget == null ? null : attempt.handler().resolveFile(inFlightTarget);
                    visit(attempt, member, target, inFlight, inFlightName, null);
                } else if (attempt.previous.phase() != null) {
                    recordFailure(inFlight, inFlightName, failure(Error.OUTCOME_UNCLEAR, "The source is missing", inFlight));
                }
            }
            attempt.progress().reset("resolve");
            FileObject source = attempt.handler().resolveFile(root);
            if (!AtomicOps.existsNow(source)) {
                if (deleting()) {
                    return null;
                }
                throw failure(attempt.previous.phase() == null ? Error.ILLEGAL_PATH : Error.OUTCOME_UNCLEAR,
                        "The source does not exist", root);
            }
            folder = source.isFolder() && walksFolder();
            if (source.isSymbolicLink() || source.isFolder() && guard.reservedDirectory(source)) {
                throw failure(Error.OPERATION_ERROR, "The source did not pass the file guards", root);
            }
            if (!folder && namedMovePattern != null) {
                try {
                    if (!source.getName().getBaseName().matches(namedMovePattern)) {
                        throw failure(Error.OPERATION_ERROR, "The source did not pass the file guards", root);
                    }
                } catch (PatternSyntaxException invalid) {
                    throw failure(Error.INVALID_CONFIGURATION, invalid.getMessage(), root);
                }
            }
            String baseName = newName == null ? escape(source.getName().getBaseName()) : newName;
            FileObject target = null;
            if (!deleting()) {
                FileObject parent = targetDir == null ? source.getParent() : attempt.handler().resolveFile(targetDir);
                target = folder && !includeParent ? parent : parent.resolveFile(baseName, NameScope.CHILD);
                if ((source.getName().equals(target.getName()) && (folder || !removesSource()))
                        || (folder && source.getName().isDescendent(target.getName()))) {
                    throw failure(Error.ILLEGAL_PATH, "The target overlaps the source", root);
                }
                if (folder && !canCreateParent() && !AtomicOps.existsNow(parent)) {
                    throw failure(Error.ILLEGAL_PATH, "The target directory does not exist", uri(parent));
                }
            }
            if (folder) {
                walk(attempt, source, target, root, "", null);
            } else {
                visit(attempt, source, target, root, source.getName().getBaseName(), null);
                ordinarySingleDelete = deleting();
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            return null;
        }

        private void walk(Attempt attempt, FileObject dir, FileObject target, String path, String relative,
                OperationFrame.Failure targetFailure) throws Exception {
            if (target != null && targetFailure == null) {
                try {
                    target.createFolder();
                } catch (Exception error) {
                    if (transport(attempt, error) || attempt.handlerDiscarded()) {
                        throw error;
                    }
                    targetFailure = failure(Error.OPERATION_ERROR, error.getMessage(), uri(target));
                }
            }
            attempt.progress().reset("list");
            FileObject[] children = dir.getChildren();
            observe("walk.after-listing", path);
            // A test can stop the walk here, after the listing and before any child moves or goes.
            hold("walk.after-listing", path);
            if (targetFailure != null && children.length == 0) {
                recordFailure(path, label(path, relative), targetFailure);
            }
            for (FileObject child : children) {
                String name = child.getName().getBaseName();
                String childPath = childPath(path, escape(name));
                String label = relative.isEmpty() ? name : relative + "/" + name;
                if (finished.contains(childPath)) {
                    continue;
                }
                if (child.isSymbolicLink() || child.isFolder() && guard.reservedDirectory(child)) {
                    // An empty temp folder of ours goes with the folder that the walk removes.
                    if (removesSource() && targetFailure == null && !child.isSymbolicLink()
                            && name.equals(attempt.handler().getContext().getConfiguration().getTempFolderName())
                            && child.getChildren().length == 0 && child.delete()) {
                        continue;
                    }
                    left.put(label, "RESERVED");
                    continue;
                }
                FileObject to = target == null ? null : target.resolveFile(escape(name), NameScope.CHILD);
                if (child.isFolder()) {
                    walk(attempt, child, to, childPath, label, targetFailure);
                } else {
                    visit(attempt, child, to, childPath, label, targetFailure);
                }
            }
            if (removesSource() && targetFailure == null) {
                dir.refresh();
                if (dir.getChildren().length == 0 && !dir.delete() && AtomicOps.existsNow(dir)) {
                    dir.refresh();
                    if (dir.getChildren().length == 0) {
                        throw failure(Error.OPERATION_ERROR, "The empty directory could not be removed", path);
                    }
                }
            }
        }

        private void visit(Attempt attempt, FileObject source, FileObject target, String path, String label,
                OperationFrame.Failure targetFailure) throws Exception {
            inFlight = path;
            inFlightName = label;
            inFlightTarget = target == null ? null : uri(target);
            if (target != null && source.getName().equals(target.getName())) {
                inFlightTarget = path;
            }
            if (deleting()) {
                observe("delete.before-lock", path);
                hold("delete.before-lock", path);
            }
            if (!source.isFolder() && !guard.accepts(source)) {
                if (folder) {
                    left.put(label, "FILTERED");
                    finished.add(path);
                    return;
                }
                throw failure(Error.OPERATION_ERROR, "The source did not pass the file guards", path);
            }
            try {
                if (folder) {
                    attempt.member(path);
                }
                noteSource(attempt, source);
                if (folder) {
                    OperationFrame.Member member = attempt.guardMember(path);
                    if (member != OperationFrame.Member.READY) {
                        left.put(label, member.name());
                        finished.add(path);
                        return;
                    }
                }
                if (targetFailure != null) {
                    throw targetFailure;
                }
                if (own || !folder) {
                    attempt.lockPath(path, own ? OperationFrame.Lock.OWN : OperationFrame.Lock.GUARD);
                }
                if (target != null && !folder) {
                    FileObject parent = target.getParent();
                    if (!AtomicOps.existsNow(parent)) {
                        if (!canCreateParent()) {
                            throw failure(Error.ILLEGAL_PATH, "The target directory does not exist", uri(parent));
                        }
                        parent.createFolder();
                    }
                }
                change(attempt, source, target, path, inFlightTarget);
                success(path, label);
            } catch (Exception error) {
                if (!folder || transport(attempt, error) || attempt.handlerDiscarded()) {
                    throw error;
                }
                if (error instanceof OperationFrame.Failure) {
                    OperationFrame.Failure failure = (OperationFrame.Failure) error;
                    if (failure.error == Error.CONNECTION_ERROR || failure.error == Error.CONNECTION_SUSPENDED) {
                        throw failure;
                    }
                    recordFailure(path, label, failure);
                } else if (error instanceof MoveFailedException) {
                    MoveFailedException move = (MoveFailedException) error;
                    if (move.isConnectionLost()) {
                        throw move;
                    }
                    confirmed |= move.isCommitConfirmed();
                    recordFailure(path, label, failure(move.isTargetExists() ? Error.FILE_ALREADY_EXISTS
                            : Error.OPERATION_ERROR, move.getMessage(), path));
                } else {
                    recordFailure(path, label, failure(Error.OPERATION_ERROR, error.getMessage(), path));
                }
                finishTemp();
            }
            if (inFlightTarget != null) {
                attempt.releasePath(inFlightTarget);
            }
            attempt.releasePath(path);
            if (folder) {
                attempt.memberDone();
                if (attempt.handlerDiscarded()) {
                    throw new java.net.SocketException("The connection was lost after the folder member completed");
                }
            }
        }

        private void success(String path, String label) {
            if (finished.add(path)) {
                if (deleting()) {
                    deleted++;
                } else if (folder && removesSource()) {
                    moved.add(label);
                }
            }
        }

        private void recordFailure(String path, String label, OperationFrame.Failure failure) {
            if (firstFailure == null || failure.error == Error.OUTCOME_UNCLEAR) {
                firstFailure = failure;
            }
            left.put(label, failure.error == Error.OUTCOME_UNCLEAR ? "UNCLEAR" : failure.error.name());
            finished.add(path);
        }

        @Override public void resultDetails(JsonObject result) {
            if (inferred) {
                result.addProperty("inferred", true);
            }
            if (confirmed) {
                result.addProperty("targetComplete", true);
            }
            if (deleting() && (!ordinarySingleDelete || result.has("error"))) {
                result.addProperty("numOfDeletedFiles", deleted);
            }
            if (folder) {
                if (removesSource() && !deleting()) {
                    JsonArray names = new JsonArray();
                    moved.forEach(names::add);
                    result.add("filesMoved", names);
                }
                JsonArray entries = new JsonArray();
                for (Map.Entry<String, String> item : left.entrySet()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", item.getKey());
                    entry.addProperty("reason", item.getValue());
                    entries.add(entry);
                }
                if (result.has("filesLeft") && inFlightName != null && !left.containsKey(inFlightName)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", inFlightName);
                    entry.addProperty("reason", "UNCLEAR");
                    entries.add(entry);
                }
                result.add("filesLeft", entries);
            }
        }

        static OperationFrame.Failure failure(Error code, String detail, String path) {
            return new OperationFrame.Failure(code, detail + ": " + UriMask.mask(path));
        }

        static void observe(String point, String path) {
            if (TestPoints.enabled()) {
                TestPoints.event(point, LockIo.pointKey(path));
            }
        }

        static void hold(String point, String path) {
            if (TestPoints.enabled()) {
                TestPoints.hold(point, LockIo.pointKey(path));
            }
        }

        static boolean transport(Attempt attempt, Exception error) {
            if (error instanceof OperationFrame.Failure) {
                return ((OperationFrame.Failure) error).transportFailure();
            }
            return new ErrorClassifier(attempt.handler().getContext().getHealth().namedLog())
                    .classify(error, attempt.progress().elapsedMillis(), ErrorClassifier.CallType.CHANGE)
                    .getKind().isTransport();
        }

        static void discardTemp(Attempt attempt, AtomicWriter.Temp temp) throws Exception {
            FileObject file = StreamLease.io(attempt.handler()).resolve(temp.uri);
            try {
                if (AtomicOps.existsNow(file) && !file.delete()) {
                    attempt.handler().getContext().getHealth().namedLog().warn("The temp file remains at "
                            + UriMask.mask(temp.uri));
                }
            } catch (Exception error) {
                if (transport(attempt, error)) {
                    throw error;
                }
                attempt.handler().getContext().getHealth().namedLog().warn("The temp file remains at "
                        + UriMask.mask(temp.uri));
            }
        }

        void noteSource(Attempt attempt, FileObject source) throws Exception { }

        abstract void change(Attempt attempt, FileObject source, FileObject target, String path, String targetPath)
                throws Exception;

        void settle(Attempt attempt) throws Exception {
            throw failure(Error.OUTCOME_UNCLEAR, "The request outcome is unclear", attempt.previous.path());
        }

        void finishTemp() { }
    }
}
