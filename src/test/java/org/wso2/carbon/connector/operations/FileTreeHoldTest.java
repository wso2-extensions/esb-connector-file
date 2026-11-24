/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
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
import org.apache.synapse.MessageContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.lock.LockIo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The hold points of the folder operations. An operation waits at a hold that a test armed and goes on when the test
 * releases it. The walk holds after the listing of a folder, and a delete holds before the lock of each entry. A retry
 * after a lost reply holds before its looks decide the outcome.
 */
public class FileTreeHoldTest {

    private static final String LISTING = "walk.after-listing";

    private static final String DECISION = "retry.before-decision";

    private static final String DELETE = "delete.before-lock";

    private static final String REQUEST = "delete.before-request";

    /**
     * What a test does while the operation waits at the hold.
     */
    private interface WhileHeld {
        void run() throws Exception;
    }

    @Test(groups = "testbuild")
    public void folderMoveWaitsAfterTheListingUntilTheRelease() throws Exception {
        Path data = WriteFileTest.directory();
        Path source = Files.createDirectory(data.resolve("src"));
        for (int i = 1; i <= 3; i++) {
            Files.writeString(source.resolve("f0" + i + ".txt"), "file " + i);
        }
        JsonObject result = heldAndReleased(data, LISTING, files -> () -> FileTreeOperationTest.invoke(
                new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/src", "targetPath", "/dst",
                "createParentDirectories", "true"), () -> {
                    // The walk waits after the listing, before the first member moves.
                    for (int i = 1; i <= 3; i++) {
                        Assert.assertTrue(Files.isRegularFile(source.resolve("f0" + i + ".txt")));
                    }
                    Assert.assertFalse(Files.exists(data.resolve("dst/f01.txt")));
                    Files.writeString(source.resolve("late.txt"), "late");
                });
        Assert.assertFalse(result.has("error"), result.toString());
        Assert.assertEquals(result.getAsJsonArray("filesMoved").size(), 3, result.toString());
        for (int i = 1; i <= 3; i++) {
            Assert.assertEquals(Files.readString(data.resolve("dst/f0" + i + ".txt")), "file " + i);
        }
        // The file that came after the listing is not part of the move.
        Assert.assertFalse(Files.exists(data.resolve("dst/late.txt")));
        Assert.assertEquals(Files.readString(source.resolve("late.txt")), "late");
    }

    @Test(groups = "testbuild")
    public void deleteWaitsBeforeTheLockUntilTheRelease() throws Exception {
        Path data = WriteFileTest.directory();
        Path source = Files.writeString(data.resolve("a.txt"), "A");
        JsonObject result = heldAndReleased(data, DELETE, files -> () -> FileTreeOperationTest.invoke(
                new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/a.txt"), () -> {
                    // The delete waits before its lock and before its request.
                    Assert.assertEquals(Files.readString(source), "A");
                });
        Assert.assertFalse(result.has("error"), result.toString());
        Assert.assertFalse(Files.exists(source));
    }

    @Test(groups = "testbuild")
    public void deleteWaitsRightBeforeItsRequestUntilTheRelease() throws Exception {
        Path data = WriteFileTest.directory();
        Path source = Files.writeString(data.resolve("a.txt"), "A");
        JsonObject result = heldAndReleased(data, REQUEST, null, files -> () -> FileTreeOperationTest.invoke(
                new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/a.txt"), () -> {
                    // The lock look is done and the request is not sent: the file is still there.
                    Assert.assertEquals(Files.readString(source), "A");
                    // Another party puts other content under the name before the old file was removed.
                    Files.writeString(source, "B");
                });
        // A delete goes by path and the connector makes no identity check, so the new file goes too. The guarantee
        // asks a producer to use unique names, to honour the lock, or to reuse a name only after the old file was
        // removed. This party did none of the three.
        Assert.assertFalse(result.has("error"), result.toString());
        Assert.assertFalse(Files.exists(source));
    }

