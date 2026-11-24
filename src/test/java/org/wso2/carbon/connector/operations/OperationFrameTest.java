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
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2PacketHeader;
import com.hierynomus.mssmb2.SMBApiException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.vfs.shared.lock.HeldLock;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class OperationFrameTest {
    private abstract static class Write implements OperationFrame.Operation<Integer> {
        final String path;

        Write(Path path) {
            this(path.toUri().toString());
        }

        Write(String path) {
            this.path = path;
        }

        public LinkedHashMap<String, OperationFrame.Lock> paths() {
            LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
            paths.put(path, OperationFrame.Lock.LOCAL);
            return paths;
        }

        public OperationFrame.Use use(String path) { return OperationFrame.Use.WRITE; }
        public OperationFrame.Retry retry() { return OperationFrame.Retry.SAFE; }
        public Integer removed(String path) { throw new AssertionError(path); }
    }

    private static FileSystemException disconnected() {
        FileSystemException failure = new FileSystemException("Connection lost");
        failure.setFault(Fault.TRANSPORT);
        return failure;
    }

    @Test
    public void eachFastFailureGetsItsOwnHandler() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            Path path = Files.createTempFile("frame", ".txt");
            try {
                Set<FileSystemHandler> handlers = new HashSet<>();
                Write op = new Write(path) {
                    public Integer steps(Attempt attempt) throws Exception {
                        Assert.assertTrue(handlers.add(attempt.handler()));
                        if (attempt.number < 3) {
                            throw disconnected();
                        }
                        return attempt.number;
                    }
                };
                Assert.assertEquals(OperationFrame.run(files.message, files.name, 2, op).intValue(), 3);
                Assert.assertEquals(handlers.size(), 3);
            } finally {
                Files.delete(path);
            }
        }
    }

    @Test
    public void failureCarriesTheExceptionOfTheAttempt() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "suspendOnConnectionFailure", "false")) {
            Path path = Files.createTempFile("frame", ".txt");
            try {
                for (Exception thrown : new Exception[]{new AccessDeniedException("sftp://user:secret@host/in/a.txt"),
                        disconnected()}) {
                    Write op = new Write(path) {
                        public Integer steps(Attempt attempt) throws Exception {
                            throw thrown;
                        }
                    };
                    try {
                        OperationFrame.run(files.message, files.name, 0, op);
                        Assert.fail("The steps failed");
                    } catch (OperationFrame.Failure failure) {
                        Throwable cause = failure.getCause();
                        Assert.assertNotNull(cause);
                        Assert.assertTrue(cause.getMessage().startsWith(thrown.getClass().getName()),
                                cause.getMessage());
                        Assert.assertFalse(cause.getMessage().contains("secret"), cause.getMessage());
                        Assert.assertEquals(cause.getStackTrace(), thrown.getStackTrace());
                    }
                }
            } finally {
                Files.delete(path);
            }
        }
    }

    @Test
    public void openBeforeDeadlineDoesNotEnterOrBorrow() throws Exception {
        AtomicLong time = new AtomicLong();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", time::get, "suspendInitialDuration", "1000")) {
            ConnectionLease seed = files.borrow();
            FilePoolFixture.pool(seed).getConfiguration().setPoolConnectionAgedTimeout(60000);
            seed.returnToPool();
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            ConnectionLease lease = files.borrow();
            lease.reportFailure();
            lease.returnToPool();
            Path path = Files.createTempFile("frame", ".txt");
            try {
                AtomicInteger calls = new AtomicInteger();
                Write op = new Write(path) {
                    public Integer steps(Attempt attempt) { return calls.incrementAndGet(); }
                };
                HeldLock other = files.factory.getContext().getShareLock()
                        .enterLocalOnly(path.toUri().toString(), 1, 0).handle;
                Assert.assertNotNull(other);
                pool.setStrat(Instant.now().minusMillis(pool.getPoolConnectionAgedTimeout() + 1));
                time.set(999);
                for (int check = 0; check < 2; check++) {
                    try {
                        OperationFrame.run(files.message, files.name, 0, op);
                        Assert.fail("The open connection admitted a caller");
                    } catch (OperationFrame.Failure failure) {
                        Assert.assertEquals(failure.error.getErrorCode(), "700109");
                        Assert.assertEquals(failure.error.getErrorDetail(), "FILE:CONNECTION_SUSPENDED");
                    }
                }
                Assert.assertEquals(calls.get(), 0);
                Assert.assertEquals(pool.getNumActive(), 0);
                Assert.assertEquals(pool.attempts.size(), 1);
                Assert.assertFalse(pool.isClosed());
                other.abandon();
                time.set(1000);
                Assert.assertEquals(OperationFrame.run(files.message, files.name, 0, op).intValue(), 1);
                Assert.assertTrue(pool.isClosed());
                ConnectionLease next = files.borrow();
                try {
                    Assert.assertNotSame(FilePoolFixture.pool(next), pool);
                    Assert.assertSame(FilePoolFixture.pool(next).getBreaker(), pool.getBreaker());
                    Assert.assertEquals(pool.getBreaker().getMode().name(), "CLOSED");
                } finally {
                    next.returnToPool();
                }
            } finally {
                Files.delete(path);
            }
        }
    }

    @Test
    public void laterBorrowFailurePreservesTheUnclearRequest() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            Path path = Files.createTempFile("frame", ".txt");
            try {
                AtomicInteger calls = new AtomicInteger();
                Write op = new Write(path) {
                    public Integer steps(Attempt attempt) throws Exception {
                        calls.incrementAndGet();
                        attempt.destructive(Attempt.Request.DELETE, this.path);
                        files.close();
                        throw disconnected();
                    }
                };
                try {
                    OperationFrame.run(files.message, files.name, 2, op);
                    Assert.fail("A failed retry concealed the missing reply");
                } catch (OperationFrame.Failure failure) {
                    Assert.assertEquals(failure.error, Error.OUTCOME_UNCLEAR);
                    Assert.assertEquals(failure.error.getErrorCode(), "700111");
                    Assert.assertEquals(failure.error.getErrorDetail(), "FILE:OUTCOME_UNCLEAR");
                }
                Assert.assertEquals(calls.get(), 1);
                Assert.assertTrue(Files.exists(path));
            } finally {
                Files.delete(path);
            }
        }
    }

    @Test
    public void retryPreservesEveryMovePhaseAndTempName() throws Exception {
        FrameUnitEvidence.Checks verify = new FrameUnitEvidence.Checks();
        JsonArray phases = new JsonArray();
        Class<MoveFailedException> type =
                MoveFailedException.class;
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Path path = Files.createTempFile("frame-phase", ".txt");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            for (MovePhase phase :
                    MovePhase.values()) {
                for (boolean unclear : new boolean[]{true, false}) {
                    Write move = new Write(path) {
                        public OperationFrame.Retry retry() { return OperationFrame.Retry.MOVE; }
                        public Integer steps(Attempt attempt) throws Exception {
                            String target = this.path + ".target";
                            String temp = target + ".tmp." + phase;
                            Attempt.Request request;
                            switch (phase) {
                                case RENAME: request = Attempt.Request.RENAME; break;
                                case DELETE_SOURCE: request = Attempt.Request.DELETE_SOURCE; break;
                                case AFTER_DELETE: request = Attempt.Request.DELETE_TARGET; break;
                                default: request = null;
                            }
                            String changedPath = request == Attempt.Request.DELETE_TARGET ? target : this.path;
                            if (attempt.number == 1) {
                                attempt.moving(this.path, target);
                                attempt.source(17, 19);
                                attempt.member(this.path);
                                if (unclear) {
                                    throw new UnclearResultException(
                                            "The reply is missing", null, phase, temp,
                                            phase == MovePhase.AFTER_DELETE);
                                }
                                if (request != null) {
                                    attempt.destructive(request, changedPath);
                                }
                                throw (Exception) constructor.newInstance(this.path, target, null, Fault.TRANSPORT,
                                        phase, temp, phase == MovePhase.AFTER_DELETE,
                                        false, false);
                            }
                            OperationFrame.RetryNote note = attempt.previous;
                            JsonObject row = new JsonObject();
                            row.addProperty("type", unclear ? "UnclearResultException" : "MoveFailedException");
                            row.addProperty("givenPhase", phase.name());
                            row.addProperty("phase", note.phase() == null ? null : note.phase().name());
                            row.addProperty("givenTemp", temp);
                            row.addProperty("temp", note.tempPath());
                            row.addProperty("givenCommit", phase.isTargetComplete());
                            row.addProperty("commit", note.committed());
                            row.addProperty("request", note.request() == null ? "none" : note.request().name());
                            phases.add(row);
                            verify.assertEquals(note.phase(), phase);
                            verify.assertEquals(note.tempPath(), temp);
                            verify.assertEquals(note.committed(), phase.isTargetComplete());
                            verify.assertEquals(note.request(), request);
                            verify.assertEquals(note.path(), request == null ? null : changedPath);
                            verify.assertEquals(note.sourceSize(), Long.valueOf(17));
                            verify.assertEquals(note.sourceModified(), Long.valueOf(19));
                            verify.assertEquals(note.member(), this.path);
                            return attempt.number;
                        }
                    };
                    Assert.assertEquals(OperationFrame.run(files.message, files.name, 1, move).intValue(), 2);
                }
            }
            verify.assertAll();
        } finally {
            FrameUnitEvidence.write("E16.11", phases);
            Files.delete(path);
        }
    }

    @Test
    public void retryLeavesTheNewOwnersMarkerIntact() throws Exception {
        Path file = Files.createTempFile("frame-retry-lock", ".txt");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        byte[] foreign = ("18:other:address:" + System.currentTimeMillis()).getBytes(
                StandardCharsets.UTF_8);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicReference<byte[]> original = new AtomicReference<>();
            AtomicInteger deletes = new AtomicInteger();
            Write op = new Write("locktrace" + file.toUri().toString().substring(4)) {
                public LinkedHashMap<String, OperationFrame.Lock> paths() {
                    LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
                    paths.put(path, OperationFrame.Lock.WHOLE);
                    return paths;
                }
                public void options(FileSystemHandler handler) throws Exception {
                    DefaultFileSystemManager manager =
                            (DefaultFileSystemManager) handler.getFsManager();
                    manager.addProvider("locktrace", new DefaultLocalFileProvider() {
                        public FileObject findFile(FileObject base,
                                String uri, FileSystemOptions options)
                                throws FileSystemException {
                            FileObject real = manager.resolveFile("file" + uri.substring(9), options);
                            return new DecoratedFileObject(real) {
                                public boolean delete() throws FileSystemException {
                                    if (uri.endsWith(".lock")) {
                                        deletes.incrementAndGet();
                                    }
                                    return super.delete();
                                }
                            };
                        }
                    });
                    if (attempts.incrementAndGet() == 2) {
                        Assert.assertEquals(Files.readAllBytes(marker), original.get());
                        Files.write(marker, foreign);
                    }
                }
                public OperationFrame.Retry retry() { return OperationFrame.Retry.APPEND; }
                public Integer steps(Attempt attempt) throws Exception {
                    Assert.assertEquals(attempt.number, 1);
                    original.set(Files.readAllBytes(marker));
                    throw disconnected();
                }
            };
            Error outcome = null;
            try {
                OperationFrame.run(files.message, files.name, 1, op);
            } catch (OperationFrame.Failure failure) {
                outcome = failure.error;
            }
            byte[] actual = Files.exists(marker) ? Files.readAllBytes(marker) : new byte[0];
            JsonObject trace = new JsonObject();
            trace.addProperty("code", outcome == null ? "OK" : outcome.getErrorCode());
            trace.addProperty("attempts", attempts.get());
            trace.addProperty("deletes", deletes.get());
            trace.addProperty("expected", Base64.getEncoder().encodeToString(foreign));
            trace.addProperty("actual", Base64.getEncoder().encodeToString(actual));
            FrameUnitEvidence.write("D12.1", trace);
            Assert.assertEquals(outcome, Error.FILE_LOCKING_ERROR);
            Assert.assertEquals(attempts.get(), 2);
            Assert.assertEquals(actual, foreign);
            Assert.assertEquals(deletes.get(), 0);
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }

    @Test
    public void failureSetsTheFlowPropertiesAndWritesTheResponseBeforeThrowing() throws Exception {
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(
                axis, new SynapseConfiguration(), null);
        OperationFrame.Mediator mediator = new OperationFrame.Mediator() {
            public void execute(MessageContext context, String variable, Boolean body)
                    throws ConnectException {
                runFrame(context, "missing", -1, null, "read", variable, body);
            }
        };
        try {
            mediator.execute(message, "response", false);
            Assert.fail("A failed operation did not enter the fault path");
        } catch (SynapseException expected) {
            Assert.assertEquals(message.getProperty("ERROR_CODE"), "700107");
            Assert.assertEquals(message.getProperty("ERROR_MESSAGE"), "FILE:INVALID_CONFIGURATION");
            ConnectorResponse result =
                    (ConnectorResponse) message.getVariable("response");
            JsonObject response = (JsonObject) result.getPayload();
            Assert.assertFalse(response.get("success").getAsBoolean());
            Assert.assertEquals(response.getAsJsonObject("error").get("code").getAsString(), "700107");
        }
    }

    @Test
    public void retainedLockEndsOnTheFinalLookWithoutAnotherBorrow() throws Exception {
        for (boolean failed : new boolean[]{false, true}) {
            Path file = Files.createTempFile("frame-final-look", ".txt");
            Path marker = file.resolveSibling(file.getFileName() + ".lock");
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "suspendOnConnectionFailure", "false")) {
                FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
                Write move = new Write(file) {
                    public LinkedHashMap<String, OperationFrame.Lock> paths() {
                        LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
                        paths.put(path, OperationFrame.Lock.OWN);
                        return paths;
                    }
                    public OperationFrame.Retry retry() { return OperationFrame.Retry.MOVE; }
                    public Integer steps(Attempt attempt) throws Exception {
                        if (attempt.number == 1) {
                            attempt.destructive(Attempt.Request.RENAME, path);
                            throw disconnected();
                        }
                        Assert.assertTrue(attempt.previous.looksOnly());
                        Assert.assertTrue(Files.exists(marker));
                        if (failed) {
                            throw disconnected();
                        }
                        return attempt.number;
                    }
                };
                try {
                    Assert.assertEquals(OperationFrame.run(files.message, files.name, 1, move).intValue(), 2);
                    Assert.assertFalse(failed);
                } catch (OperationFrame.Failure failure) {
                    Assert.assertTrue(failed);
                    Assert.assertEquals(failure.error, Error.OUTCOME_UNCLEAR);
                }
                Assert.assertEquals(pool.attempts.size(), 2);
                Assert.assertEquals(pool.getNumActive(), 0);
                Assert.assertEquals(Files.exists(marker), failed);
            } finally {
                Files.deleteIfExists(marker);
                Files.delete(file);
            }
        }
    }

    private JsonObject frameError(FilePoolFixture files, Write op, int retries, Error expected) throws Exception {
        org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
        axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
        Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
        OperationFrame.Mediator mediator = new OperationFrame.Mediator() {
            public void execute(MessageContext context, String variable, Boolean body) throws ConnectException {
                runFrame(context, files.name, retries, op, "move", variable, body);
            }
        };
        try {
            mediator.execute(message, "response", false);
        } catch (SynapseException fault) {
            Assert.assertEquals(message.getProperty("ERROR_CODE"), expected.getErrorCode());
            Assert.assertEquals(message.getProperty("ERROR_MESSAGE"), expected.getErrorDetail());
            JsonObject payload = (JsonObject) ((ConnectorResponse) message.getVariable("response")).getPayload();
            Assert.assertFalse(payload.get("success").getAsBoolean());
            Assert.assertEquals(payload.getAsJsonObject("error").get("code").getAsString(), expected.getErrorCode());
            return payload;
        }
        throw new AssertionError("The move did not enter the fault sequence");
    }

    @Test
    public void unclearResponseCarriesOnlyConfirmedTargetAndCurrentMember() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "suspendOnConnectionFailure", "false")) {
            for (MovePhase phase : new MovePhase[]{MovePhase.RENAME, MovePhase.DELETE_SOURCE}) {
                for (boolean folder : new boolean[]{true, false}) {
                    Write op = new Write("file:///source") {
                        public Integer steps(Attempt attempt) throws Exception {
                            attempt.moving(path, path + ".target");
                            if (folder) {
                                attempt.member("f3.txt");
                            }
                            throw new UnclearResultException("lost", null, phase, path + ".tmp", false);
                        }
                    };
                    JsonObject payload = frameError(files, op, 0, Error.OUTCOME_UNCLEAR);
                    Assert.assertEquals(payload.has("targetComplete"), phase == MovePhase.DELETE_SOURCE);
                    if (payload.has("targetComplete")) {
                        Assert.assertTrue(payload.get("targetComplete").getAsBoolean());
                    }
                    Assert.assertEquals(payload.has("filesLeft"), folder);
                    if (folder) {
                        JsonArray left = payload.getAsJsonArray("filesLeft");
                        Assert.assertEquals(left.size(), 1);
                        Assert.assertEquals(left.get(0).getAsJsonObject().get("name").getAsString(), "f3.txt");
                        Assert.assertEquals(left.get(0).getAsJsonObject().get("reason").getAsString(), "UNCLEAR");
                    }
                }
            }
        }
    }

    @Test
    public void laterRefusalPreservesTheConfirmedTargetAndUnsettledMember() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "suspendOnConnectionFailure", "false")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Write op = new Write("file:///current") {
                public Integer steps(Attempt attempt) throws Exception {
                    Assert.assertEquals(attempt.number, 1);
                    attempt.moving(path, path + ".target");
                    attempt.member("unfinished.txt");
                    pool.denial = new org.wso2.integration.connector.core.connection.PoolBusyException(
                            new java.util.NoSuchElementException());
                    throw new UnclearResultException("lost", null, MovePhase.DELETE_SOURCE, path + ".tmp", false);
                }
            };
            JsonObject payload = frameError(files, op, 1, Error.OUTCOME_UNCLEAR);
            Assert.assertTrue(payload.get("targetComplete").getAsBoolean());
            JsonObject member = payload.getAsJsonArray("filesLeft").get(0).getAsJsonObject();
            Assert.assertEquals(member.get("name").getAsString(), "unfinished.txt");
            Assert.assertEquals(member.get("reason").getAsString(), "UNCLEAR");
            Assert.assertEquals(pool.attempts.size(), 2);
            Assert.assertEquals(pool.getNumActive(), 0);
        }
    }

    @Test
    public void completedMemberDoesNotMakeTheNextMemberUnclear() throws Exception {
        for (boolean secondSent : new boolean[]{false, true}) {
            Path first = Files.createTempFile("member-a", ".txt");
            Path second = Files.createTempFile("member-b", ".txt");
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "suspendOnConnectionFailure", "false")) {
                Write op = new Write(first) {
                    public Integer steps(Attempt attempt) throws Exception {
                        attempt.member(path);
                        attempt.destructive(Attempt.Request.DELETE, path);
                        Files.delete(first);
                        attempt.removed(path);
                        String current = second.toUri().toString();
                        Assert.assertEquals(attempt.guardMember(current), OperationFrame.Member.READY);
                        if (secondSent) {
                            attempt.destructive(Attempt.Request.DELETE, current);
                        }
                        throw disconnected();
                    }
                };
                JsonObject payload = frameError(files, op, 0, secondSent ? Error.OUTCOME_UNCLEAR : Error.CONNECTION_ERROR);
                Assert.assertEquals(payload.has("filesLeft"), secondSent);
                if (secondSent) {
                    Assert.assertEquals(payload.getAsJsonArray("filesLeft").get(0).getAsJsonObject()
                            .get("name").getAsString(), second.toUri().toString());
                }
                Assert.assertFalse(Files.exists(first));
                Assert.assertTrue(Files.exists(second));
            } finally {
                Files.deleteIfExists(first);
                Files.delete(second);
            }
        }
    }

    @Test
    public void definiteSourceDeleteRefusalReportsTheConfirmedTarget() throws Exception {
        Constructor<MoveFailedException> move = MoveFailedException.class.getDeclaredConstructor(
                String.class, String.class, Throwable.class, Fault.class, MovePhase.class,
                String.class, boolean.class, boolean.class, boolean.class);
        move.setAccessible(true);
        Path source = Files.createTempFile("frame-delete-source", ".txt");
        Path target = source.resolveSibling(source.getFileName() + ".target");
        Files.write(source, new byte[]{17});
        Files.copy(source, target);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            Write op = new Write(source) {
                public OperationFrame.Retry retry() { return OperationFrame.Retry.MOVE; }
                public Integer steps(Attempt attempt) throws Exception {
                    attempt.moving(path, target.toUri().toString());
                    throw move.newInstance(path, target.toUri().toString(),
                            new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "permission denied"),
                            Fault.APPLICATION, MovePhase.DELETE_SOURCE, null, false, false, false);
                }
            };
            JsonObject response = frameError(files, op, 2, Error.OPERATION_ERROR);
            Assert.assertTrue(response.get("targetComplete").getAsBoolean());
            Assert.assertTrue(response.getAsJsonObject("error").get("detail").getAsString()
                    .contains("source was not deleted"));
            Assert.assertFalse(response.has("filesLeft"));
            Assert.assertEquals(Files.readAllBytes(source), new byte[]{17});
            Assert.assertEquals(Files.readAllBytes(target), new byte[]{17});
            Assert.assertEquals(pool.attempts.size(), 1);
            Assert.assertEquals(pool.attempts.get(0).getAsJsonObject().get("returns").getAsInt(), 1);
            Assert.assertEquals(pool.attempts.get(0).getAsJsonObject().get("invalidates").getAsInt(), 0);
        } finally {
            Files.deleteIfExists(target);
            Files.deleteIfExists(source);
        }
    }

    @Test
    public void typedPermissionFailuresAreApplicationResults() throws Exception {
        SMB2PacketHeader header = new SMB2PacketHeader();
        header.setStatusCode(NtStatus.STATUS_ACCESS_DENIED.getValue());
        header.setMessageType(SMB2MessageCommandCode.SMB2_CREATE);
        Exception[] errors = {
            new AccessDeniedException("denied"),
            new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "denied"),
            new SMBApiException(header, "denied"),
            new SftpException(ChannelSftp.SSH_FX_FAILURE, "permission denied"),
            new IllegalArgumentException("permission denied")
        };
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionLease first = files.borrow();
            ConnectionPool pool = FilePoolFixture.pool(first);
            FileSystemHandler initial = (FileSystemHandler) first.getConnection();
            first.returnToPool();
            for (int index = 0; index < errors.length; index++) {
                Exception error = errors[index];
                Error expected = index < 3 ? Error.ACCESS_DENIED : Error.OPERATION_ERROR;
                AtomicInteger attempts = new AtomicInteger();
                Write op = new Write("file:///typed-denial") {
                    public Integer steps(Attempt attempt) throws Exception {
                        Assert.assertSame(attempt.handler(), initial);
                        attempts.incrementAndGet();
                        throw new FileSystemException("vfs.provider/get-type.error", error, path);
                    }
                };
                try {
                    OperationFrame.run(files.message, files.name, 2, op);
                    Assert.fail("The file system refused the call");
                } catch (OperationFrame.Failure failure) {
                    Assert.assertEquals(failure.error.getErrorCode(), expected.getErrorCode());
                    Assert.assertEquals(failure.error.getErrorDetail(), expected.getErrorDetail());
                }
                Assert.assertEquals(attempts.get(), 1);
                Assert.assertEquals(pool.getNumActive(), 0);
                Assert.assertEquals(pool.getBreaker().getMode().name(), "CLOSED");
            }
        }
    }
}
