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

import com.google.gson.JsonObject;
import org.apache.axiom.om.OMConstants;
import org.apache.axiom.om.OMContainer;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.om.OMSourcedElement;
import org.apache.axiom.om.OMText;
import org.apache.axiom.om.impl.MTOMXMLStreamWriter;
import org.apache.axiom.om.impl.OMContainerEx;
import org.apache.axiom.om.impl.OMNodeEx;
import org.apache.axiom.om.impl.serialize.StreamingOMSerializer;
import org.apache.axis2.AxisFault;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseTransportException;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.commons.vfs.VFSUtils;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.util.InlineExpressionUtil;
import org.jaxen.JaxenException;
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileServerProtocol;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileWriteMode;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.ContentFailure;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.relay.ExpandingMessageFormatter;
import org.wso2.carbon.relay.MessageBuilder;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileObject;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;
import org.wso2.vfs.shared.write.AtomicWriter;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.util.StreamReaderDelegate;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

public class WriteFile extends OperationFrame.Mediator {
    private static final int BUFFER_BYTES = 8192;
    private static final String WRITE_XML_DECLARATION = "WRITE_XML_DECLARATION";
    private static final String MESSAGE_AS_BYTES = "MESSAGE_AS_BYTES";

    @Override
    public void execute(MessageContext message, String responseVariable, Boolean overwriteBody) throws ConnectException {
        Write write;
        try {
            write = new Write(message, Boolean.TRUE.equals(overwriteBody));
        } catch (InvalidConfigurationException | IllegalArgumentException | JaxenException invalid) {
            FileOperationResult result = new FileOperationResult("write", false, Error.INVALID_CONFIGURATION,
                    invalid.getMessage());
            handleConnectorResponse(message, responseVariable, overwriteBody, generateOperationResult(message, result),
                    null, null);
            handleException("Invalid write parameters",
                    new InvalidConfigurationException(UriMask.mask(invalid.getMessage())), message);
            return;
        }
        try {
            long bytes = runFrame(message, Utils.getConnectionName(message), write.retries, write,
                    "write", responseVariable, overwriteBody);
            JsonObject result = generateOperationResult(message, new FileOperationResult("write", true, (int) bytes));
            handleConnectorResponse(message, responseVariable, overwriteBody, result, null, null);
        } finally {
            for (AtomicWriter.Temp temp : write.names) {
                temp.close();
            }
        }
    }

    private final class Write implements OperationFrame.Operation<Long> {
        private NamedLog operationLog;
        private final boolean replaceBody;
        final MessageContext message;
        final String relative;
        final String filename;
        final String content;
        final String mime;
        final Charset encoding;
        final boolean compress;
        final boolean streaming;
        final boolean newline;
        final boolean locking;
        final boolean atomic;
        final FileWriteMode mode;
        final Integer position;
        final boolean carryTime;
        final Long timestamp;
        final String permission;
        final long interval;
        final int retries;
        final String access;
        final List<AtomicWriter.Temp> names = new ArrayList<>();
        String path;
        AtomicWriter.Temp previous;
        boolean ready;
        long bytes;
        boolean targetFailed;
        boolean built;