    @Test(groups = "testbuild")
    public void deleteWithItsOwnLockKeepsTheLockAcrossTheRequest() throws Exception {
        Path data = WriteFileTest.directory();
        Path source = Files.writeString(data.resolve("a.txt"), "A");
        Path lock = data.resolve("a.txt.lock");
        JsonObject result = heldAndReleased(data, REQUEST, null, files -> () -> FileTreeOperationTest.invoke(
                new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/a.txt", "enableLock", "true"),
                () -> {
                    // The connector holds its own lock at the request, so a producer that honours the lock waits.
                    Assert.assertTrue(Files.isRegularFile(lock), "The lock is held during the hold");
                    Assert.assertEquals(Files.readString(source), "A");
                });
        Assert.assertFalse(result.has("error"), result.toString());
        Assert.assertFalse(Files.exists(source));
        Assert.assertFalse(Files.exists(lock), "The lock is gone after the delete");
    }

    @Test(groups = "testbuild")
    public void folderDeleteWaitsBeforeTheRequestOfTheHeldMemberOnly() throws Exception {
        Path data = WriteFileTest.directory();
        Path folder = Files.createDirectory(data.resolve("d"));
        for (int i = 1; i <= 3; i++) {
            Files.writeString(folder.resolve("f" + i + ".txt"), "file " + i);
        }
        Path held = folder.resolve("f2.txt");
        JsonObject result = heldAndReleased(data, REQUEST, held, files -> () -> FileTreeOperationTest.invoke(
                new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/d"), () -> {
                    // Only the member of the key waits, before its own request.
                    Assert.assertEquals(Files.readString(held), "file 2");
                });
        Assert.assertFalse(result.has("error"), result.toString());
        Assert.assertFalse(Files.exists(folder));
    }

    @Test(groups = "testbuild")
    public void moveRetryWaitsBeforeItsDecisionUntilTheRelease() throws Exception {
        Path data = WriteFileTest.directory();
        Path source = Files.writeString(Files.createDirectory(data.resolve("in")).resolve("a.txt"), "A");
        JsonObject result = heldAndReleased(data, DECISION, files -> () -> FileTreeOperationTest.invoke(
                new MoveFiles() {
                    @Override
                    Walk operation(MessageContext message) throws InvalidConfigurationException {
                        return new Move(message, false) {
                            @Override
                            void change(Attempt attempt, FileObject from, FileObject to, String path,
                                        String targetPath) throws Exception {
                                // The rename takes effect and its reply is lost, as the fork reports it.
                                super.change(attempt, from, to, path, targetPath);
                                throw new UnclearResultException("The reply is missing", null, MovePhase.RENAME,
                                        null, false);
                            }
                        };
                    }
                }, WriteFileTest.message(files), "sourcePath", "/in/a.txt", "targetPath", "/done",
                "createParentDirectories", "true", "overwrite", "true"), () -> {
                    // The move took effect before its reply was lost.
                    Assert.assertEquals(Files.readString(data.resolve("done/a.txt")), "A");
                    Files.writeString(source, "B");
                });
        assertUnclear(result);
        Assert.assertEquals(Files.readString(source), "B");
        Assert.assertEquals(Files.readString(data.resolve("done/a.txt")), "A");
    }

    @Test(groups = "testbuild")
    public void deleteRetryWaitsBeforeItsDecisionUntilTheRelease() throws Exception {
        Path data = WriteFileTest.directory();
        Path source = Files.writeString(data.resolve("a.txt"), "A");
        JsonObject result = heldAndReleased(data, DECISION, files -> () -> FileTreeOperationTest.invoke(
                new DeleteFileOrFolder() {
                    @Override
                    Walk operation(MessageContext message) throws InvalidConfigurationException {
                        return lostDeleteReply(message);
                    }
                }, WriteFileTest.message(files), "path", "/a.txt"), () -> {
                    // The delete took effect before its reply was lost.
                    Assert.assertFalse(Files.exists(source));
                    Files.writeString(source, "B");
                });
        assertUnclear(result);
        Assert.assertEquals(Files.readString(source), "B");
    }

    // The delete of the product, whose reply is lost after the file is gone. The delete is final, so this walk hands
    // each step to it.
    private static FileTreeOperation.Walk lostDeleteReply(MessageContext message)
            throws InvalidConfigurationException {
        DeleteFileOrFolder.Delete delete = new DeleteFileOrFolder.Delete(message);
        return new FileTreeOperation.Walk(message, "deleteFile", "path", "matchingPattern") {
            @Override
            public OperationFrame.Retry retry() {
                return delete.retry();
            }

            @Override
            public OperationFrame.Use use(String path) {
                return delete.use(path);
            }

            @Override
            boolean removesSource() {
                return delete.removesSource();
            }

            @Override
            void change(Attempt attempt, FileObject source, FileObject target, String path, String targetPath)
                    throws Exception {
                delete.change(attempt, new DecoratedFileObject(source) {
                    @Override
                    public boolean delete() throws FileSystemException {
                        // The delete takes effect and its reply is lost.
                        super.delete();
                        throw lostReply();
                    }
                }, target, path, targetPath);
            }

            @Override
            void settle(Attempt attempt) throws Exception {
                delete.settle(attempt);
            }
        };
    }

    private static FileSystemException lostReply() {
        FileSystemException failure = new FileSystemException("Connection lost");
        failure.setFault(Fault.TRANSPORT);
        return failure;
    }

    // The new file under the source name is left alone, and the outcome is unclear.
    private static void assertUnclear(JsonObject result) {
        Assert.assertTrue(result.has("error"), result.toString());
        Assert.assertTrue(result.toString().contains("700111"), result.toString());
    }

    /**
     * The operation that the test runs, made with the pool of the test.
     */
    private interface Call {
        Callable<JsonObject> with(FilePoolFixture files) throws Exception;
    }

    // Arms the hold, runs the operation, waits until it reaches the hold, checks that it still waits, runs the step of
    // the test and releases the hold. Returns the result of the operation.
    private static JsonObject heldAndReleased(Path data, String point, Call call, WhileHeld whileHeld)
            throws Exception {
        return heldAndReleased(data, point, null, call, whileHeld);
    }

    // With a member, only the operation on that path waits.
    private static JsonObject heldAndReleased(Path data, String point, Path member, Call call, WhileHeld whileHeld)
            throws Exception {
        Path root = Files.createDirectory(data.resolve("points"));
        String previous = System.getProperty("vfs.testpoints.dir");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Path control = null;
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", data.toString())) {
            System.setProperty("vfs.testpoints.dir", root.toString());
            TestPoints.copy("connector", FileTreeOperation.class);
            TestPoints.event("hold.ready", files.name);
            Path copy;
            try (Stream<Path> folders = Files.list(root)) {
                copy = folders.filter(p -> Files.isRegularFile(p.resolve("copy"))).findFirst().get();
            }
            String name = member == null ? point
                    : point + "@k" + keyHash(LockIo.pointKey(member.toUri().toString()));
            control = Files.writeString(copy.resolve(name + ".hold"), "wait=30\n");
            Future<JsonObject> operation = worker.submit(call.with(files));
            awaitReached(copy, point, operation);
            Thread.sleep(300);
            Assert.assertFalse(operation.isDone(), "The operation waits at " + point);
            whileHeld.run();
            Files.delete(control);
            JsonObject result = operation.get(10, TimeUnit.SECONDS);
            Assert.assertTrue(Files.readAllLines(copy.resolve("events.log")).stream()
                    .anyMatch(line -> line.contains("hold-end") && line.contains("at=" + point)
                            && line.contains("end=released")), "The hold ended by the release");
            return result;
        } finally {
            if (control != null) {
                Files.deleteIfExists(control);
            }
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
            if (previous == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", previous);
            }
        }
    }

    // The name of the hold file for one key, as the test points build it.
    private static String keyHash(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append(String.format("%02x", digest[i]));
        }
        return text.toString();
    }

    private static void awaitReached(Path copy, String point, Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Stream<Path> names = Files.list(copy)) {
                if (names.map(p -> p.getFileName().toString())
                        .anyMatch(n -> n.startsWith(point + "@") && n.endsWith(".reached"))) {
                    return;
                }
            }
            if (operation.isDone()) {
                Assert.fail("The operation completed without waiting at " + point + ": " + operation.get());
            }
            Thread.sleep(5);
        }
        Assert.fail("The operation did not reach " + point);
    }
}
