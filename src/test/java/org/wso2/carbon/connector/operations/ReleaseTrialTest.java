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
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.vfs.shared.lock.HeldLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ReleaseTrialTest {
    private Object state(CircuitBreaker breaker) throws Exception {
        return ((AtomicReference<?>) FilePoolFixture.field(breaker, CircuitBreaker.class, "state")).get();
    }

    @Test
    public void releaseReturnsTheTrialUnused() throws Exception {
        AtomicReference<CircuitBreaker> breaker = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        Path file = Files.createTempFile("release-trial", ".bin");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        JsonObject row = new JsonObject();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", () -> {
            if (breaker.get() != null && breaker.get().getMode() == CircuitBreaker.Mode.OPEN) {
                expired.set(true);
            }
            return expired.get() ? 1001L : 0L;
        }, "suspendInitialDuration", "1000", "retriesBeforeSuspension", "0")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            breaker.set(pool.getBreaker());
            AtomicReference<HeldLock> held = new AtomicReference<>();
            AtomicInteger grants = new AtomicInteger();
            AtomicInteger grantReads = new AtomicInteger();
            AtomicReference<Object> releaseState = new AtomicReference<>();
            pool.afterBorrow = raw -> {
                int number = grants.incrementAndGet();
                if (number > 2) {
                    return;
                }
                FileSystemHandler handler = (FileSystemHandler) raw;
                DefaultFileSystemManager manager = (DefaultFileSystemManager) handler.getFsManager();
                try {
                    if (number == 2) {
                        releaseState.set(state(breaker.get()));
                    }
                    manager.addProvider("trialfile", new DefaultLocalFileProvider() {
                        public FileObject findFile(FileObject base, String uri, FileSystemOptions options)
                                throws FileSystemException {
                            if (number == 2) {
                                grantReads.incrementAndGet();
                            }
                            return manager.resolveFile("file:" + uri.substring("trialfile:".length()), options);
                        }
                    });
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            };
            String name = "trialfile:" + file.toUri().getRawPath();
            OperationFrameStreamTest.Steps<Void> write = new OperationFrameStreamTest.Steps<Void>() {
                public Void steps(Attempt attempt) throws Exception {
                    held.set(attempt.lock(name));
                    row.addProperty("heldLock", held.get() != null && Files.exists(marker));
                    // Another operation opens the breaker before this attempt fails, so the release try
                    // gets the trial.
                    breaker.get().admit().reportFailure();
                    FileSystemException lost = new FileSystemException("Connection lost");
                    lost.setFault(Fault.TRANSPORT);
                    throw lost;
                }
            };
            write.paths.put(name, OperationFrame.Lock.OWN);
            try {
                OperationFrame.run(OperationFrameStreamTest.message(), files.name, 0, write);
                Assert.fail("The write lost its connection");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.CONNECTION_ERROR);
            }
            Object before = releaseState.get();
            Assert.assertNotNull(before);
            Object after = state(breaker.get());
            long generation = (Long) FilePoolFixture.field(before, before.getClass(), "generation");
            long afterGeneration = (Long) FilePoolFixture.field(after, after.getClass(), "generation");
            long failures = (Long) FilePoolFixture.field(after, after.getClass(), "failures");
            boolean trialBefore = (Boolean) FilePoolFixture.field(before, before.getClass(), "trialInFlight");
            boolean trialAfter = (Boolean) FilePoolFixture.field(after, after.getClass(), "trialInFlight");
            boolean ended = ((AtomicBoolean) FilePoolFixture.field(held.get(), HeldLock.class, "ended")).get();
            row.addProperty("fastTransportFailure", grants.get() == 2);
            row.addProperty("maxRetries", 0);
            row.addProperty("operationReports", failures);
            row.addProperty("opened", expired.get());
            row.addProperty("deadlinePassedBeforeReleaseBorrow", expired.get() && trialBefore);
            row.addProperty("releaseTrial", trialBefore);
            row.addProperty("cancellations", trialBefore && !trialAfter ? 1 : 0);
            row.addProperty("releaseCheckedCalls", grantReads.get());
            row.addProperty("abandoned", ended && Files.exists(marker));
            row.addProperty("mode", breaker.get().getMode().name());
            row.addProperty("trialInFlight", trialAfter);
            row.addProperty("releaseReports", afterGeneration - generation);
            row.addProperty("releaseBorrows", grants.get() - 1);
            row.addProperty("releaseReturns", pool.returns());
            row.addProperty("handlerUsed", grantReads.get() != 0);
            Assert.assertEquals(breaker.get().getMode(), CircuitBreaker.Mode.HALF_OPEN);
            Assert.assertTrue(trialBefore && !trialAfter && ended && Files.exists(marker));
            Assert.assertEquals(pool.returns(), 1);
            Assert.assertEquals(grants.get(), 2);
            Assert.assertEquals(grantReads.get(), 0);
            Assert.assertEquals(afterGeneration, generation);
            Assert.assertEquals(failures, 1L);
            ConnectionLease next = files.borrow();
            try {
                row.addProperty("nextAdmitted", next.getConnection() != null);
                row.addProperty("nextTrial", next.isTrial());
                Assert.assertTrue(next.isTrial());
            } finally {
                next.cancelTrial();
                next.returnToPool();
            }
            String trace = System.getenv("BREAKER_OBSERVATIONS");
            if (trace != null) {
                JsonObject output = new JsonObject();
                output.add("releaseReturnsTheTrialUnused", row);
                Files.write(Paths.get(trace), output.toString().getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }

    @Test
    public void releaseTryRunsBeforeTheFailureOpensTheBreaker() throws Exception {
        Path file = Files.createTempFile("release-first", ".bin");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            String name = file.toUri().toString();
            AtomicBoolean locked = new AtomicBoolean();
            OperationFrameStreamTest.Steps<Void> write = new OperationFrameStreamTest.Steps<Void>() {
                public Void steps(Attempt attempt) throws Exception {
                    locked.set(attempt.lock(name) != null && Files.exists(marker));
                    FileSystemException lost = new FileSystemException("Connection lost");
                    lost.setFault(Fault.TRANSPORT);
                    throw lost;
                }
            };
            write.paths.put(name, OperationFrame.Lock.OWN);
            try {
                OperationFrame.run(OperationFrameStreamTest.message(), files.name, 0, write);
                Assert.fail("The write lost its connection");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.CONNECTION_ERROR);
            }
            Assert.assertTrue(locked.get());
            Assert.assertEquals(pool.getBreaker().getMode(), CircuitBreaker.Mode.OPEN);
            Assert.assertFalse(Files.exists(marker), "The release try left the lock of the failed attempt");
            Assert.assertEquals(pool.returns(), 1);
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }
}
