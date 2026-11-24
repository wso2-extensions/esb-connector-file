/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.connector.frame;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.ConnectionContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.connection.FileSystemHandlerFactory;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.CircuitOpenException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.connection.PoolBusyException;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.error.ErrorKind;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.log.UriMask;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class OperationFrame {
    public enum Lock { LOCAL, WHOLE, OWN, GUARD, NONE, READ_INPUT }
    public enum Retry { READ, SAFE, BODY, APPEND, INSERT, CREATE_NEW, MOVE, MOVE_NO_REPLACE, DELETE }
    public enum Use { READ, SOURCE, DELETE, WRITE }
    public enum Member { READY, LOCKED, APPLICATION_ERROR, REMOVED }

    public abstract static class Mediator extends AbstractConnectorOperation {
        protected <T> T runFrame(MessageContext message, String connection, Integer retries, Operation<T> operation,
                                 String name, String responseVariable, Boolean overwriteBody) throws ConnectException {
            try {
                return run(message, connection, retries, operation);
            } catch (Failure failure) {
                FileOperationResult result = new FileOperationResult(name, false, failure.error, failure.getMessage());
                JsonObject payload = Utils.generateOperationResult(message, result);
                if (failure.targetComplete) {
                    payload.addProperty("targetComplete", true);
                }
                if (failure.unclearMember != null) {
                    JsonObject member = new JsonObject();
                    member.addProperty("name", UriMask.mask(failure.unclearMember));
                    member.addProperty("reason", "UNCLEAR");
                    JsonArray left = new JsonArray();
                    left.add(member);
                    payload.add("filesLeft", left);
                }
                if (operation != null) {
                    operation.resultDetails(payload);
                }
                handleConnectorResponse(message, responseVariable, overwriteBody, payload, null, null);
                handleException(failure.getMessage(), failure, message);
                throw new ConnectException(failure);
            }
        }
    }

    public interface Operation<T> {
        LinkedHashMap<String, Lock> paths();
        Use use(String path);
        Retry retry();
        T steps(Attempt attempt) throws Exception;
        T removed(String path) throws Failure;
        default void resultDetails(JsonObject result) { }
        default void bind(String baseUri) { }
        default void options(FileSystemHandler handler) throws Exception { }
        default void beforeWholeLock(Attempt attempt, String path) throws Exception { }
    }

    public static final class RetryNote {
        MovePhase phase;
        String tempPath;
        boolean committed;
        Attempt.Request request;
        String path;
        Long sourceSize;
        Long sourceModified;
        String member;

        RetryNote() { }

        RetryNote(RetryNote prior) {
            phase = prior.phase;
            tempPath = prior.tempPath;
            committed = prior.committed;
            request = prior.request;
            path = prior.path;
            sourceSize = prior.sourceSize;
            sourceModified = prior.sourceModified;
            member = prior.member;
        }

        public MovePhase phase() { return phase; }
        public String tempPath() { return tempPath; }
        public boolean committed() { return committed; }
        public Attempt.Request request() { return request; }
        public String path() { return path; }
        public Long sourceSize() { return sourceSize; }
        public Long sourceModified() { return sourceModified; }
        public String member() { return member; }
        public boolean looksOnly() { return request != null && request != Attempt.Request.DELETE_TARGET; }
    }

    public static final class Failure extends Exception {
        public final Error error;
        private final boolean local;
        private final Classification classified;
        private boolean targetComplete;
        private String unclearMember;

        private Failure withFacts(RetryNote note) {
            if (error == Error.OUTCOME_UNCLEAR || error == Error.OPERATION_ERROR) {
                targetComplete = note.committed;
            }
            if (error == Error.OUTCOME_UNCLEAR) {
                unclearMember = note.member;
            }
            return this;
        }

        public boolean transportFailure() {
            return classified != null && classified.getKind().isTransport();
        }

        public Failure(Error error, String detail) {
            this(error, detail, false, null);
        }

        public Failure(Error error, Classification classified) {
            this(error, classified.getStatusText(), false, classified);
        }

        private Failure(Error error, String detail, boolean local, Classification classified) {
            super(UriMask.mask(detail));
            this.error = error;
            this.local = local;
            this.classified = classified;
        }
    }

    private static final AtomicLong IDS = new AtomicLong();
    private final ConnectionHandler pool;
    private final String connection;
    private final MessageContext message;
    private final ConnectionContext context;
    private final String operation;
    // The lock wait budget counts only the time spent in the lock calls that wait and in the pauses between tries.
    private long waited;
    private final Set<String> warned = new HashSet<>();
    private final Map<String, StreamLease.Lending> lent = new LinkedHashMap<>();
    private final Set<String> removed = new HashSet<>();

    private OperationFrame(MessageContext message, String connection, ConnectionContext context) {
        this.context = context;
        this.message = message;
        this.connection = connection;
        pool = ConnectionHandler.getConnectionHandler();
        operation = Long.toString(IDS.incrementAndGet());
    }

    public static <T> T run(MessageContext message, String connection, Integer maxRetries, Operation<T> op)
            throws Failure {
        if (connection == null || op == null || maxRetries != null && maxRetries < 0) {
            throw local(Error.INVALID_CONFIGURATION, "Invalid file operation parameters");
        }
        Object factory = ConnectionHandler.getConnectionHandler().getConnectionFactory(Const.CONNECTOR_NAME, connection);
        if (!(factory instanceof FileSystemHandlerFactory)) {
            throw local(Error.CONNECTION_ERROR, "The file connection is missing");
        }
        ConnectionContext context;
        try {
            context = ((FileSystemHandlerFactory) factory).getContext();
        } catch (Exception error) {
            throw local(Error.CONNECTION_ERROR, error.toString());
        }
        return new OperationFrame(message, connection, context).execute(op, maxRetries == null ? 1 : maxRetries);
    }

    private static Failure local(Error error, String detail) {
        return new Failure(error, detail, true, null);
    }

    private static Failure caused(Failure failure, Exception error) {
        if (failure != error && failure.getCause() == null) {
            failure.initCause(masked(error, Collections.newSetFromMap(new IdentityHashMap<>())));
        }
        return failure;
    }

    // A copy of the chain for the log. Each text passes the mask, and each link keeps its class name and its stack.
    private static Throwable masked(Throwable error, Set<Throwable> seen) {
        if (error == null || !seen.add(error)) {
            return null;
        }
        String text = error.getMessage() == null ? error.getClass().getName()
                : error.getClass().getName() + ": " + UriMask.mask(error.getMessage());
        Throwable copy = new Exception(text, masked(error.getCause(), seen));
        copy.setStackTrace(error.getStackTrace());
        return copy;
    }

    private <T> T execute(Operation<T> op, int retries) throws Failure {
        op.bind(context.getBaseUri());
        Map<String, Lock> paths = new LinkedHashMap<>();
        String readInput = null;
        for (Map.Entry<String, Lock> declaration : op.paths().entrySet()) {
            String path = declaration.getKey();
            if (declaration.getValue() == Lock.READ_INPUT) {
                readInput = context.getBaseUri() + path;
                path = readInput;
            }
            paths.put(path, declaration.getValue());
        }
        RetryNote note = new RetryNote();
        Classification prior = null;
        ConnectionLease priorLoan = null;
        Map<String, HeldLock> kept = Collections.emptyMap();
        for (int number = 1; ; number++) {
            Attempt attempt = new Attempt(this, number, connection, operation, note, lent, removed);
            attempt.readInput = readInput;
            Classification failure = null;
            ConnectionLease admission = null;
            boolean retry = false;
            boolean fast = false;
            boolean count = false;
            boolean success = false;
            boolean keep = false;
            try {
                if (pool.isOpenBeforeDeadline(Const.CONNECTOR_NAME, connection)) {
                    throw local(Error.CONNECTION_SUSPENDED, "The connection is suspended");
                }
                awaitLocalLocks(attempt, paths, kept);
                attempt.hold("frame.before-borrow");
                attempt.loan = pool.borrowConnection(Const.CONNECTOR_NAME, connection);
                admission = attempt.loan;
                String removedPath = prepareBorrowedPaths(attempt, op, paths, kept);
                if (removedPath != null) {
                    return op.removed(removedPath);
                }
                attempt.hold("frame.step6.start");
                T result = op.steps(attempt);
                count = true;
                success = true;
                return result;
            } catch (Exception error) {
                Failure declared = error instanceof Failure ? (Failure) error : null;
                if (admission == null || declared != null && declared.local) {
                    if (prior != null && (admission == null || declared != null
                            && (declared.error == Error.CONNECTION_ERROR || declared.error == Error.CONNECTION_SUSPENDED))) {
                        priorLoan.reportFailure();
                        context.getHealth().failure(prior);
                        throw transport(op.retry(), note, prior);
                    }
                    if (declared != null) {
                        throw declared;
                    }
                    Error code = error instanceof CircuitOpenException ? Error.CONNECTION_SUSPENDED
                            : error instanceof PoolBusyException ? Error.POOL_BUSY : Error.CONNECTION_ERROR;
                    IllegalStateException closed = cause(error, IllegalStateException.class);
                    String detail = closed != null && "Pool not open".equals(closed.getMessage())
                            ? "The pool closed during rotation" : error.toString();
                    throw caused(local(code, detail), error);
                }
                failure = classifyAttempt(attempt, op, error, declared);
                count = true;
                if (failure == null || !failure.getKind().isTransport()) {
                    throw caused(applicationFailure(attempt, error, declared, failure), error);
                }
                attempt.invalid = true;
                record(attempt, failure);
                note = attempt.note;
                fast = failure.isFast();
                retry = !attempt.streamHandedOver && fast && number <= retries && !admission.isTrial()
                        && safe(op.retry(), attempt) && pool.getBreakerState(Const.CONNECTOR_NAME, connection) == CircuitBreaker.Mode.CLOSED;
                keep = fast && !attempt.acquisitionFailed && (!retry || note.looksOnly());
                if (!retry) {
                    throw caused(transport(op.retry(), note, failure), error);
                }
                prior = failure;
                priorLoan = admission;
            } finally {
                try {
                    if (!success && count) {
                        recordFailedReads(attempt, op, paths, failure);
                    }
                    for (Map.Entry<String, HeldLock> entry : kept.entrySet()) {
                        if (attempt.io == null || attempt.invalid && !retry) {
                            entry.getValue().abandon();
                        } else {
                            attempt.own(entry.getKey(), entry.getValue(), true);
                        }
                    }
                    kept = attempt.finishLocks(keep);
                    if (!retry) {
                        attempt.endLending();
                    }
                } finally {
                    try {
                        attempt.endBorrow();
                    } finally {
                        try {
                            // The release try borrows before the failure reaches the breaker, so a breaker
                            // that this failure opens can not refuse it.
                            if (!retry && !kept.isEmpty()) {
                                releaseTry(kept);
                            }
                        } finally {
                            if (admission != null && !retry) {
                                if (!count) {
                                    admission.cancelTrial();
                                } else {
                                    reportOutcome(attempt, admission, failure, success);
                                }
                            }
                        }
                    }
                }
                if (TestPoints.enabled() && success && attempt.builtDelivery != null) {
                    attempt.builtDelivery.observe("read.end", "mode=built");
                }
                String detail = "attempt=" + number + " op=" + operation + " fast=" + fast + " retry=" + retry;
                context.getHealth().namedLog().debug(detail);
                if (TestPoints.enabled()) {
                    attempt.event("frame.attempt", "attempt=" + number, "op=" + operation, "fast=" + fast,
                            "retry=" + retry);
                }
            }
        }
    }

    private void awaitLocalLocks(Attempt attempt, Map<String, Lock> paths, Map<String, HeldLock> kept)
            throws Failure {
        for (Map.Entry<String, Lock> item : paths.entrySet()) {
            String path = item.getKey();
            StreamLease lease = lent.containsKey(path) ? null : await(path);
            if (!attempt.previous.looksOnly() && !lent.containsKey(path) && !kept.containsKey(path)
                    && localLock(item.getValue())
                    && (lease == null || !lease.heldLock() || lease.state() == StreamLease.State.ENDED)) {
                enter(attempt, path);
            }
        }
    }

    /** Returns the removed path if lease reconciliation ends this operation before its steps. */
    private String prepareBorrowedPaths(Attempt attempt, Operation<?> op, Map<String, Lock> paths,
            Map<String, HeldLock> kept) throws Exception {
        attempt.handler = (FileSystemHandler) attempt.loan.getConnection();
        if (context.isClosed() || attempt.handler.getContext() != context) {
            throw local(Error.CONNECTION_ERROR, "The connection was retired");
        }
        attempt.io = StreamLease.io(attempt.handler);
        Set<String> idle = new LinkedHashSet<>();
        Set<String> absent = new LinkedHashSet<>();
        for (String path : paths.keySet()) {
            if (!lent.containsKey(path)) {
                StreamLease lease = find(path);
                if (lease == null) {
                    absent.add(path);
                } else if (!lend(attempt, lease, path, op.use(path), idle)) {
                    return path;
                }
            }
        }
        op.options(attempt.handler);
        attempt.clock = new ProgressClock(connection, operation, System::nanoTime);
        for (Map.Entry<String, Lock> item : paths.entrySet()) {
            String path = item.getKey();
            if (item.getValue() == Lock.READ_INPUT || kept.containsKey(path)
                    || attempt.previous.looksOnly() && !lent.containsKey(path)) {
                continue;
            }
            StreamLease.Lending lending = lent.get(path);
            boolean whole = item.getValue() == Lock.WHOLE || item.getValue() == Lock.LOCAL && idle.contains(path);
            if (whole && (lending == null || lending.lock() == null)) {
                op.beforeWholeLock(attempt, path);
            }
            lock(attempt, path, item.getValue(), idle.contains(path));
        }
        for (String path : absent) {
            StreamLease latest = find(path);
            if (latest != null && latest.state() == StreamLease.State.ENDED
                    && !terminal(latest, path, op.use(path), idle)) {
                return path;
            }
        }
        return null;
    }

    private Classification classifyAttempt(Attempt attempt, Operation<?> op, Exception error, Failure declared) {
        Classification failure = declared == null ? new ErrorClassifier(context.getHealth().namedLog()).classify(error,
                attempt.clock == null ? 0 : attempt.clock.elapsedMillis(),
                op.retry() == Retry.READ ? ErrorClassifier.CallType.READ : ErrorClassifier.CallType.CHANGE)
                : declared.classified;
        if (attempt.streamDelivery != null && attempt.streamDelivery.streamFailure() != null) {
            return attempt.streamDelivery.streamFailure();
        }
        return failure;
    }

    private Failure applicationFailure(Attempt attempt, Exception error, Failure declared, Classification failure) {
        MoveFailedException move = cause(error, MoveFailedException.class);
        if (move != null && move.isConnectionLost()) {
            attempt.invalid = true;
        }
        if (failure != null && failure.getPhase() != null) {
            record(attempt, failure);
        }
        return (declared != null ? declared : application(error, failure, attempt.previous.looksOnly()
                && java.util.Objects.equals(attempt.previous.member, attempt.note.member)))
                .withFacts(attempt.note);
    }

    private void reportOutcome(Attempt attempt, ConnectionLease admission, Classification failure, boolean success) {
        if (attempt.streamDelivery != null) {
            attempt.streamDelivery.reportOperation(admission, failure, success);
        } else if (failure != null && failure.getKind().isTransport()) {
            admission.reportFailure();
            context.getHealth().failure(failure);
        } else {
            admission.reportSuccess();
            if (success) {
                context.getHealth().success();
            }
        }
    }

    private void recordFailedReads(Attempt attempt, Operation<?> op, Map<String, Lock> paths,
            Classification failure) {
        StreamLease.Reason reason = failure != null && failure.getKind().isTransport()
                ? StreamLease.Reason.TRANSPORT_ERROR : StreamLease.Reason.APPLICATION_ERROR;
        if (attempt.readSource != null && attempt.ownsShare(attempt.readSource)) {
            StreamLeases.forRead(message).failed(context, attempt.readSource, reason);
        }
        for (Map.Entry<String, Lock> item : paths.entrySet()) {
            String path = item.getKey();
            if (item.getValue() != Lock.READ_INPUT && op.use(path) == Use.READ
                    && attempt.ownsShare(path)) {
                StreamLeases.forRead(message).failed(context, path, reason);
            }
        }
    }

    boolean guardRead(Attempt attempt, String path, String lockPath, boolean own, boolean child) throws Failure {
        if (!child) {
            lock(attempt, path, lockPath, own ? Lock.OWN : Lock.GUARD, false);
            return true;
        }
        try {
            if (!lent.containsKey(path)) {
                StreamLease lease = await(path);
                if (lease != null) {
                    lend(attempt, lease, path, Use.READ, Collections.emptySet());
                }
            }
            StreamLease.Lending token = lent.get(path);
            if (own || token != null && token.lock() != null) {
                lock(attempt, path, lockPath, own ? Lock.OWN : Lock.GUARD, false);
            } else {
                attempt.clock.reset("guard");
                LockOutcome result = context.getShareLock().isLockedByOther(attempt.io, lockPath);
                if (result.kind == LockOutcome.Kind.ERROR && !result.connectionLost()) {
                    context.getHealth().namedLog().warn("Could not check the lock on " + UriMask.mask(path));
                    return false;
                }
                checked(attempt, result);
            }
            return true;
        } catch (Failure busy) {
            if (busy.error == Error.FILE_LOCKING_ERROR && !attempt.invalid) {
                return false;
            }
            throw busy;
        }
    }

    Member guardMember(Attempt attempt, String path) throws Failure {
        Set<String> idle = new HashSet<>();
        StreamLease lease = null;
        if (!lent.containsKey(path)) {
            for (int check = 0; ; check++) {
                try {
                    lease = await(path);
                    if (lease == null) {
                        break;
                    }
                    if (!lend(attempt, lease, path, Use.DELETE, idle)) {
                        return Member.REMOVED;
                    }
                    break;
                } catch (Failure failure) {
                    if (failure.error == Error.OPERATION_ERROR && lease != null
                            && lease.reason() == StreamLease.Reason.APPLICATION_ERROR) {
                        context.getHealth().namedLog().warn(failure.getMessage());
                        return Member.APPLICATION_ERROR;
                    }
                    if (failure.error != Error.FILE_LOCKING_ERROR) {
                        throw failure;
                    }
                    if (lease != null && lease.reason() == StreamLease.Reason.TAKEN_OVER) {
                        context.getHealth().namedLog().warn("Another owner holds " + path);
                        return Member.LOCKED;
                    }
                    if (check == 2 || remaining() == 0) {
                        return Member.LOCKED;
                    }
                }
            }
        }
        StreamLease.Lending token = lent.get(path);
        if (token != null && token.lock() != null || idle.contains(path)) {
            try {
                lock(attempt, path, Lock.GUARD, idle.contains(path));
            } catch (Failure failure) {
                if (failure.error != Error.FILE_LOCKING_ERROR || attempt.invalid) {
                    throw failure;
                }
                context.getHealth().namedLog().warn("Another owner holds " + path);
                return Member.LOCKED;
            }
        } else {
            attempt.clock.reset("guard");
            LockOutcome guard = context.getShareLock().isLockedByOther(attempt.io, path);
            if (guard.kind == LockOutcome.Kind.LOCKED && !guard.connectionLost()) {
                return Member.LOCKED;
            }
            if (guard.kind == LockOutcome.Kind.ERROR && !guard.connectionLost()
                    && (guard.classification == null || !guard.classification.getKind().isTransport())) {
                context.getHealth().namedLog().warn("Could not check the lock on " + UriMask.mask(path));
                return Member.APPLICATION_ERROR;
            }
            checked(attempt, guard);
        }
        if (lease == null && !lent.containsKey(path)) {
            StreamLease latest = find(path);
            if (latest != null && latest.state() == StreamLease.State.ENDED) {
                try {
                    if (!terminal(latest, path, Use.DELETE, idle)) {
                        return Member.REMOVED;
                    }
                } catch (Failure failure) {
                    if (latest.reason() == StreamLease.Reason.APPLICATION_ERROR
                            || latest.reason() == StreamLease.Reason.TAKEN_OVER) {
                        context.getHealth().namedLog().warn(failure.getMessage());
                        return latest.reason() == StreamLease.Reason.APPLICATION_ERROR
                                ? Member.APPLICATION_ERROR : Member.LOCKED;
                    }
                    throw failure;
                }
            }
        }
        return Member.READY;
    }

    private boolean localLock(Lock lock) {
        return lock == Lock.LOCAL || lock == Lock.WHOLE;
    }

    private StreamLease find(String path) {
        StreamLeases holder = StreamLeases.find(message);
        return holder == null ? null : holder.get(connection, path);
    }

    private long remaining() {
        return Math.max(0, 10000 - waited);
    }

    private LockOutcome waiting(Supplier<LockOutcome> call) {
        long begin = context.now();
        try {
            return call.get();
        } finally {
            waited += context.now() - begin;
        }
    }

    private long gap() {
        return Math.min(5000, remaining() / 2);
    }

    private void pause() throws Failure {
        long begin = context.now();
        long wait = Math.min(5000, remaining());
        long end = context.now() + wait;
        try {
            while (wait > 0) {
                TimeUnit.MILLISECONDS.sleep(TestPoints.slice("lockwait", wait));
                wait = end - context.now();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw local(Error.FILE_LOCKING_ERROR, "Interrupted while waiting for a file lock");
        } finally {
            waited += context.now() - begin;
        }
    }

    private StreamLease await(String path) throws Failure {
        for (int check = 1; ; check++) {
            StreamLease lease = find(path);
            if (lease == null) {
                return null;
            }
            StreamLease.State state = lease.state();
            if (state != StreamLease.State.ACTIVE && state != StreamLease.State.LENT) {
                return lease;
            }
            if (check == 3 || remaining() == 0) {
                throw local(Error.FILE_LOCKING_ERROR, "The stream lease is in use");
            }
            pause();
        }
    }

    private boolean lend(Attempt attempt, StreamLease lease, String path, Use use, Set<String> idle) throws Failure {
        StreamLease.State before = lease.state();
        StreamLease.Lending token = lease.lend(attempt.number);
        if (token != null) {
            lent.put(path, token);
            return true;
        }
        StreamLease current = find(path);
        if (current != lease || lease.state() != StreamLease.State.ENDED) {
            throw local(Error.FILE_LOCKING_ERROR, "Another operation won the stream lease");
        }
        if (before == StreamLease.State.REPLAY && lease.reason() == StreamLease.Reason.RETIRED) {
            throw local(Error.CONNECTION_ERROR, "The connection was retired during the lend");
        }
        return terminal(lease, path, use, idle);
    }

    private boolean terminal(StreamLease lease, String path, Use use, Set<String> idle) throws Failure {
        if (use == Use.READ || !lease.heldLock()) {
            return true;
        }
        switch (lease.reason()) {
            case RETIRED:
            case TRANSPORT_ERROR:
                throw local(Error.CONNECTION_ERROR, "The file lease ended with " + lease.reason());
            case APPLICATION_ERROR:
                throw local(Error.OPERATION_ERROR, "The read of " + path + " ended with an application error");
            case TAKEN_OVER:
                throw local(Error.FILE_LOCKING_ERROR, "Another owner holds the file");
            case REMOVED:
                if (use == Use.DELETE) {
                    return false;
                }
                throw local(use == Use.WRITE ? Error.OPERATION_ERROR : Error.ILLEGAL_PATH,
                        "This message removed " + path);
            case IDLE_LIMIT:
                idle.add(path);
                if (warned.add(path)) {
                    context.getHealth().namedLog().warn("The stream lease of " + path + " reached streamIdleTimeout");
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void enter(Attempt attempt, String path) throws Failure {
        LockOutcome outcome = waiting(() -> context.getShareLock().enterLocalOnly(path, 3, gap()));
        checked(attempt, outcome);
        attempt.own(path, outcome.handle, false);
    }

    void lockTarget(Attempt attempt, String path, Lock mode) throws Failure {
        Set<String> idle = new HashSet<>();
        StreamLease lease = lent.containsKey(path) ? null : await(path);
        if (lease != null) {
            lend(attempt, lease, path, Use.WRITE, idle);
        }
        if (!attempt.hasShare(path)) {
            lock(attempt, path, mode, idle.contains(path));
        }
        if (lease == null && !lent.containsKey(path)) {
            StreamLease latest = find(path);
            if (latest != null && latest.state() == StreamLease.State.ENDED) {
                terminal(latest, path, Use.WRITE, idle);
            }
        }
    }

    void lock(Attempt attempt, String path, Lock mode, boolean idle) throws Failure {
        lock(attempt, path, path, mode, idle);
    }

    private void lock(Attempt attempt, String path, String lockPath, Lock mode, boolean idle) throws Failure {
        if (TestPoints.enabled()) {
            StreamLease priorRead = find(path);
            if (priorRead != null) {
                priorRead.observe("read.before-next-lock", "attempt=" + attempt.number);
            }
        }
        attempt.hold("frame.lock-step");
        attempt.clock.reset("lock");
        StreamLease.Lending token = lent.get(path);
        if (token != null && token.lock() != null) {
            if (attempt.verified.contains(path)) {
                return;
            }
            LockOutcome result = token.lock().verifyOwner(attempt.io);
            token.afterVerifyOwner(attempt.number);
            if (result.kind == LockOutcome.Kind.TAKEN_OVER) {
                token.takenOver();
                lent.remove(path);
                throw new Failure(attempt.previous.looksOnly() ? Error.OUTCOME_UNCLEAR : Error.FILE_LOCKING_ERROR,
                        "Another owner holds the file");
            }
            checked(attempt, result);
            attempt.verified.add(path);
            return;
        }
        if (localLock(mode) && attempt.lock(path) == null) {
            enter(attempt, path);
        }
        if (mode == Lock.WHOLE || idle && mode == Lock.LOCAL) {
            HeldLock handle = attempt.lock(path);
            acquired(attempt, waiting(() -> handle.lockShare(attempt.io, 3, 5000, remaining())));
            attempt.own(path, handle, true);
        } else if (mode == Lock.OWN || idle) {
            LockOutcome result = waiting(() -> context.getShareLock().acquireWithWait(attempt.io, lockPath,
                    ShareLock.Mode.WRITER, 3, gap()));
            acquired(attempt, result);
            attempt.own(path, result.handle, true);
        } else if (mode == Lock.GUARD) {
            for (int check = 1; ; check++) {
                LockOutcome result = context.getShareLock().isLockedByOther(attempt.io, lockPath);
                if (result.kind != LockOutcome.Kind.LOCKED || check == 3 || remaining() == 0) {
                    checked(attempt, result);
                    return;
                }
                pause();
            }
        }
    }

    void acquireCommitLock(Attempt attempt, String path) throws Failure {
        acquired(attempt, waiting(() -> attempt.lock(path).lockShare(attempt.io, 3, 5000, remaining())));
    }

    private void acquired(Attempt attempt, LockOutcome result) throws Failure {
        if (result.kind == LockOutcome.Kind.TRANSPORT_ERROR || result.classification != null
                && result.classification.getKind().isTransport()) {
            attempt.acquisitionFailed = true;
        }
        checked(attempt, result);
    }

    private void checked(Attempt attempt, LockOutcome result) throws Failure {
        attempt.inspect(result);
        switch (result.kind) {
            case ACQUIRED:
            case OURS:
            case FREE:
                return;
            case LOCK_BUSY:
            case BUSY_LOCAL:
            case BUSY_SHARE:
            case LOCKED:
                throw new Failure(Error.FILE_LOCKING_ERROR, "The file is locked");
            default:
                throw new Failure(Error.OPERATION_ERROR, result.classification == null ? result.kind.toString()
                        : result.classification.getStatusText(), false, result.classification);
        }
    }

    private void releaseTry(Map<String, HeldLock> locks) {
        Attempt release = new Attempt(this, 0, connection, operation, new RetryNote(), Collections.emptyMap(),
                Collections.emptySet());
        release.invalid = true;
        try {
            release.hold("release-try.start");
            release.hold("release-try.borrow");
            release.loan = pool.borrowConnection(Const.CONNECTOR_NAME, connection);
            release.handler = (FileSystemHandler) release.loan.getConnection();
            if (release.loan.isTrial() || context.isClosed() || release.handler.getContext() != context) {
                release.loan.cancelTrial();
                release.handler = null;
            }
            if (release.handler != null) {
                release.io = StreamLease.io(release.handler);
            }
            release.invalid = false;
        } catch (ConnectException ignored) {
            // The abandoned marker remains for stale lock recovery.
        } finally {
            try {
                List<HeldLock> reverse = new ArrayList<>(locks.values());
                Collections.reverse(reverse);
                for (HeldLock handle : reverse) {
                    if (release.io == null || release.invalid) {
                        handle.abandon();
                    } else {
                        release.inspect(handle.releaseChecked(release.io));
                    }
                }
            } finally {
                release.endBorrow();
            }
        }
    }

    private void record(Attempt attempt, Classification failure) {
        RetryNote note = attempt.note;
        if (failure.getPhase() != null) {
            note.phase = failure.getPhase();
            note.committed = failure.isCommitConfirmed();
            if (failure.getTempPath() != null) {
                note.tempPath = failure.getTempPath();
            }
            if (failure.getKind() == ErrorKind.UNCLEAR && note.request == null) {
                switch (note.phase) {
                    case RENAME: note.request = Attempt.Request.RENAME; break;
                    case DELETE_SOURCE: note.request = Attempt.Request.DELETE_SOURCE; break;
                    case AFTER_DELETE: note.request = Attempt.Request.DELETE_TARGET; break;
                    default: break;
                }
                if (note.request != null && note.path == null) {
                    note.path = note.request == Attempt.Request.DELETE_TARGET ? attempt.moveTarget : attempt.moveSource;
                }
            }
        }
    }

    private boolean safe(Retry retry, Attempt attempt) {
        switch (retry) {
            case BODY: return !attempt.payloadStarted;
            case APPEND: return !attempt.bytesSent && !attempt.payloadStarted;
            case INSERT:
            case CREATE_NEW: return !attempt.commitCalled && !attempt.payloadStarted;
            case MOVE_NO_REPLACE: return attempt.note.phase != MovePhase.COMMIT
                    && attempt.note.phase != MovePhase.AFTER_DELETE;
            default: return true;
        }
    }

    private Failure transport(Retry retry, RetryNote note, Classification failure) {
        boolean unclear = note.looksOnly() || retry == Retry.CREATE_NEW && failure.getKind() == ErrorKind.UNCLEAR
                && note.phase != null || (retry == Retry.MOVE || retry == Retry.MOVE_NO_REPLACE)
                && (note.phase == MovePhase.COMMIT || note.phase == MovePhase.AFTER_DELETE);
        return new Failure(unclear ? Error.OUTCOME_UNCLEAR : Error.CONNECTION_ERROR,
                failure.getStatusText()).withFacts(note);
    }

    private static <T> T cause(Throwable failure, Class<T> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = link.getCause()) {
            if (type.isInstance(link)) {
                return type.cast(link);
            }
        }
        return null;
    }

    private static boolean denied(Throwable error) {
        SftpException sftp = cause(error, SftpException.class);
        SMBApiException smb = cause(error, SMBApiException.class);
        return cause(error, AccessDeniedException.class) != null
                || sftp != null && sftp.id == ChannelSftp.SSH_FX_PERMISSION_DENIED
                || smb != null && smb.getStatus() == NtStatus.STATUS_ACCESS_DENIED;
    }

    private Failure application(Exception error, Classification failure, boolean uncertainLook) {
        MoveFailedException move = cause(error, MoveFailedException.class);
        Error code;
        if (uncertainLook) {
            code = Error.OUTCOME_UNCLEAR;
        } else if (move != null && move.getPhase() == MovePhase.DELETE_SOURCE && move.isCommitConfirmed()) {
            return new Failure(Error.OPERATION_ERROR, "The target is complete and the source was not deleted. "
                    + failure.getStatusText());
        } else if (failure.getKind() == ErrorKind.MISSING) {
            code = Error.ILLEGAL_PATH;
        } else if (cause(error, FileAlreadyExistsException.class) != null
                || move != null && move.isTargetExists()) {
            code = Error.FILE_ALREADY_EXISTS;
        } else if (denied(error)) {
            code = Error.ACCESS_DENIED;
        } else {
            code = Error.OPERATION_ERROR;
        }
        return new Failure(code, failure.getStatusText());
    }
}
