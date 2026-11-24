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

import org.apache.synapse.MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.log.UriMask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Attempt {
    public enum Request { DELETE, RENAME, DELETE_SOURCE, DELETE_TARGET }

    public final int number;
    public final OperationFrame.RetryNote previous;
    private final OperationFrame frame;
    private final String connection;
    private final String operation;
    private final Map<String, StreamLease.Lending> lent;
    private final Map<String, HeldLock> owned = new LinkedHashMap<>();
    private final Set<String> shareLocks = new LinkedHashSet<>();
    final Set<String> verified = new LinkedHashSet<>();
    final Set<String> removed;
    OperationFrame.RetryNote note;
    ConnectionLease loan;
    FileSystemHandler handler;
    LockIo io;
    ProgressClock clock;
    boolean invalid;
    boolean acquisitionFailed;
    boolean payloadStarted;
    boolean bytesSent;
    boolean commitCalled;
    boolean streamHandedOver;
    StreamLease streamDelivery;
    StreamLease builtDelivery;
    String readInput;
    String readSource;
    String moveSource;
    String moveTarget;

    public void moving(String source, String target) {
        moveSource = source;
        moveTarget = target;
    }

    Attempt(OperationFrame frame, int number, String connection, String operation, OperationFrame.RetryNote previous,
            Map<String, StreamLease.Lending> lent, Set<String> removed) {
        this.frame = frame;
        this.number = number;
        this.connection = connection;
        this.operation = operation;
        this.previous = previous;
        this.lent = lent;
        this.removed = removed;
        note = previous.looksOnly() ? new OperationFrame.RetryNote(previous) : new OperationFrame.RetryNote();
    }

    public FileSystemHandler handler() {
        return handler;
    }

    public ProgressClock progress() {
        return clock;
    }

    public boolean handlerDiscarded() {
        return invalid;
    }

    public void discardHandler() {
        invalid = true;
    }

    public void lockTarget(String path, OperationFrame.Lock mode) throws OperationFrame.Failure {
        frame.lockTarget(this, path, mode);
    }

    public void lockPath(String path, OperationFrame.Lock mode) throws OperationFrame.Failure {
        if (!hasShare(path)) {
            frame.lock(this, path, mode, false);
        }
    }

    public void releasePath(String path) {
        HeldLock handle = owned.remove(path);
        shareLocks.remove(path);
        if (handle != null) {
            if (invalid) {
                handle.abandon();
            } else {
                inspect(handle.releaseChecked(io));
            }
        }
    }

    public void acquireCommitLock(String path) throws OperationFrame.Failure {
        frame.acquireCommitLock(this, path);
    }

    public boolean hasShare(String path) {
        return verified.contains(path) || ownsShare(path);
    }

    public void payloadStarted() {
        payloadStarted = true;
    }

    public void bytesSent() {
        bytesSent = true;
    }

    public void commitCalled() {
        commitCalled = true;
    }

    public void tempPath(String path) {
        note.tempPath = path;
    }

    public void source(long size, long modified) {
        note.sourceSize = size;
        note.sourceModified = modified;
    }

    public String readInput() {
        return readInput;
    }

    public boolean guardRead(String path, String lockPath, boolean own, boolean child) throws OperationFrame.Failure {
        if (!frame.guardRead(this, path, lockPath, own, child)) {
            return false;
        }
        readSource = path;
        return true;
    }

    public OperationFrame.Member guardMember(String path) throws OperationFrame.Failure {
        member(path);
        return frame.guardMember(this, path);
    }

    public void memberDone() {
        note = new OperationFrame.RetryNote();
        commitCalled = false;
    }

    public void member(String path) {
        if (note.member != null && !note.member.equals(path)) {
            note = new OperationFrame.RetryNote();
        }
        note.member = path;
    }

    public void destructive(Request request, String path) {
        if (previous.looksOnly() && path.equals(previous.path())) {
            throw new IllegalStateException("A retry cannot change the source after an unclear request");
        }
        note.request = request;
        note.path = path;
        if (TestPoints.enabled()) {
            event("frame.destructive", "request=" + request, "path=" + UriMask.mask(path));
        }
    }

    public boolean hasLentLease(String path) {
        return lent.containsKey(path);
    }

    public void removed(String path) {
        removed.add(path);
        if (path.equals(note.path) || path.equals(note.member)) {
            note = new OperationFrame.RetryNote();
        }
    }

    public HeldLock lock(String path) {
        StreamLease.Lending lending = lent.get(path);
        return lending != null && lending.lock() != null ? lending.lock() : owned.get(path);
    }

    public StreamLease publishRead(MessageContext message, String path, FileObject streamFile, String type,
                                   long size, long modified) {
        HeldLock lock = lock(path);
        StreamLeases holder = StreamLeases.forRead(message);
        StreamLease lease = streamFile == null
                ? StreamLease.replay(handler.getContext(), path, size, modified, lock)
                : StreamLease.active(handler.getContext(), path, type, size, modified, loan, streamFile, lock);
        handler.getContext().publish(holder, lease, lent.remove(path));
        owned.remove(path);
        if (streamFile != null) {
            streamHandedOver = true;
            streamDelivery = lease;
            loan = null;
        } else if (TestPoints.enabled()) {
            builtDelivery = lease;
        }
        return lease;
    }

    void own(String path, HeldLock handle, boolean share) {
        owned.put(path, handle);
        if (share) {
            shareLocks.add(path);
        }
    }

    boolean ownsShare(String path) {
        return shareLocks.contains(path) && owned.containsKey(path);
    }

    void inspect(LockOutcome outcome) {
        if (outcome != null && (outcome.connectionLost() || outcome.classification != null
                && outcome.classification.getKind().isTransport())) {
            invalid = true;
        }
    }

    Map<String, HeldLock> finishLocks(boolean keep) {
        Map<String, HeldLock> kept = new LinkedHashMap<>();
        if (keep && invalid) {
            for (Map.Entry<String, HeldLock> entry : owned.entrySet()) {
                if (shareLocks.contains(entry.getKey())) {
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
        }
        List<String> paths = new ArrayList<>(owned.keySet());
        for (int index = paths.size() - 1; index >= 0; index--) {
            String path = paths.get(index);
            HeldLock handle = owned.remove(path);
            if (kept.containsKey(path)) {
                continue;
            }
            if (invalid) {
                handle.abandon();
            } else {
                inspect(handle.releaseChecked(io));
            }
        }
        return kept;
    }

    void endLending() {
        for (Map.Entry<String, StreamLease.Lending> entry : lent.entrySet()) {
            FileSystemHandler usable = invalid ? null : handler;
            inspect(removed.contains(entry.getKey()) ? entry.getValue().removed(usable)
                    : entry.getValue().giveBack(usable, number));
        }
        lent.clear();
    }

    void endBorrow() {
        ConnectionLease ending = loan;
        loan = null;
        if (ending != null) {
            if (invalid) {
                ending.invalidate();
            } else {
                ending.returnToPool();
            }
            if (TestPoints.enabled()) {
                event("frame.lease-end", "attempt=" + number, "fate=" + (invalid ? "invalidated" : "returned"));
            }
        }
        handler = null;
        io = null;
    }

    void hold(String point) {
        if (TestPoints.enabled()) {
            TestPoints.hold(point, connection, "attempt=" + number, "op=" + operation);
        }
    }

    void event(String point, String... details) {
        TestPoints.event(point, connection, details);
    }
}
