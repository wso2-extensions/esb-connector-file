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

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class WriteFileOrderingTest {

    @DataProvider
    public Object[][] writerPoints() {
        return new Object[][] {
                {"write.local.after-borrow", false}, {"append.after-share-lock", true},
                {"append.in-transfer", true}, {"append.after-bytes", true},
                {"temp.before-time-set", false}, {"temp.before-permission-set", false},
                {"append.before-time-set", true}
        };
    }

    @Test(groups = "testbuild", dataProvider = "writerPoints")
    public void ordinaryLocalWriterWaitsAtItsDeclaredStep(String point, boolean append) throws Exception {
        Path data = WriteFileTest.directory();
        Path root = Files.createDirectory(data.resolve("points"));
        Path target = Files.writeString(data.resolve("item"), "old");
        String requested = "x".repeat(24576);
        String firstResult = append ? "old" + requested : requested;
        String previous = System.getProperty("vfs.testpoints.dir");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", data.toString())) {
            System.setProperty("vfs.testpoints.dir", root.toString());
            TestPoints.copy("connector", WriteFile.class);
            TestPoints.event("ordering.ready", files.name);
            Path copy;
            try (Stream<Path> folders = Files.list(root)) {
                copy = folders.filter(p -> Files.isRegularFile(p.resolve("copy"))).findFirst().get();
            }
            Path control = copy.resolve(point + ".hold");
            boolean connectionKey = point.equals("write.local.after-borrow") || point.endsWith("-set");
            Files.writeString(control, "wait=10\n" + (connectionKey ? "connection=" + files.name + "\n" : ""));
            try {
                Future<?> first = workers.submit(() -> {
                    WriteFileTest.write(WriteFileTest.message(files), "item", requested,
                            "writeMode", append ? "Append" : "Overwrite", "lastModified", "1700000000000",
                            "updateFilePermission", "0640");
                    return null;
                });
                awaitPoint(copy, point, first);
                Assert.assertFalse(first.isDone(), point);
                if (!append || point.equals("append.after-share-lock")) {
                    Assert.assertEquals(Files.readString(target), "old", point);
                }
                if (point.startsWith("temp.before-")) {
                    try (Stream<Path> names = Files.list(data.resolve(".part"))) {
                        Path staged = names.filter(Files::isRegularFile).findFirst().get();
                        Assert.assertEquals(Files.readString(staged), requested);
                        long actual = Files.getLastModifiedTime(staged).toMillis();
                        if (point.equals("temp.before-permission-set")) {
                            Assert.assertEquals(actual, 1700000000000L);
                        } else {
                            Assert.assertNotEquals(actual, 1700000000000L);
                        }
                    }
                }
                if (append) {
                    Assert.assertTrue(Files.isRegularFile(data.resolve("item.lock")), point);
                }
                if (point.equals("append.after-bytes") || point.equals("append.before-time-set")) {
                    Assert.assertEquals(Files.readString(target), firstResult, point);
                }
                if (point.equals("append.before-time-set")) {
                    Assert.assertNotEquals(Files.getLastModifiedTime(target).toMillis(), 1700000000000L);
                }

                AtomicReference<Thread> contender = new AtomicReference<>();
                Future<?> second = workers.submit(() -> {
                    contender.set(Thread.currentThread());
                    WriteFileTest.write(WriteFileTest.message(files), "item", "+", "writeMode", "Append",
                            "updateLastModified", "false");
                    return null;
                });
                awaitPathWait(contender, second);
                Files.delete(control);
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                Assert.assertEquals(Files.readString(target), firstResult + "+");
                Assert.assertEquals(((Integer) Files.getAttribute(target, "unix:mode")) & 0777, 0640);
                Assert.assertFalse(Files.exists(data.resolve("item.lock")));
            } finally {
                Files.deleteIfExists(control);
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
            if (previous == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", previous);
            }
        }
    }

    @Test(groups = "testbuild")
    public void insertProgressFollowsBuffersAcrossLineBoundaries() throws Exception {
        Path data = WriteFileTest.directory();
        Path root = Files.createDirectory(data.resolve("points"));
        String previous = System.getProperty("vfs.testpoints.dir");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", data.toString())) {
            System.setProperty("vfs.testpoints.dir", root.toString());
            TestPoints.copy("connector", WriteFile.class);
            TestPoints.event("ordering.ready", files.name);
            for (boolean shortLines : new boolean[]{false, true}) {
                String line = "x".repeat(shortLines ? 2046 : 1048576);
                String original = shortLines ? (line + "\r\n").repeat(512) : line;
                Path target = Files.writeString(data.resolve("line"), original);
                long before = insertResets(root);
                WriteFileTest.write(WriteFileTest.message(files), "line", "é", "writeMode", "Append",
                        "appendPosition", "1");
                long after = insertResets(root);
                Assert.assertEquals(Files.readString(target), line + "é" + original.substring(line.length()));
                Assert.assertTrue(after - before >= original.length() / 8192,
                        "Expected progress for each moved buffer, got " + (after - before));
            }
        } finally {
            if (previous == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", previous);
            }
        }
    }

    private long insertResets(Path root) throws Exception {
        long count = 0;
        try (Stream<Path> logs = Files.walk(root)) {
            for (Path log : (Iterable<Path>) logs.filter(f -> f.getFileName().toString().equals("events.log"))::iterator) {
                try (Stream<String> lines = Files.lines(log)) {
                    count += lines.filter(l -> l.contains("point=progress.reset") && l.contains("cause=insert")).count();
                }
            }
        }
        return count;
    }

    private void awaitPoint(Path directory, String point, Future<?> owner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Stream<Path> files = Files.list(directory)) {
                if (files.map(f -> f.getFileName().toString())
                        .anyMatch(n -> n.startsWith(point + "@") && n.endsWith(".reached"))) {
                    return;
                }
            }
            if (owner.isDone()) {
                owner.get();
                Assert.fail("The writer completed without waiting at " + point);
            }
            Thread.sleep(5);
        }
        Assert.fail("The writer did not reach " + point);
    }

    private void awaitPathWait(AtomicReference<Thread> thread, Future<?> second) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Thread worker = thread.get();
            if (worker != null && Arrays.stream(worker.getStackTrace()).anyMatch(s ->
                    s.getClassName().equals("org.wso2.vfs.shared.lock.PathRegistry") && s.getMethodName().equals("await"))) {
                return;
            }
            if (second.isDone()) {
                second.get();
                Assert.fail("Second writer passed the held path");
            }
            Thread.sleep(5);
        }
        Assert.fail("Second writer did not wait for the same path");
    }
}