        Write(MessageContext message, boolean replaceBody) throws InvalidConfigurationException, JaxenException {
            this.message = message;
            this.replaceBody = replaceBody;
            String operand = Utils.lookUpStringParam(message, "filePath");
            mode = FileWriteMode.fromString(Utils.lookUpStringParam(message, "writeMode", "Overwrite"));
            if (mode == null) {
                throw new InvalidConfigurationException("Unknown write mode");
            }
            int slash = operand.lastIndexOf('/');
            filename = operand.substring(slash + 1);
            compress = Utils.lookUpBooleanParam(message, "compress", false);
            if (compress && mode != FileWriteMode.APPEND) {
                int dot = filename.indexOf('.');
                relative = operand.substring(0, slash + 1) + (dot < 0 ? filename : filename.substring(0, dot))
                        + Const.ZIP_FILE_EXTENSION;
            } else {
                relative = operand;
            }
            content = InlineExpressionUtil.processInLineSynapseExpressionTemplate(message,
                    Utils.lookUpStringParam(message, "contentOrExpression", ""));
            mime = Utils.lookUpStringParam(message, "mimeType", "");
            encoding = Charset.forName(Utils.lookUpStringParam(message, "encoding", Const.DEFAULT_ENCODING));
            streaming = Utils.lookUpBooleanParam(message, "enableStreaming", false);
            newline = !streaming && Utils.lookUpBooleanParam(message, "appendNewLine", false);
            String line = Utils.lookUpStringParam(message, "appendPosition", null);
            position = mode == FileWriteMode.APPEND && !streaming && hasContent() && line != null
                    ? Integer.valueOf(line) : null;
            if (position != null && position < 1) {
                throw new InvalidConfigurationException("The append position must be positive");
            }
            locking = Utils.lookUpBooleanParam(message, "enableLock", true);
            atomic = Utils.lookUpBooleanParam(message, "atomicWrite", true);
            Long time = null;
            carryTime = Utils.lookUpBooleanParam(message, "updateLastModified", true);
            if (carryTime) {
                String supplied = Utils.lookUpStringParam(message, "lastModified", null);
                if (supplied != null) {
                    time = Long.valueOf(supplied);
                } else if (message instanceof Axis2MessageContext) {
                    time = VFSUtils.getLastModified(((Axis2MessageContext) message).getAxis2MessageContext());
                }
            }
            timestamp = time;
            permission = Utils.lookUpStringParam(message, "updateFilePermission", null);
            interval = Long.parseLong(Utils.lookUpStringParam(message, "timeBetweenSizeCheck", "0"));
            retries = Integer.parseInt(Utils.lookUpStringParam(message, Const.MAX_RETRY_PARAM, "1"));
            access = Utils.lookUpStringParam(message, Const.DISK_SHARE_ACCESS_MASK, null);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        public void bind(String baseUri) {
            path = baseUri + relative;
        }

        public LinkedHashMap<String, OperationFrame.Lock> paths() {
            LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
            boolean whole = locking && (mode == FileWriteMode.APPEND || !atomic);
            paths.put(path, whole ? OperationFrame.Lock.WHOLE : OperationFrame.Lock.LOCAL);
            return paths;
        }

        public OperationFrame.Use use(String ignored) {
            return OperationFrame.Use.WRITE;
        }

        public OperationFrame.Retry retry() {
            switch (mode) {
                case APPEND: return position == null ? OperationFrame.Retry.APPEND : OperationFrame.Retry.INSERT;
                case CREATE_NEW: return OperationFrame.Retry.CREATE_NEW;
                default: return hasContent() ? OperationFrame.Retry.SAFE : OperationFrame.Retry.BODY;
            }
        }

        public Long removed(String removedPath) throws OperationFrame.Failure {
            throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "The target was removed");
        }

        public void options(FileSystemHandler handler) {
            operationLog = handler.getContext().getHealth().namedLog();
            Utils.addDiskShareAccessMaskToFSO(handler.getFsOptions(), access, operationLog);
            if (TestPoints.enabled() && handler.getContext().getConfiguration().getProtocol() == FileServerProtocol.LOCAL) {
                String name = handler.getContext().getName();
                observeAndWait("write.local.after-borrow", name, "connection=" + name);
            }
        }

        @Override
        public void beforeWholeLock(Attempt attempt, String targetPath) throws Exception {
            FileOperation.prepareTargetParent(attempt, targetPath);
        }

        public Long steps(Attempt attempt) throws Exception {
            attempt.progress().reset("resolve");
            LockIo io = StreamLease.io(attempt.handler());
            FileObject target = attempt.handler().resolveFile(path);
            boolean healthy = true;
            try {
                attempt.progress().reset("stat");
                if (target.isFolder()) {
                    throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "Path points to a folder");
                }
                if (interval > 0 && AtomicOps.existsNow(target)) {
                    long before = target.getContent().getSize();
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                    attempt.progress().reset("stat");
                    target.refresh();
                    if (target.getContent().getSize() != before) {
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The file size changed");
                    }
                }
                if (mode == FileWriteMode.CREATE_NEW && AtomicOps.existsNow(target)) {
                    throw exists();
                }
                boolean insert = position != null && AtomicOps.existsNow(target);
                if (insert || atomic && mode != FileWriteMode.APPEND) {
                    healthy = publish(attempt, io, target, insert);
                } else {
                    boolean append = mode == FileWriteMode.APPEND;
                    if (append && attempt.hasShare(path)) {
                        appendPoint("append.after-share-lock");
                    }
                    if (!hasContent()) {
                        buildBody();
                    }
                    attempt.progress().reset("open");
                    try (OutputStream output = target.getContent().getOutputStream(append)) {
                        if (mode == FileWriteMode.CREATE_NEW || position != null) {
                            attempt.commitCalled();
                        }
                        bytes = transfer(attempt, output, append);
                    }
                    if (append) {
                        appendPoint("append.after-bytes");
                    }
                    healthy = metadata(attempt, target, false);
                }
                return bytes;
            } catch (Exception failure) {
                healthy = !classify(attempt, failure).getKind().isTransport();
                throw failure;
            } finally {
                if (healthy && !attempt.handlerDiscarded()) {
                    target.close();
                }
            }
        }

