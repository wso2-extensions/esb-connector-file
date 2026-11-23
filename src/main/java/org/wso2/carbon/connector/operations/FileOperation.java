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

import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;
import com.google.gson.JsonObject;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.SocketException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.write.AtomicWriter;

abstract class FileOperation extends OperationFrame.Mediator {
    private final String name;
    private final String input;
    private final String output;

    FileOperation(String name, String input) {
        this(name, input, null);
    }

    FileOperation(String name, String input, String output) {
        this.name = name;
        this.input = input;
        this.output = output;
    }

    @Override
    public void execute(MessageContext message, String responseVariable, Boolean overwriteBody) throws ConnectException {
        Call call = new Call(message);
        String connection;
        try {
            configure(call);
            call.relative = Utils.lookUpStringParam(message, input);
            call.destination = output == null ? null : Utils.lookUpStringParam(message, output);
            call.retries = Integer.parseInt(call.option("maxRetries", "1"));
            connection = Utils.getConnectionName(message);
        } catch (InvalidConfigurationException | IllegalArgumentException invalid) {
            JsonObject result = Utils.generateOperationResult(message,
                    new FileOperationResult(name, false, Error.INVALID_CONFIGURATION, invalid.getMessage()));
            handleConnectorResponse(message, responseVariable, overwriteBody, result, null, null);
            String detail = UriMask.mask(invalid.getMessage());
            handleException(detail, new InvalidConfigurationException(detail), message);
            return;
        }
        JsonObject result = runFrame(message, connection, call.retries, call, name, responseVariable, overwriteBody);
        if (call.onSuccess != null) {
            call.onSuccess.run();
        } else {
            handleConnectorResponse(message, responseVariable, overwriteBody, result, null, null);
        }
    }

    void configure(Call call) throws InvalidConfigurationException { }

    abstract void perform(Call call, Attempt attempt, FileObject file, JsonObject result) throws Exception;

    final class Call implements OperationFrame.Operation<JsonObject> {
        final MessageContext message;
        NamedLog connectionLog;
        String relative;
        String destination;
        String source;
        String target;
        private Pattern pattern;
        private AdvancedFileFilter filter;
        private long sizeCheckInterval;
        boolean flatListing;
        String tempFolder;
        String tempSuffix;
        int retries;
        OperationFrame.Use sourceUse;
        boolean knownOutput;
        Runnable onSuccess;
        long partSize;
        OperationFrame.Retry retry = OperationFrame.Retry.READ;

        Call(MessageContext message) {
            this.message = message;
        }

        String option(String key) { return option(key, null); }

        String option(String key, String fallback) {
            return Utils.lookUpStringParam(message, key, fallback);
        }

        boolean flag(String key, boolean fallback) {
            return Utils.lookUpBooleanParam(message, key, fallback);
        }

        int depth() {
            try {
                return Integer.parseInt(option("subDirectoryMaxDepth", "-1"));
            } catch (NumberFormatException invalid) {
                return -1;
            }
        }

        FileSorter sorter() {
            return new FileSorter(option("sortingAttribute", FileSorter.SORT_ATTRIB_NAME),
                    option("sortingOrder", FileSorter.SORT_ORDER_ASCENDING), connectionLog);
        }

        void prepareGuard(String patternParam) {
            boolean advanced = option("includeFiles") != null || option("excludeFiles") != null
                    || option("maxFileAge") != null || option("minFileAge") != null;
            String expression = patternParam == null || advanced ? null : option(patternParam);
            pattern = expression == null ? null : Pattern.compile(expression);
            filter = new AdvancedFileFilter(option("fileFilterType"), option("includeFiles"),
                    option("excludeFiles"), option("maxFileAge"), option("minFileAge"));
            sizeCheckInterval = Long.parseLong(option("timeBetweenSizeCheck", "0"));
        }

        SourceGuard guard() {
            return new SourceGuard(pattern, filter, sizeCheckInterval, tempFolder, tempSuffix);
        }

        @Override public void bind(String base) {
            source = base + relative;
            target = destination == null ? null : base + destination;
        }

        @Override public LinkedHashMap<String, OperationFrame.Lock> paths() {
            LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
            if (sourceUse != null) {
                paths.put(source, OperationFrame.Lock.NONE);
            }
            if (knownOutput) {
                paths.put(target, flag("atomicWrite", true) ? OperationFrame.Lock.LOCAL : OperationFrame.Lock.WHOLE);
            }
            return paths;
        }

        @Override public OperationFrame.Use use(String path) {
            return path.equals(target) ? OperationFrame.Use.WRITE : sourceUse;
        }

