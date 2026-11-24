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
package org.wso2.carbon.connector.operations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.CircuitOpenException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.connection.PoolBusyException;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

public class FileFrameLeaseTest {
    private static final class Fate {
        final JsonObject row = new JsonObject();
        int returns;
        int invalidates;

        Fate(Object handler) {
            row.addProperty("handler", handler == null ? null : Integer.toHexString(System.identityHashCode(handler)));
            update();
        }

        void update() {
            row.addProperty("returns", returns);
            row.addProperty("invalidates", invalidates);
        }
    }

    public static final class CountingPool extends ConnectionPool {
        final Map<Object, Fate> acquired = new IdentityHashMap<>();
        final JsonArray attempts = new JsonArray();
        final CircuitBreaker breaker;
        ConnectException denial;
        public int returnedToClosed;
        public Object closedHandler;
        public volatile Consumer<Object> afterBorrow = handler -> { };

        public synchronized void inventory(JsonObject facts) {
            JsonArray loans = new JsonArray();
            JsonObject ends = new JsonObject();
            for (int index = 0; index < attempts.size(); index++) {
                JsonObject row = attempts.get(index).getAsJsonObject();
                if (row.get("handler").isJsonNull()) {
                    continue;
                }
                String id = row.get("handler").getAsString() + "/" + index;
                loans.add(id);
                JsonArray fates = new JsonArray();
                for (int n = 0; n < row.get("returns").getAsInt(); n++) {
                    fates.add("returned");
                }
                for (int n = 0; n < row.get("invalidates").getAsInt(); n++) {
                    fates.add("invalidated");
                }
                ends.add(id, fates);
            }
            facts.add("borrowedLoans", loans);
            facts.add("loanFates", ends);
        }

        public synchronized boolean ownsManager(Object manager) {
            return acquired.keySet().stream().anyMatch(handler ->
                    ((FileSystemHandler) handler).getFsManager() == manager);
        }

        public synchronized int returns() {
            int total = 0;
            for (int index = 0; index < attempts.size(); index++) {
                total += attempts.get(index).getAsJsonObject().get("returns").getAsInt();
            }
            return total;
        }

        CountingPool(ConnectionPool original) {
            super(original.getConnectionFactory(), original.getConfiguration());
            breaker = original.getBreaker();
        }

        @Override
        public CircuitBreaker getBreaker() { return breaker; }

        @Override
        public Object borrowObject() throws ConnectException {
            if (denial != null) {
                attempts.add(new Fate(null).row);
                throw denial;
            }
            Object handler = super.borrowObject();
            Fate fate = new Fate(handler);
            synchronized (this) {
                attempts.add(fate.row);
                acquired.put(handler, fate);
            }
            afterBorrow.accept(handler);
            return handler;
        }

        @Override
        public void returnObject(Object handler) {
            synchronized (this) {
                if (isClosed()) {
                    returnedToClosed++;
                    closedHandler = handler;
                }
                Fate fate = acquired.get(handler);
                fate.returns++;
                fate.update();
            }
            super.returnObject(handler);
        }

        @Override
        public void invalidateObject(Object handler) throws Exception {
            synchronized (this) {
                Fate fate = acquired.get(handler);
                fate.invalidates++;
                fate.update();
            }
            super.invalidateObject(handler);
        }
    }