        private boolean publish(Attempt attempt, LockIo io, FileObject target, boolean insert) throws Exception {
            AtomicWriter writer = attempt.handler().getContext().getWriter();
            if (previous != null) {
                attempt.progress().reset("stat");
                FileObject temp = io.resolve(previous.uri);
                boolean reuse = ready && mode == FileWriteMode.OVERWRITE && AtomicOps.existsNow(temp);
                if (!ready) {
                    temp.delete();
                }
                temp.close();
                if (reuse) {
                    return commit(attempt, io, writer, previous, false);
                }
            }
            ready = false;
            attempt.progress().reset("prepare");
            previous = writer.prepare(io, path);
            names.add(previous);
            attempt.tempPath(previous.uri);
            try {
                attempt.progress().reset("transfer");
                writer.transfer(io, previous, output -> {
                    if (insert) {
                        bytes = insert(target, output, attempt);
                    } else {
                        bytes = transfer(attempt, output, false);
                    }
                });
                FileObject staged = io.resolve(previous.uri);
                metadata(attempt, staged, true);
                staged.close();
            } catch (Exception failure) {
                if (!classify(attempt, failure).getKind().isTransport()) {
                    discard(attempt, io, previous);
                }
                throw failure;
            }
            ready = true;
            return commit(attempt, io, writer, previous, insert);
        }

        private boolean commit(Attempt attempt, LockIo io, AtomicWriter writer, AtomicWriter.Temp temp,
                               boolean insert) throws OperationFrame.Failure {
            boolean take = locking && !attempt.hasShare(path);
            CommitStep.Mode publishMode = mode == FileWriteMode.CREATE_NEW ? CommitStep.Mode.CREATE_NEW
                    : CommitStep.Mode.OVERWRITE;
            if (take) {
                // Charge the share wait to the same budget as local admission.
                attempt.progress().reset("lock");
                try {
                    attempt.acquireCommitLock(path);
                } catch (OperationFrame.Failure failure) {
                    if (!attempt.handlerDiscarded()) {
                        discard(attempt, io, temp);
                    }
                    throw failure;
                }
            }
            attempt.progress().reset("commit");
            AtomicWriter.Result result = writer.commit(io, attempt.lock(path), temp, publishMode, take,
                    1, 0, !insert, () -> filePoint("temp.before-delete-after-app-error"));
            if (result.lock == null) {
                attempt.commitCalled();
            }
            if (result.connectionLost()) {
                attempt.discardHandler();
            }
            if (result.outcome == AtomicWriter.Outcome.COMMITTED) {
                return !result.connectionLost();
            }
            if (result.outcome == AtomicWriter.Outcome.TARGET_EXISTS) {
                throw exists();
            }
            if (result.outcome == AtomicWriter.Outcome.LOCK_BUSY) {
                throw new OperationFrame.Failure(Error.FILE_LOCKING_ERROR, "The target is locked");
            }
            if (result.failure != null) {
                throw new OperationFrame.Failure(Error.OPERATION_ERROR, result.failure);
            }
            String reason = result.commit == null ? result.outcome.name() : result.commit.getAtomicResult().getDetail();
            throw new OperationFrame.Failure(Error.OPERATION_ERROR, reason == null ? result.outcome.name() : reason);
        }

        private OperationFrame.Failure exists() {
            return new OperationFrame.Failure(Error.FILE_ALREADY_EXISTS, "The target file already exists");
        }