        @Override public OperationFrame.Retry retry() {
            return retry;
        }

        @Override public void options(FileSystemHandler handler) {
            connectionLog = handler.getContext().getHealth().namedLog();
            Utils.addDiskShareAccessMaskToFSO(handler.getFsOptions(), option(Const.DISK_SHARE_ACCESS_MASK), connectionLog);
            tempFolder = handler.getContext().getConfiguration().getTempFolderName();
            tempSuffix = handler.getContext().getConfiguration().getTempFileSuffix();
        }

        @Override public JsonObject steps(Attempt attempt) throws Exception {
            JsonObject result = Utils.generateOperationResult(message, new FileOperationResult(name, true));
            try (FileObject file = attempt.handler().resolveFile(source)) {
                perform(this, attempt, file, result);
            }
            return result;
        }

        @Override public JsonObject removed(String path) throws OperationFrame.Failure {
            throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "This message removed the file");
        }

        void requireFolder(FileObject file) throws Exception {
            if (!AtomicOps.existsNow(file) || !file.isFolder()) {
                throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "The source directory does not exist");
            }
        }

        void requireSource(Attempt attempt, FileObject file, SourceGuard guard) throws Exception {
            if (!AtomicOps.existsNow(file) || !file.isFile()) {
                throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "The source file does not exist");
            }
            if (!guard.accepts(file)) {
                throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The source file is not eligible");
            }
            attempt.lockPath(source, OperationFrame.Lock.GUARD);
        }

        void write(Attempt attempt, String path, boolean append, AtomicWriter.Content content) throws Exception {
            requireHandler(attempt);
            boolean atomic = !append && flag("atomicWrite", true);
            LockIo io = StreamLease.io(attempt.handler());
            attempt.lockTarget(path, atomic ? OperationFrame.Lock.LOCAL : OperationFrame.Lock.WHOLE);
            if (!atomic) {
                try (FileObject file = io.resolve(path);
                     OutputStream stream = file.getContent().getOutputStream(append)) {
                    content.writeTo(stream);
                }
            } else {
                AtomicWriter writer = attempt.handler().getContext().getWriter();
                attempt.progress().reset("prepare");
                try (AtomicWriter.Temp temp = writer.prepare(io, path)) {
                    attempt.tempPath(temp.uri);
                    try {
                        attempt.progress().reset("transfer");
                        writer.transfer(io, temp, content);
                        if (!attempt.hasShare(path)) {
                            attempt.acquireCommitLock(path);
                        }
                    } catch (Exception error) {
                        if (!attempt.handlerDiscarded() && !FileTreeOperation.Walk.transport(attempt, error)) {
                            FileTreeOperation.Walk.discardTemp(attempt, temp);
                        }
                        throw error;
                    }
                    attempt.progress().reset("commit");
                    attempt.commitCalled();
                    AtomicWriter.Result committed = writer.commit(io, attempt.lock(path), temp,
                            CommitStep.Mode.OVERWRITE, false, 1, 0, true, null);
                    if (committed.connectionLost()) {
                        attempt.discardHandler();
                    }
                    if (committed.outcome != AtomicWriter.Outcome.COMMITTED) {
                        if (committed.failure != null) {
                            throw new OperationFrame.Failure(Error.OPERATION_ERROR, committed.failure);
                        }
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, committed.outcome.name());
                    }
                }
            }
            attempt.releasePath(path);
            attempt.memberDone();
        }
    }

    static void requireHandler(Attempt attempt) throws SocketException {
        if (attempt.handlerDiscarded()) {
            throw new SocketException("The connection was lost after writing the file");
        }
    }

    static InputStream partInput(Attempt attempt, FileObject source) throws IOException {
        return new FilterInputStream(source.getContent().getInputStream()) {
            private boolean ended;

            @Override public int read() throws IOException {
                if (ended) {
                    return -1;
                }
                requireHandler(attempt);
                int value = super.read();
                ended = value == -1;
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (ended) {
                    return -1;
                }
                requireHandler(attempt);
                int count = super.read(bytes, offset, length);
                ended = count == -1;
                return count;
            }
        };
    }

    static long copy(InputStream input, OutputStream output, Attempt attempt, boolean append) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > 0) {
                if (append && attempt != null) {
                    attempt.bytesSent();
                }
                output.write(buffer, 0, count);
                if (attempt != null) {
                    attempt.progress().reset("transfer");
                }
                total += count;
            }
        }
        return total;
    }
}