    @Test
    public void eachFrameOutcomeEndsEveryLeaseOnce() throws Exception {
        JsonArray cells = new JsonArray();
        for (String protocol : new String[]{"LOCAL", "SFTP", "FTP", "FTPS", "SMB2"}) {
            for (String outcome : new String[]{"success", "application", "lock-busy", "fast-retry", "slow-transport",
                    "failed-borrow", "open"}) {
                run(protocol, outcome, cells);
            }
        }
        String output = System.getenv("FILE_UNIT_OBSERVATIONS");
        if (output != null) {
            JsonObject report = new JsonObject();
            report.addProperty("run", System.getProperty("connector.unit.run"));
            report.add("cells", cells);
            Files.write(Paths.get(output), report.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void failedBorrowPreservesThePriorFailureAndEndsOnlyAcquiredHandlers() throws Exception {
        JsonArray cases = new JsonArray();
        FrameUnitEvidence.Checks verify = new FrameUnitEvidence.Checks();
        try {
            for (boolean retryBorrow : new boolean[]{false, true}) {
                for (boolean busy : new boolean[]{false, true}) {
                    try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
                        CountingPool pool = install(files);
                        ConnectException refusal = busy ? new PoolBusyException(new NoSuchElementException())
                                : new CircuitOpenException("The circuit is open");
                        if (!retryBorrow) {
                            pool.denial = refusal;
                        }
                        OperationFrameStreamTest.Steps<Integer> op = new OperationFrameStreamTest.Steps<Integer>() {
                            public Integer steps(Attempt attempt) throws Exception {
                                pool.denial = refusal;
                                FileSystemException lost = new FileSystemException("The connection was lost");
                                lost.setFault(Fault.TRANSPORT);
                                throw lost;
                            }
                        };
                        JsonObject row = new JsonObject();
                        row.addProperty("case", (retryBorrow ? "retry" : "first") + (busy ? "Busy" : "Open"));
                        row.addProperty("refusal", refusal.getClass().getSimpleName());
                        try {
                            OperationFrame.run(files.message, files.name, 1, op);
                            row.addProperty("code", "OK");
                            row.addProperty("type", "OK");
                            verify.fail("The borrow succeeded");
                        } catch (OperationFrame.Failure failure) {
                            Error expected = retryBorrow ? Error.CONNECTION_ERROR
                                    : busy ? Error.POOL_BUSY : Error.CONNECTION_SUSPENDED;
                            row.addProperty("code", failure.error.getErrorCode());
                            row.addProperty("type", failure.error.getErrorDetail());
                            verify.assertEquals(failure.error, expected);
                        }
                        row.add("attempts", pool.attempts);
                        cases.add(row);
                        verify.assertEquals(pool.acquired.size(), retryBorrow ? 1 : 0);
                        verify.assertEquals(pool.attempts.size(), retryBorrow ? 2 : 1);
                        for (Fate fate : pool.acquired.values()) {
                            verify.assertEquals(fate.returns, 0);
                            verify.assertEquals(fate.invalidates, 1);
                        }
                        verify.assertEquals(pool.getNumActive(), 0);
                    }
                }
            }
        } finally {
            FrameUnitEvidence.write("failedBorrowPreservesThePriorFailureAndEndsOnlyAcquiredHandlers", cases);
        }
        verify.assertAll();
    }

    @SuppressWarnings("unchecked")
    public static CountingPool install(FilePoolFixture files) throws Exception {
        Map<String, Object> entries = (Map<String, Object>) FilePoolFixture.field(files.core,
                ConnectionHandler.class, "connectionMap");
        String key = "file:" + files.name;
        ConnectionPool original = (ConnectionPool) entries.get(key);
        CountingPool pool = new CountingPool(original);
        Assert.assertTrue(entries.replace(key, original, pool));
        original.close();
        return pool;
    }

    private void run(String protocol, String outcome, JsonArray cells) throws Exception {
        try (FilePoolFixture files = new FilePoolFixture(protocol)) {
            CountingPool pool = install(files);
            if (outcome.equals("open")) {
                pool.breaker.admit().reportFailure();
            }
            OperationFrame.Operation<String> op = new OperationFrame.Operation<String>() {
                public LinkedHashMap<String, OperationFrame.Lock> paths() { return new LinkedHashMap<>(); }
                public OperationFrame.Use use(String path) { return OperationFrame.Use.SOURCE; }
                public OperationFrame.Retry retry() { return OperationFrame.Retry.READ; }
                public String removed(String path) { throw new AssertionError(path); }

                public String steps(Attempt attempt) throws Exception {
                    switch (outcome) {
                        case "success":
                            return outcome;
                        case "application":
                            throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The server refused the request");
                        case "lock-busy":
                            throw new OperationFrame.Failure(Error.FILE_LOCKING_ERROR, "The file is locked");
                        case "fast-retry":
                            if (attempt.number == 2) {
                                return outcome;
                            }
                            break;
                        case "failed-borrow":
                            pool.denial = new PoolBusyException(new NoSuchElementException("The pool is busy"));
                            break;
                        case "slow-transport":
                            Thread.sleep(5010);
                            break;
                        default:
                            throw new AssertionError("The open breaker ran file steps");
                    }
                    FileSystemException error = new FileSystemException("Lost connection");
                    error.setFault(Fault.TRANSPORT);
                    throw error;
                }
            };
            Error expected;
            switch (outcome) {
                case "application": expected = Error.OPERATION_ERROR; break;
                case "lock-busy": expected = Error.FILE_LOCKING_ERROR; break;
                case "open": expected = Error.CONNECTION_SUSPENDED; break;
                case "slow-transport":
                case "failed-borrow": expected = Error.CONNECTION_ERROR; break;
                default: expected = null;
            }
            try {
                Assert.assertEquals(OperationFrame.run(files.message, files.name, 1, op), outcome);
                Assert.assertNull(expected);
            } catch (OperationFrame.Failure failure) {
                Assert.assertEquals(failure.error, expected, protocol + " " + outcome);
            }
            if (outcome.equals("open")) {
                Assert.assertEquals(pool.attempts.size(), 0);
                pool.attempts.add(new Fate(null).row);
            }
            int expectedBorrows = outcome.equals("failed-borrow") || outcome.equals("fast-retry") ? 2 : 1;
            Assert.assertEquals(pool.attempts.size(), expectedBorrows);
            int expectedInvalid = outcome.equals("slow-transport") || outcome.equals("failed-borrow")
                    || outcome.equals("fast-retry") ? 1 : 0;
            int invalidated = 0;
            for (Fate fate : pool.acquired.values()) {
                Assert.assertEquals(fate.returns + fate.invalidates, 1);
                invalidated += fate.invalidates;
            }
            Assert.assertEquals(invalidated, expectedInvalid);
            Assert.assertEquals(pool.getNumActive(), 0);
            JsonObject cell = new JsonObject();
            cell.addProperty("protocol", protocol);
            cell.addProperty("outcome", outcome);
            cell.add("attempts", pool.attempts);
            cells.add(cell);
        }
    }
}