        private void discard(Attempt attempt, LockIo io, AtomicWriter.Temp temp) {
            try {
                filePoint("temp.before-delete-after-app-error");
                attempt.progress().reset("delete temp");
                FileObject staged = io.resolve(temp.uri);
                if (!staged.delete() && AtomicOps.existsNow(staged)) {
                    operationLog.warn("The temp file was left at " + UriMask.mask(temp.uri));
                }
                staged.close();
            } catch (Exception cleanup) {
                operationLog.warn("The temp file was left at " + UriMask.mask(temp.uri));
                if (classify(attempt, cleanup).getKind().isTransport()) {
                    attempt.discardHandler();
                }
            }
        }

        private Classification classify(Attempt attempt, Exception error) {
            return new ErrorClassifier(attempt.handler().getContext().getHealth().namedLog())
                    .classify(error, attempt.progress().elapsedMillis(), ErrorClassifier.CallType.CHANGE);
        }

        private void filePoint(String point) {
            if (TestPoints.enabled()) {
                TestPoints.event(point, LockIo.pointKey(path));
            }
        }

        private void appendPoint(String point) {
            if (TestPoints.enabled()) {
                observeAndWait(point, LockIo.pointKey(path));
            }
        }

        private void observeAndWait(String point, String key, String... fields) {
            TestPoints.event(point, key, fields);
            TestPoints.hold(point, key, fields);
        }

        private boolean metadata(Attempt attempt, FileObject file, boolean staged) throws IOException {
            ConnectionContext context = attempt.handler().getContext();
            if (carryTime) {
                if (file.getFileSystem().hasCapability(Capability.SET_LAST_MODIFIED_FILE)) {
                    if (timestamp != null && !attribute(attempt, file, staged, true)) {
                        return false;
                    }
                } else if (context.firstTimeWarning()) {
                    operationLog.debug("The file system does not support setting the file time");
                }
            }
            return permission == null || attribute(attempt, file, staged, false);
        }

        private boolean attribute(Attempt attempt, FileObject file, boolean staged, boolean time) throws IOException {
            String name = attempt.handler().getContext().getName();
            String scheme = file.getName().getScheme();
            if (!time && !("file".equals(scheme) || "sftp".equals(scheme))) {
                operationLog.warn("Cannot set POSIX permissions on " + UriMask.mask(path));
                return true;
            }
            if (TestPoints.enabled() && (staged || time && mode == FileWriteMode.APPEND)) {
                String point = staged ? (time ? "temp.before-time-set" : "temp.before-permission-set")
                        : "append.before-time-set";
                observeAndWait(point, name, "connection=" + name);
            }
            attempt.progress().reset(time ? "set time" : "set permissions");
            try {
                if (time) {
                    file.getContent().setLastModifiedTime(timestamp);
                } else {
                    if (!permission.matches("[0-7]{3,4}")) {
                        throw new IllegalArgumentException("Invalid POSIX mode");
                    }
                    int mode = Integer.parseInt(permission, 8);
                    if ("sftp".equals(scheme)) {
                        ((SftpFileObject) FileObjectUtils.getAbstractFileObject(file)).setPosixMode(mode);
                    } else {
                        try {
                            Files.setAttribute(Paths.get(file.getURL().toURI()), "unix:mode", mode);
                        } catch (URISyntaxException invalid) {
                            throw new IOException(invalid);
                        }
                    }
                }
                return true;
            } catch (IOException | RuntimeException failure) {
                boolean lost = classify(attempt, failure).getKind().isTransport();
                if (lost && staged) {
                    throw failure;
                }
                operationLog.warn("Could not set " + (time ? "time" : "permissions") + " on " + UriMask.mask(path));
                if (lost) {
                    attempt.discardHandler();
                }
                return !lost;
            }
        }

