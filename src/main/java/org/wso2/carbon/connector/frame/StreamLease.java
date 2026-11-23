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

import org.apache.axiom.attachments.SizeAwareDataSource;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFile;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class StreamLease implements SizeAwareDataSource {
    public enum State { ACTIVE, REPLAY, LENT, ENDED }

    public enum Reason { IDLE_LIMIT, RETIRED, TRANSPORT_ERROR, APPLICATION_ERROR, TAKEN_OVER, REMOVED, MOVED }

    public interface Owner {
        long now();
        long idleMs();
        boolean isClosed();
        String getName();
        EndpointHealth getHealth();
        ConnectionLease borrowForStream() throws IOException;
        void wakeReaper();
        void ended(StreamLease lease, HeldLock queued, long deadline);
    }

    private static final class Value {
        final State state;
        final Reason reason;
        final HeldLock lock;
        final ConnectionLease core;
        final FileObject file;
        final Reader stream;
        final boolean inCall;
        final long deadline;

        Value(State state, Reason reason, HeldLock lock, ConnectionLease core, FileObject file,
              Reader stream, boolean inCall, long deadline) {
            this.state = state;
            this.reason = reason;
            this.lock = lock;
            this.core = core;
            this.file = file;
            this.stream = stream;
            this.inCall = inCall;
            this.deadline = deadline;
        }

        static Value replay(HeldLock lock, long deadline) {
            return new Value(State.REPLAY, null, lock, null, null, null, false, deadline);
        }

        static Value ended(Reason reason) {
            return new Value(State.ENDED, reason, null, null, null, null, false, Long.MAX_VALUE);
        }

        Value enter() {
            return new Value(State.ACTIVE, null, lock, core, file, stream, true, deadline);
        }
    }

    private final Owner owner;
    private final String path;
    private final String type;
    private final long size;
    private final long modified;
    private final boolean streaming;
    private final boolean heldLock;
    private final AtomicReference<Value> value;
    private volatile Reason rejected;
    private volatile Classification streamFailure;

    private StreamLease(Owner owner, String path, String type, long size, long modified, boolean streaming,
                        boolean heldLock, Value initial) {
        this.owner = owner;
        this.path = path;
        this.type = type;
        this.size = size;
        this.modified = modified;
        this.streaming = streaming;
        this.heldLock = heldLock;
        value = new AtomicReference<>(initial);
    }

    public static StreamLease active(Owner owner, String path, String type, long size, long modified,
                                     ConnectionLease core, FileObject file, HeldLock lock) {
        return new StreamLease(owner, path, type, size, modified, true, lock != null,
                new Value(State.ACTIVE, null, lock, core, file, null, false, owner.now() + owner.idleMs()));
    }

    public static StreamLease replay(Owner owner, String path, long size, long modified, HeldLock lock) {
        return new StreamLease(owner, path, null, size, modified, false, true,
                Value.replay(Objects.requireNonNull(lock), owner.now() + owner.idleMs()));
    }

    public static StreamLease failed(Owner owner, String path, Reason reason) {
        if (reason != Reason.APPLICATION_ERROR && reason != Reason.TRANSPORT_ERROR) {
            throw new IllegalArgumentException("The read failure reason is required");
        }
        return new StreamLease(owner, path, null, -1, -1, false, true, Value.ended(reason));
    }

    public String connection() {
        return owner.getName();
    }

    public State state() {
        return value.get().state;
    }

    public Reason reason() {
        return value.get().reason;
    }

    public boolean heldLock() {
        return heldLock;
    }

    public void checkDelivery() throws OperationFrame.Failure {
        Reason reason = reason();
        if (reason != null) {
            throw new OperationFrame.Failure(reason == Reason.RETIRED || reason == Reason.TRANSPORT_ERROR
                    ? Error.CONNECTION_ERROR
                    : Error.OPERATION_ERROR,
                    "The read ended during delivery: " + reason);
        }
    }

    public void rejectDelivery(Exception failure) {
        Classification outcome = new ErrorClassifier(owner.getHealth().namedLog()).classify(failure, 0,
                ErrorClassifier.CallType.READ);
        rejected = outcome.getKind().isTransport() ? Reason.TRANSPORT_ERROR : Reason.APPLICATION_ERROR;
        for (;;) {
            Value seen = value.get();
            if (seen.state == State.ENDED || seen.state == State.LENT) {
                return;
            }
            if (seen.state == State.REPLAY) {
                if (value.compareAndSet(seen, Value.ended(rejected))) {
                    owner.ended(this, seen.lock, owner.now() + owner.idleMs());
                    return;
                }
            } else if (!seen.inCall) {
                Value call = seen.enter();
                if (value.compareAndSet(seen, call)) {
                    fail(call, failure, owner.now(), false);
                    return;
                }
            } else if (value.compareAndSet(seen, Value.ended(rejected))) {
                try {
                    abort(seen.core);
                } finally {
                    abandonAndRemove(seen);
                }
                return;
            }
        }
    }

    Classification streamFailure() {
        return streamFailure;
    }

    synchronized void reportOperation(ConnectionLease admission, Classification failure, boolean success) {
        if (streamFailure != null) {
            return;
        }
        if (failure != null && failure.getKind().isTransport()) {
            admission.reportFailure();
            owner.getHealth().failure(failure);
        } else {
            admission.reportSuccess();
            if (success) {
                owner.getHealth().success();
            }
        }
    }

    private synchronized void reportStreamFailure(ConnectionLease core, Classification outcome) {
        streamFailure = outcome;
        core.reportFailure();
        owner.getHealth().failure(outcome);
    }

    public long nextDeadline(long now) {
        Value seen = value.get();
        return seen.inCall && now >= seen.deadline ? Long.MAX_VALUE : seen.deadline;
    }

    @Override
    public String getName() {
        return path;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public String getContentType() {
        return type;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        throw new IOException("The file stream is read only");
    }

    public final class Lending {
        private final Value lent;

        void afterVerifyOwner(int attempt) {
            if (TestPoints.enabled()) {
                hold("frame.after-verify-owner", "attempt=" + attempt);
            }
        }

        private Lending(Value lent) {
            this.lent = lent;
        }

        public HeldLock lock() {
            return lent.lock;
        }

        /** Returns the checked release result if this call used the supplied handler, otherwise null. */
        public LockOutcome giveBack(FileSystemHandler usable) {
            return giveBack(usable, 0);
        }

        LockOutcome giveBack(FileSystemHandler usable, int attempt) {
            Value replay = Value.replay(lent.lock, owner.now() + owner.idleMs());
            if (!value.compareAndSet(lent, replay)) {
                return null;
            }
            hold("lease.after-replay");
            try {
                if (owner.isClosed()) {
                    return finish(replay, Reason.RETIRED, usable, attempt);
                }
                Reason reason = rejected;
                return reason == null ? null : finish(replay, reason, usable, attempt);
            } finally {
                owner.wakeReaper();
            }
        }

        /** Returns the checked release result if this call used the supplied handler, otherwise null. */
        public LockOutcome removed(FileSystemHandler usable) {
            return finish(lent, Reason.REMOVED, usable);
        }

        public void moved() {
            finish(lent, Reason.MOVED, null);
        }

        public void takenOver() {
            finish(lent, Reason.TAKEN_OVER, null);
        }
    }

    public Lending lend() {
        return lend(0);
    }

    Lending lend(int attempt) {
        Value seen = value.get();
        if (seen.state != State.REPLAY || rejected != null) {
            return null;
        }
        Value lent = new Value(State.LENT, null, seen.lock, null, null, null, false, Long.MAX_VALUE);
        hold("lease.before-lend");
        if (!value.compareAndSet(seen, lent)) {
            return null;
        }
        hold("lease.after-lend");
        boolean retired = owner.isClosed();
        hold("lease.after-mark");
        if (retired) {
            finish(lent, Reason.RETIRED, null, attempt);
            return null;
        }
        return new Lending(lent);
    }

    private LockOutcome finish(Value seen, Reason reason, FileSystemHandler usable) {
        return finish(seen, reason, usable, 0);
    }

    private LockOutcome finish(Value seen, Reason reason, FileSystemHandler usable, int attempt) {
        hold("lease.before-end");
        if (!value.compareAndSet(seen, Value.ended(reason))) {
            return null;
        }
        try {
            if (seen.lock != null && reason != Reason.MOVED && reason != Reason.TAKEN_OVER) {
                if (usable != null && usable.getContext() == owner) {
                    return seen.lock.releaseChecked(io(usable));
                }
                seen.lock.abandon();
            }
            return null;
        } finally {
            owner.ended(this, null, 0);
            hold("lease.after-end", "reason=" + reason);
            if (TestPoints.enabled() && reason == Reason.RETIRED && attempt != 0) {
                hold("lease.after-retired-end", "attempt=" + attempt);
            }
        }
    }

    public void expire(long now) {
        Value seen = value.get();
        if (seen.inCall || now < seen.deadline) {
            return;
        }
        if (seen.state == State.REPLAY) {
            expireReplay(seen);
        } else if (seen.state == State.ACTIVE && value.compareAndSet(seen, Value.ended(Reason.IDLE_LIMIT))) {
            try {
                if (seen.stream == null) {
                    seen.core.returnToPool();
                } else {
                    try {
                        abort(seen.core);
                    } finally {
                        invalidateBetweenCalls(seen.core, seen.stream);
                    }
                }
            } finally {
                abandonAndRemove(seen);
            }
        }
    }

    private void expireReplay(Value seen) {
        if (value.compareAndSet(seen, Value.ended(Reason.IDLE_LIMIT))) {
            owner.ended(this, seen.lock, seen.deadline + owner.idleMs());
        }
    }

    public void retire() {
        for (;;) {
            Value seen = value.get();
            if (seen.state == State.LENT || seen.state == State.ENDED) {
                return;
            }
            if (value.compareAndSet(seen, Value.ended(Reason.RETIRED))) {
                try {
                    if (seen.core != null) {
                        try {
                            abort(seen.core);
                        } finally {
                            if (!seen.inCall) {
                                invalidateBetweenCalls(seen.core, seen.stream);
                            }
                        }
                    }
                } finally {
                    abandonAndRemove(seen);
                }
                return;
            }
        }
    }

    private void abandonAndRemove(Value seen) {
        try {
            if (seen.lock != null) {
                seen.lock.abandon();
            }
        } finally {
            owner.ended(this, null, 0);
        }
    }

    private static FileSystemHandler handler(ConnectionLease lease) {
        return (FileSystemHandler) lease.getConnection();
    }

    private static void abort(ConnectionLease lease) {
        ((DefaultFileSystemManager) handler(lease).getFsManager()).abortAll();
    }

    // Initialized only for abandoned local files. Queue entries keep their original pool loan
    // until the local close returns. No redeploy can transfer that loan to a replacement pool.
    private static final class LocalDisposals {
        private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), action -> {
                    Thread worker = new Thread(action, "File stream local disposal");
                    worker.setDaemon(true);
                    worker.setContextClassLoader(null);
                    return worker;
                });

        static {
            WORKERS.allowCoreThreadTimeOut(true);
        }
    }

    private void invalidateBetweenCalls(ConnectionLease core, Reader stream) {
        if (stream != null && stream.localFile) {
            LocalDisposals.WORKERS.execute(() -> invalidate(core, stream));
        } else {
            core.invalidate();
        }
    }

    private void invalidate(ConnectionLease core, Reader stream) {
        try {
            if (stream != null && stream.localFile) {
                stream.closeUnderlying();
            }
        } catch (IOException | RuntimeException failure) {
            owner.getHealth().namedLog().warn("Closing the local file stream failed. " + failure);
        } finally {
            core.invalidate();
        }
    }

    public static LockIo io(FileSystemHandler handler) {
        return new LockIo(handler.getFsManager(), handler.getFsOptions());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (!TestPoints.enabled()) {
            return open();
        }
        try {
            InputStream reader = open();
            ioEvent("open", reader, 0, false, false, "success");
            return reader;
        } catch (IOException | RuntimeException failure) {
            ioEvent("open", null, 0, false, false, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private InputStream open() throws IOException {
        Value seen = value.get();
        if (rejected != null) {
            throw unavailable();
        }
        if (!streaming) {
            throw new IOException("The read has no stream");
        }
        boolean resend = seen.state == State.REPLAY;
        Value call = resend ? secondOpen(seen) : enter(seen, null);
        FileObject file = call.file;
        Reader reader;
        long start = owner.now();
        try {
            if (resend) {
                file = handler(call.core).resolveFile(path);
                if (file.getContent().getSize() != size || file.getContent().getLastModifiedTime() != modified) {
                    FileSystemException changed = new FileSystemException("The size or modification time of the file changed");
                    changed.setFault(Fault.APPLICATION);
                    throw changed;
                }
            }
            boolean local = FileObjectUtils.isInstanceOf(file, LocalFile.class);
            reader = new Reader(file.getContent().getInputStream(), local);
        } catch (IOException | RuntimeException failure) {
            throw fail(call, failure, start, false);
        }
        Value open = new Value(State.ACTIVE, null, call.lock, call.core, file, reader, false, owner.now() + owner.idleMs());
        leave(call, open);
        return reader;
    }

    private Value secondOpen(Value seen) throws IOException {
        if (owner.now() >= seen.deadline) {
            expireReplay(seen);
            throw unavailable();
        }
        hold("lease.second-open.before-borrow");
        ConnectionLease core = owner.borrowForStream();
        seen = value.get();
        if (seen.state != State.REPLAY || owner.now() >= seen.deadline) {
            core.returnToPool();
            if (seen.state == State.REPLAY) {
                expireReplay(seen);
            }
            throw unavailable();
        }
        Value call = new Value(State.ACTIVE, null, seen.lock, core, null, null, true, seen.deadline);
        if (!value.compareAndSet(seen, call)) {
            core.returnToPool();
            throw unavailable();
        }
        if (owner.isClosed()) {
            if (value.compareAndSet(call, Value.ended(Reason.RETIRED))) {
                try {
                    core.returnToPool();
                } finally {
                    abandonAndRemove(call);
                }
            } else {
                core.invalidate();
            }
            throw unavailable();
        }
        return call;
    }

    private Value enter(Value seen, Reader expected) throws IOException {
        if (seen.state == State.ACTIVE && !seen.inCall && seen.stream == expected) {
            Value call = seen.enter();
            if (value.compareAndSet(seen, call)) {
                return call;
            }
        }
        throw unavailable();
    }

    private void leave(Value call, Value next) throws IOException {
        if (!value.compareAndSet(call, next)) {
            // An open may have acquired its stream after the retiring thread observed the call.
            invalidate(call.core, next.stream != null ? next.stream : call.stream);
            throw unavailable();
        }
        if (next.state == State.REPLAY || owner.now() >= call.deadline) {
            owner.wakeReaper();
        }
    }

    private IOException unavailable() {
        Value seen = value.get();
        if (seen.reason == Reason.IDLE_LIMIT) {
            return new IOException("The stream idle limit was reached");
        }
        if (seen.reason == Reason.RETIRED) {
            return new IOException("The connection was retired");
        }
        if (seen.state == State.LENT) {
            return new IOException("An operation of this message holds the stream lease");
        }
        return new IOException("The stream is unavailable " + (seen.reason == null ? seen.state : seen.reason));
    }

    private IOException fail(Value call, Throwable failure, long start, boolean closing) {
        if (value.get() != call) {
            invalidate(call.core, call.stream);
            return unavailable();
        }
        ErrorClassifier classifier = new ErrorClassifier(owner.getHealth().namedLog());
        Classification outcome = classifier.classify(failure, owner.now() - start, ErrorClassifier.CallType.READ);
        if (!outcome.getKind().isTransport()) {
            if (call.stream != null && !closing) {
                try {
                    call.stream.closeUnderlying();
                } catch (IOException | RuntimeException close) {
                    if (failure != close) {
                        failure.addSuppressed(close);
                    }
                    outcome = classifier.classify(close, owner.now() - start, ErrorClassifier.CallType.READ);
                }
            }
            if (!outcome.getKind().isTransport() && call.lock != null && value.get() == call) {
                LockOutcome release = call.lock.releaseChecked(io(handler(call.core)));
                if (release.classification != null && release.classification.getKind().isTransport()) {
                    outcome = release.classification;
                }
            }
        }
        boolean transport = outcome.getKind().isTransport();
        Reason reason = transport ? Reason.TRANSPORT_ERROR : Reason.APPLICATION_ERROR;
        if (!value.compareAndSet(call, Value.ended(reason))) {
            invalidate(call.core, call.stream);
            return unavailable();
        }
        try {
            if (transport) {
                try {
                    invalidate(call.core, call.stream);
                } finally {
                    try {
                        if (call.lock != null) {
                            call.lock.abandon();
                        }
                    } finally {
                        reportStreamFailure(call.core, outcome);
                    }
                }
            } else {
                call.core.returnToPool();
            }
        } finally {
            owner.ended(this, null, 0);
        }
        return failure instanceof IOException ? (IOException) failure : new IOException(failure);
    }

    private final class Reader extends InputStream {
        private final InputStream raw;
        private final boolean localFile;
        private volatile boolean finished;
        private boolean closeStarted;
        private long observedBytes;

        Reader(InputStream raw, boolean localFile) {
            this.raw = raw;
            this.localFile = localFile;
        }

        // The ACTIVE call owner or the winner between calls owns this stream exclusively.
        private void closeUnderlying() throws IOException {
            if (!closeStarted) {
                closeStarted = true;
                raw.close();
            }
        }

        @Override
        public int read() throws IOException {
            return readRaw(null, 0, 0);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes);
            if (offset < 0 || length < 0 || length > bytes.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            return readRaw(bytes, offset, length);
        }

        private int readRaw(byte[] bytes, int offset, int length) throws IOException {
            if (!TestPoints.enabled()) {
                return readCall(bytes, offset, length);
            }
            try {
                int count = readCall(bytes, offset, length);
                ioEvent("read", this, count < 0 ? 0 : (bytes == null ? 1 : count), count < 0, finished, "success");
                return count;
            } catch (IOException | RuntimeException failure) {
                ioEvent("read", this, 0, false, finished, failure.getClass().getSimpleName());
                throw failure;
            }
        }

        private int readCall(byte[] bytes, int offset, int length) throws IOException {
            if (finished) {
                return bytes != null && length == 0 ? 0 : -1;
            }
            Value call = enter(value.get(), this);
            long start = owner.now();
            int count;
            try {
                if (TestPoints.enabled()) {
                    observe("reader.in-read", "state=ACTIVE");
                    if (observedBytes == size && (bytes == null || length > 0)) {
                        observe("reader.before-last-read", "expectedOffset=" + size);
                    }
                }
                count = bytes == null ? raw.read() : raw.read(bytes, offset, length);
                if (TestPoints.enabled() && count >= 0) {
                    observedBytes += bytes == null ? 1 : count;
                }
            } catch (IOException | RuntimeException failure) {
                throw fail(call, failure, start, false);
            }
            if (count == -1) {
                if (value.get() != call) {
                    invalidate(call.core, call.stream);
                    throw unavailable();
                }
                closeCall(call, start);
            } else {
                leave(call, new Value(State.ACTIVE, null, call.lock, call.core, call.file, this, false,
                        owner.now() + owner.idleMs()));
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            if (!TestPoints.enabled()) {
                finishStream();
                return;
            }
            try {
                finishStream();
                ioEvent("close", this, 0, false, finished, "success");
            } catch (IOException | RuntimeException failure) {
                ioEvent("close", this, 0, false, finished, failure.getClass().getSimpleName());
                throw failure;
            }
        }

        private void finishStream() throws IOException {
            if (!finished) {
                closeCall(enter(value.get(), this), owner.now());
            }
        }

        private void closeCall(Value call, long start) throws IOException {
            if (TestPoints.enabled()) {
                observe("reader.before-close", "state=ACTIVE");
            }
            try {
                closeUnderlying();
            } catch (IOException | RuntimeException failure) {
                throw fail(call, failure, start, true);
            }
            leave(call, Value.replay(call.lock, owner.now() + owner.idleMs()));
            finished = true;
            call.core.returnToPool();
            if (TestPoints.enabled()) {
                observe("read.end", "mode=streamed", "bytes=" + observedBytes);
            }
        }
    }

    void observe(String point, String... details) {
        TestPoints.event(point, id(), details);
    }

    private String id() {
        return Integer.toUnsignedString(System.identityHashCode(this));
    }

    private void ioEvent(String operation, InputStream stream, int bytes, boolean eof, boolean closed,
                         String outcome) {
        String frames = Arrays.stream(Thread.currentThread().getStackTrace())
                    .map(StackTraceElement::toString).collect(Collectors.joining("|"));
        Value seen = value.get();
        TestPoints.event("lease.io", id(), "op=" + operation, "stream=" + (stream == null ? "none"
                : Integer.toUnsignedString(System.identityHashCode(stream))), "bytes=" + bytes, "eof=" + eof,
                "closed=" + closed, "state=" + seen.state, "reason=" + seen.reason, "outcome=" + outcome,
                "frames=" + frames);
    }

    private void hold(String point, String... details) {
        if (TestPoints.enabled()) {
            TestPoints.hold(point, id(), details);
        }
    }
}
