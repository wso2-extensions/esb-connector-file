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
package org.wso2.carbon.connector.connection;

import org.apache.synapse.MessageContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.operations.FilePoolFixture;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.vfs.shared.lock.HeldLock;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LockWaitBudgetTest {
    private abstract static class Steps implements OperationFrame.Operation<Integer> {
        final LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();

        public LinkedHashMap<String, OperationFrame.Lock> paths() { return paths; }
        public OperationFrame.Use use(String path) { return OperationFrame.Use.WRITE; }
        public OperationFrame.Retry retry() { return OperationFrame.Retry.SAFE; }
        public Integer removed(String path) { throw new AssertionError(path); }
    }

    private static MessageContext message() {
        return (MessageContext) Proxy.newProxyInstance(MessageContext.class.getClassLoader(),
                new Class<?>[]{MessageContext.class}, (proxy, method, args) -> null);
    }

    private static byte[] foreignLock() {
        return ("18:other:address:" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void longWorkBeforeTheCommitLockLeavesTheWholeWait() throws Exception {
        Path file = Files.createTempFile("lock-budget", ".txt");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        AtomicLong offset = new AtomicLong();
        ScheduledExecutorService other = Executors.newSingleThreadScheduledExecutor();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            StreamLeaseTest.virtualContext(files,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + offset.get(), HeldLock::releaseChecked);
            String path = file.toUri().toString();
            Steps write = new Steps() {
                public Integer steps(Attempt attempt) throws Exception {
                    // The transfer before the commit takes longer than the whole lock wait budget.
                    offset.addAndGet(11000);
                    Files.write(marker, foreignLock());
                    other.schedule(() -> Files.deleteIfExists(marker), 1, TimeUnit.SECONDS);
                    attempt.acquireCommitLock(path);
                    return attempt.number;
                }
            };
            write.paths.put(path, OperationFrame.Lock.LOCAL);
            Assert.assertEquals(OperationFrame.run(message(), files.name, 0, write).intValue(), 1);
            Assert.assertFalse(Files.exists(marker));
        } finally {
            other.shutdownNow();
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }

    @Test
    public void lockThatStaysBusyEndsWhenTheWaitIsUsed() throws Exception {
        Path file = Files.createTempFile("lock-budget", ".txt");
        Path marker = file.resolveSibling(file.getFileName() + ".lock");
        byte[] foreign = foreignLock();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            Files.write(marker, foreign);
            Steps move = new Steps() {
                public Integer steps(Attempt attempt) {
                    throw new AssertionError("The steps ran under a busy lock");
                }
            };
            move.paths.put(file.toUri().toString(), OperationFrame.Lock.OWN);
            // Make the context and one handler first, so that the time below is the time of the operation.
            files.factory.getContext().borrowForStream().returnToPool();
            long start = System.nanoTime();
            try {
                OperationFrame.run(message(), files.name, 0, move);
                Assert.fail("The lock stayed busy");
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, Error.FILE_LOCKING_ERROR);
            }
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Assert.assertTrue(waited >= 9000 && waited < 14000, "waited " + waited + " ms");
            Assert.assertEquals(Files.readAllBytes(marker), foreign);
        } finally {
            Files.deleteIfExists(marker);
            Files.delete(file);
        }
    }
}