        private OutputStream progressOutput(Attempt attempt, OutputStream destination, boolean append, String cause) {
            return new FilterOutputStream(destination) {
                boolean first = true;

                @Override
                public void write(int value) throws IOException {
                    write(new byte[]{(byte) value}, 0, 1);
                }

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    while (length > 0) {
                        int size = Math.min(length, BUFFER_BYTES);
                        attempt.progress().reset(cause);
                        attempt.bytesSent();
                        try {
                            out.write(buffer, offset, size);
                        } catch (IOException | RuntimeException failure) {
                            targetFailed = true;
                            throw failure;
                        }
                        if (append && first) {
                            first = false;
                            appendPoint("append.in-transfer");
                        }
                        offset += size;
                        length -= size;
                    }
                }

                @Override
                public void flush() throws IOException {
                    try {
                        out.flush();
                    } catch (IOException | RuntimeException failure) {
                        targetFailed = true;
                        throw failure;
                    }
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } catch (IOException | RuntimeException failure) {
                        targetFailed = true;
                        throw failure;
                    }
                }
            };
        }

        private long transfer(Attempt attempt, OutputStream destination, boolean append) throws IOException {
            OutputStream progress = progressOutput(attempt, destination, append, "write");
            boolean zip = compress && (!append || !hasContent());
            if (!zip) {
                return payload(attempt, progress);
            }
            try (ZipOutputStream output = new ZipOutputStream(new CloseShieldOutputStream(progress))) {
                output.putNextEntry(new ZipEntry(filename));
                return payload(attempt, output);
            }
        }

        private long payload(Attempt attempt, OutputStream destination) throws IOException {
            CountingOutputStream output = new CountingOutputStream(destination);
            if (hasContent()) {
                if (!Const.CONTENT_TYPE_BINARY.equals(mime)) {
                    // The separator belongs to the same encoded value, including for encodings with a BOM.
                    try (OutputStreamWriter text = new OutputStreamWriter(new CloseShieldOutputStream(output), encoding)) {
                        text.write(content);
                        if (newline) {
                            text.append(Const.NEW_LINE);
                        }
                    }
                    return output.getByteCount();
                }
                output.write(Base64.getDecoder().decode(content));
            } else {
                org.apache.axis2.context.MessageContext axis = ((Axis2MessageContext) message).getAxis2MessageContext();
                if (!mime.isEmpty() && !Const.CONTENT_TYPE_AUTOMATIC.equals(mime)) {
                    axis.setProperty(Const.MESSAGE_TYPE, mime);
                }
                // The first element of the body is read before the write. A lazy SOAP body then has its header and
                // the start of its body in the tree, so the connector core can take the children out of the body
                // after a write that replaces it spent the parser.
                OMElement first = axis.getEnvelope().getBody().getFirstElement();
                MessageFormatter formatter = getMessageFormatter(axis);
                // With streaming on, a binary body is streamed when the formatter of its message type is the relay
                // formatter, which then reads the content once and keeps no copy. Any other body, and a binary body
                // under another type, is written by the formatter of its type as a write that is not streamed.
                boolean stream = streaming && formatter.getClass() == ExpandingMessageFormatter.class
                        && binaryWrapper(first);
                OMOutputFormat format = BaseUtils.getOMOutputFormat(axis);
                // A body that is not built yet is pulled from its parser and lost unless the formatter keeps it.
                // A JSON stream body is written again from its buffered stream, so it needs no tree.
                boolean keep = !replaceBody && !stream && !unexpandedJson(axis);
                attempt.payloadStarted();
                targetFailed = false;
                boolean broken = false;
                OMElement original = null;
                OMElement copy = null;
                try {
                    if (keep && xmlToJson(axis)) {
                        // The JSON formatter takes the indentation and the namespaces out of the XML element it
                        // writes. The flow keeps its body, so the formatter writes a copy, as a call of an out only
                        // message writes a clone.
                        original = axis.getEnvelope().getBody().getFirstElement();
                        copy = original.cloneOMElement();
                        original.insertSiblingBefore(copy);
                        original.detach();
                    }
                    // A body built before a direct write is written as a kept one, so it keeps its bytes as well.
                    if ((keep || built) && parsedEnvelope(axis, formatter, format)) {
                        writeEnvelope(axis, format, output);
                    } else {
                        formatter.writeTo(axis, format, output, keep);
                    }
                } catch (IOException | RuntimeException failure) {
                    if (targetFailed) {
                        throw failure;
                    }
                    broken = true;
                    // The source of the body failed and not the target, so this is no transport error of the target.
                    throw new ContentFailure("The message body could not be read", failure);
                } finally {
                    if (copy != null) {
                        copy.insertSiblingBefore(original);
                        copy.detach();
                    }
                    if (replaceBody) {
                        // No attempt writes the body again once its payload started. Close what the write left of
                        // the body now, also when the write fails, so that the connector core does not read a
                        // parser the write has spent when it puts the result in place of the body. A body that
                        // broke is closed whole, also where a formatter read it with the cache on.
                        closeSpent(axis.getEnvelope(), broken);
                    }
                }
            }
            if (newline && (hasContent() || !compress)) {
                output.write(Const.NEW_LINE.toString().getBytes(encoding));
            }
            return output.getByteCount();
        }

        // True when the formatter turns an XML body into JSON: the message type names JSON, and the body is XML and
        // not a JSON stream. The text and the binary wrapper are XML here too.
        private boolean xmlToJson(org.apache.axis2.context.MessageContext axis) {
            Object type = axis.getProperty(Const.MESSAGE_TYPE);
            return type != null && type.toString().toLowerCase(Locale.ROOT).contains("json")
                    && axis.getEnvelope().getBody().getFirstElement() != null && !JsonUtil.hasAJsonPayload(axis);
        }

        // A write straight into the target builds an XML body that is not built yet before it opens the target. A call
        // through the VFS transport writes a copy of the message, which is read whole before the target is opened. A
        // body that breaks late then fails before the first byte, and the target stays as it was. A JSON stream and
        // the text and binary wrappers are not XML from a parser and are left alone.
        private void buildBody() throws FileSystemException {
            org.apache.axis2.context.MessageContext axis = ((Axis2MessageContext) message).getAxis2MessageContext();
            if (unexpandedJson(axis)) {
                return;
            }
            try {
                axis.getEnvelope().build();
                OMElement first = axis.getEnvelope().getBody().getFirstElement();
                if (first != null && !(first instanceof OMSourcedElement)) {
                    first.build();
                }
                built = true;
            } catch (RuntimeException failure) {
                if (replaceBody) {
                    closeSpent(axis.getEnvelope(), true);
                }
                throw new ContentFailure("The message body could not be read", failure);
            }
        }

        // True for a SOAP envelope that a parser made, kept, and written whole by the SOAP formatter or by the relay
        // formatter that hands it to the SOAP formatter. With no SwA and no MTOM. A binary body is written as its
        // content by the relay formatter, never as an envelope.
        private boolean parsedEnvelope(org.apache.axis2.context.MessageContext axis, MessageFormatter formatter,
                                       OMOutputFormat format) {
            if (axis.getEnvelope().getBuilder() == null || format.isOptimized() || format.isDoingSWA()) {
                return false;
            }
            if (formatter.getClass() == SOAPMessageFormatter.class) {
                return true;
            }
            // The relay formatter takes every body in the binary wrapper as binary, by the name alone.
            OMElement first = axis.getEnvelope().getBody().getFirstElement();
            if (formatter.getClass() != ExpandingMessageFormatter.class || axis.getOperationContext() == null
                    || first != null && BaseConstants.DEFAULT_BINARY_WRAPPER.equals(first.getQName())) {
                return false;
            }
            Object relayed = axis.getProperty(MessageBuilder.RELAY_FORMATTERS_MAP);
            return axis.getOperationContext().getProperty(MESSAGE_AS_BYTES) == null
                    && axis.getProperty(MessageBuilder.FORCED_RELAY_FORMATTER) == null
                    && !(relayed instanceof Map && ((Map<?, ?>) relayed).get(axis.getProperty(Const.MESSAGE_TYPE))
                    != null);
        }

        private boolean unexpandedJson(org.apache.axis2.context.MessageContext axis) {
            OMElement first = axis.getEnvelope().getBody().getFirstElement();
            return JsonUtil.hasAJsonPayload(axis) && first instanceof OMSourcedElement
                    && !((OMSourcedElement) first).isExpanded();
        }

        private long insert(FileObject target, OutputStream destination, Attempt attempt) throws IOException {
            CountingOutputStream count = new CountingOutputStream(progressOutput(attempt, destination, false, "insert"));
            attempt.progress().reset("insert");
            InputStream source = new FilterInputStream(target.getContent().getInputStream()) {
                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    attempt.progress().reset("insert");
                    return super.read(buffer, offset, Math.min(length, BUFFER_BYTES));
                }
            };
            try (BufferedReader input = new BufferedReader(new InputStreamReader(source, encoding));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(new CloseShieldOutputStream(count),
                         encoding))) {
                int line = 1;
                boolean inserted = false;
                int next = input.read();
                while (next >= 0) {
                    int value = next;
                    if (value == '\r' || value == '\n') {
                        boolean pair = false;
                        next = input.read();
                        if (value == '\r' && next == '\n') {
                            pair = true;
                            next = input.read();
                        }
                        if (!inserted && (line == position || next < 0)) {
                            addText(output, line);
                            inserted = true;
                        }
                        output.write(value);
                        if (pair) {
                            output.write('\n');
                        }
                        line++;
                    } else {
                        output.write(value);
                        next = input.read();
                    }
                }
                if (!inserted) {
                    addText(output, line);
                }
            }
            return count.getByteCount();
        }

        private void addText(BufferedWriter output, int line) throws IOException {
            if (line < position) {
                operationLog.warn("FileConnector:write - Append position is greater than the existing line count of file "
                        + filename + ". Hence appending the content at EOF.");
            }
            output.write(content);
            if (newline) {
                output.append(Const.NEW_LINE);
            }
        }
    }

    // Marks each element the write pulled from its parser as complete and closes that parser, or each element
    // that is not complete when the body broke. Only nodes that are built are visited, so no parser is read.
    private static void closeSpent(OMContainer container, boolean broken) {
        if (container instanceof OMElement) {
            OMElement element = (OMElement) container;
            if (!element.isComplete() && element.getBuilder() != null
                    && (broken || !element.getBuilder().isCache())) {
                element.close(false);
            }
        }
        OMNode child = ((OMContainerEx) container).getFirstOMChildIfAvailable();
        while (child != null) {
            if (child instanceof OMContainer) {
                closeSpent((OMContainer) child, broken);
            }
            child = ((OMNodeEx) child).getNextOMSiblingIfAvailable();
        }
    }

    // Writes a SOAP envelope that a parser made as the parser gives it, through a reader that keeps what it reads. The
    // bytes are those of the file, as the transport sender writes them, and the envelope stays usable for a second
    // write. From its tree Axiom writes only the header, when it has children, and the body, so whitespace and
    // comments under the envelope and an empty header would be lost. The declaration is written as Axiom writes it.
    private static void writeEnvelope(org.apache.axis2.context.MessageContext axis, OMOutputFormat format,
                                      OutputStream output) throws AxisFault {
        if ("false".equalsIgnoreCase((String) axis.getProperty(WRITE_XML_DECLARATION))) {
            format.setIgnoreXMLDeclaration(true);
        }
        MTOMXMLStreamWriter writer = null;
        try {
            writer = new MTOMXMLStreamWriter(output, format);
            if (!writer.isIgnoreXMLDeclaration()) {
                String charset = writer.getCharSetEncoding();
                String version = writer.getXmlVersion();
                writer.getXmlStreamWriter().writeStartDocument(
                        charset == null ? OMConstants.DEFAULT_CHAR_SET_ENCODING : charset,
                        version == null ? OMConstants.DEFAULT_XML_VERSION : version);
            }
            // The plain delegate keeps the data source events off, so an element with a data source is expanded
            // into the tree once and a source that can be read only once is not spent by the first write.
            new StreamingOMSerializer().serialize(new StreamReaderDelegate(axis.getEnvelope().getXMLStreamReader(true)),
                    writer);
            writer.flush();
        } catch (XMLStreamException failure) {
            throw AxisFault.makeFault(failure);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (XMLStreamException ignored) {
                    // The bytes are flushed already. A failure of the target shows at the flush.
                }
            }
        }
    }

    // The binary wrapper holds binary content or nothing. A wrapper whose first child is a text node that is not
    // binary, as a payload factory or an XML parser makes it, is plain XML. It is written by the formatter of its
    // message type, as the transport sender does.
    private static boolean binaryWrapper(OMElement element) {
        if (element == null || !BaseConstants.DEFAULT_BINARY_WRAPPER.equals(element.getQName())) {
            return false;
        }
        OMNode child = element.getFirstOMChild();
        return !(child instanceof OMText) || ((OMText) child).isBinary();
    }

    // The formatter comes from the message type, as the transport sender takes it, and never from the shape of the
    // body. So a binary body under an XML type is written as an envelope, and a body that the formatter of its type
    // does not write gives an empty file, as on the transport.
    private MessageFormatter getMessageFormatter(org.apache.axis2.context.MessageContext msgContext) {
        try {
            return MessageProcessorSelector.getMessageFormatter(msgContext);
        } catch (Exception e) {
            throw new BaseTransportException("Unable to get the message formatter to use");
        }
    }
}
