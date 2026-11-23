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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.axis2.format.BinaryBuilder;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;
import org.wso2.vfs.shared.write.AtomicWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FileTreeOperationTest {
    @BeforeClass
    public void recordRuntimeInputs() throws Exception {
        List<String> roles = new ArrayList<>();
        for (Class<?> type : new Class<?>[]{MoveOptions.class, Smb2FileSystemConfigBuilder.class,
                AtomicWriter.class, ConnectionHandler.class}) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            roles.add(type.getName() + "\t" + origin);
        }
        Files.write(Path.of("runtime-dependency-origins.tsv"), roles);
    }

    static JsonObject invoke(AbstractConnectorOperation operation, Axis2MessageContext message, String... options)
            throws Exception {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < options.length; i += 2) {
            params.put(options[i], options[i + 1]);
        }
        TemplateContext template = new TemplateContext("file", Collections.emptyList());
        template.setMappedValues(params);
        Stack<TemplateContext> stack = new Stack<>();
        stack.push(template);
        message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
        String response = "result" + UUID.randomUUID().toString().replace("-", "");
        try {
            operation.execute(message, response, false);
        } catch (SynapseException failure) {
            if (message.getVariable(response) == null) {
                throw failure;
            }
        }
        return (JsonObject) ((ConnectorResponse) message.getVariable(response)).getPayload();
    }

    private static void success(JsonObject result) {
        Assert.assertFalse(result.has("error"), result.toString());
    }

    private static void error(JsonObject result, String code) {
        Assert.assertTrue(result.has("error"), result.toString());
        Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), code, result.toString());
    }

    @DataProvider
    public Object[][] leaseMoves() {
        return new Object[][] {
                {false, "/docs", "report.txt", true}, {false, "/view", "report.txt", true},
                {true, "/docs", "report.txt", true}, {true, "/docs", "renamed.txt", false},
                {false, "/other", "report.txt", false}, {false, "/docs", "hard.txt", false}
        };
    }

    @Test(dataProvider = "leaseMoves")
    public void lockedReadThenMoveThenCopy(boolean rename, String directory, String name, boolean same)
            throws Exception {
        Path root = WriteFileTest.directory();
        Path docs = Files.createDirectory(root.resolve("docs"));
        Path source = Files.writeString(docs.resolve("report.txt"), "report");
        Files.createSymbolicLink(root.resolve("view"), docs);
        Files.createDirectory(root.resolve("other"));
        if (name.equals("hard.txt")) {
            Files.createLink(docs.resolve(name), source);
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            ReadFileTest reader = new ReadFileTest();
            Axis2MessageContext ctx = reader.message(files, new BinaryBuilder());
            reader.read(ctx, "/docs/report.txt", true, false);
            String path = files.factory.getContext().getBaseUri() + "/docs/report.txt";
            StreamLease lease = StreamLeases.find(ctx).get(files.name, path);
            Assert.assertNotNull(lease);
            JsonObject move = rename ? invoke(new RenameFiles(), ctx, "path", "/docs/report.txt", "renameTo", name,
                    "overwrite", "true") : invoke(new MoveFiles(), ctx, "sourcePath", "/docs/report.txt",
                    "targetPath", directory, "renameTo", name, "overwrite", "true");
            success(move);
            Assert.assertEquals(Files.readString(root.resolve(directory.substring(1)).resolve(name)), "report");
            Assert.assertEquals(Files.exists(source), same);
            Assert.assertEquals(Files.exists(docs.resolve("report.txt.lock")), same);
            Assert.assertEquals(lease.reason(), same ? null : StreamLease.Reason.REMOVED);
            JsonObject copy = invoke(new CopyFiles(), ctx, "sourcePath", "/docs/report.txt", "targetPath", "/out");
            if (same) {
                success(copy);
                Assert.assertEquals(Files.readString(root.resolve("out/report.txt")), "report");
                Assert.assertEquals(lease.state(), StreamLease.State.REPLAY);
            } else {
                error(copy, "700102");
            }
            for (JsonElement item : pool.attempts) {
                JsonObject loan = item.getAsJsonObject();
                if (!loan.get("handler").isJsonNull()) {
                    Assert.assertEquals(loan.get("returns").getAsInt(), 1, loan.toString());
                    Assert.assertEquals(loan.get("invalidates").getAsInt(), 0, loan.toString());
                }
            }
        }
    }

    @Test
    public void folderCollisionLeavesMembersAndContinuesOtherBranches() throws Exception {
        for (boolean move : new boolean[]{true, false}) {
            Path root = WriteFileTest.directory();
            Path src = Files.createDirectory(root.resolve("src"));
            Path dst = Files.createDirectory(root.resolve("dst"));
            for (String name : List.of("one", "two", "three")) {
                Files.writeString(Files.createDirectory(src.resolve(name)).resolve("data"), name);
            }
            String[] listed = src.toFile().list();
            Assert.assertNotNull(listed);
            String blocked = listed[1];
            Files.writeString(Files.createDirectory(src.resolve(blocked).resolve("nested")).resolve("file"), "file");
            Files.writeString(dst.resolve(blocked), "keep");
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
                JsonObject result = invoke(move ? new MoveFiles() : new CopyFiles(), WriteFileTest.message(files),
                        "sourcePath", "/src", "targetPath", "/dst", "createParentDirectories", "true");
                error(result, "700108");
                Set<String> left = new HashSet<>();
                for (JsonElement item : result.getAsJsonArray("filesLeft")) {
                    left.add(item.getAsJsonObject().get("name").getAsString());
                }
                Assert.assertEquals(left, Set.of(blocked + "/data", blocked + "/nested/file"));
                Assert.assertEquals(Files.readString(dst.resolve(blocked)), "keep");
                Assert.assertEquals(Files.readString(src.resolve(blocked).resolve("data")), blocked);
                Assert.assertEquals(Files.readString(src.resolve(blocked).resolve("nested/file")), "file");
                for (String other : List.of(listed[0], listed[2])) {
                    Assert.assertEquals(Files.readString(dst.resolve(other).resolve("data")), other);
                    Assert.assertEquals(Files.exists(src.resolve(other).resolve("data")), !move);
                }
            }
        }
    }

    @Test
    public void publicMoveRenameCopyDeleteAndMissingDelete() throws Exception {
        Path dir = WriteFileTest.directory();
        Files.writeString(dir.resolve("original"), "payload");
        FileTime time = FileTime.fromMillis(1680000000000L);
        Files.setLastModifiedTime(dir.resolve("original"), time);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(files);
            error(invoke(new MoveFiles(), ctx, "sourcePath", "/original", "targetPath", "/nested"), "700102");
            success(invoke(new MoveFiles(), ctx, "sourcePath", "/original", "targetPath", "/nested",
                    "createParentDirectories", "true", "enableLock", "true", "renameTo", "moved"));
            Assert.assertFalse(Files.exists(dir.resolve("original")));
            Assert.assertEquals(Files.getLastModifiedTime(dir.resolve("nested/moved")), time);
            success(invoke(new RenameFiles(), ctx, "path", "/nested/moved", "renameTo", "value"));
            success(invoke(new CopyFiles(), ctx, "sourcePath", "/nested/value", "targetPath", "/out"));
            Path copy = dir.resolve("out/value");
            Assert.assertEquals(Files.readString(copy), "payload");
            Files.writeString(copy, "old");
            error(invoke(new CopyFiles(), ctx, "sourcePath", "/nested/value", "targetPath", "/out"), "700103");
            Assert.assertEquals(Files.readString(copy), "old");
            success(invoke(new CopyFiles(), ctx, "sourcePath", "/nested/value", "targetPath", "/out",
                    "overwrite", "true"));
            Assert.assertEquals(Files.readString(copy), "payload");
            JsonObject deleted = invoke(new DeleteFileOrFolder(), ctx, "path", "/out/value");
            JsonObject expected = new JsonObject();
            expected.addProperty("success", true);
            Assert.assertEquals(deleted, expected);
            Assert.assertFalse(Files.exists(copy));
            JsonObject absent = invoke(new DeleteFileOrFolder(), ctx, "path", "/out/value");
            expected.addProperty("numOfDeletedFiles", 0);
            Assert.assertEquals(absent, expected);
        }
    }

    @Test
    public void deleteUnderOwnReadLeaseReturnsOrdinarySuccess() throws Exception {
        Path dir = WriteFileTest.directory();
        Path source = Files.writeString(dir.resolve("owned.txt"), "payload");
        Path marker = dir.resolve("owned.txt.lock");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            ReadFileTest reader = new ReadFileTest();
            Axis2MessageContext ctx = reader.message(files, new BinaryBuilder());
            reader.read(ctx, "/owned.txt", true, false);
            String uri = files.factory.getContext().getBaseUri() + "/owned.txt";
            StreamLease lease = StreamLeases.find(ctx).get(files.name, uri);
            Assert.assertTrue(Files.isRegularFile(marker));
            JsonObject expected = new JsonObject();
            expected.addProperty("success", true);
            Assert.assertEquals(invoke(new DeleteFileOrFolder(), ctx, "path", "/owned.txt"), expected);
            Assert.assertFalse(Files.exists(source));
            Assert.assertFalse(Files.exists(marker));
            Assert.assertEquals(lease.reason(), StreamLease.Reason.REMOVED);
            expected.addProperty("numOfDeletedFiles", 0);
            Assert.assertEquals(invoke(new DeleteFileOrFolder(), ctx, "path", "/owned.txt"), expected);
        }
    }

    @Test
    public void filteredWalkRetainsOtherFilesAndOnlyRemovesEmptyDirectories() throws Exception {
        Path dir = WriteFileTest.directory();
        Path nested = Files.createDirectories(dir.resolve("src/sub"));
        Path empty = Files.createDirectories(dir.resolve("src/emptyAfterMove"));
        Files.writeString(nested.resolve("keep.txt"), "left");
        Files.writeString(nested.resolve("a.csv"), "A");
        Files.writeString(empty.resolve("b.csv"), "B");
        Files.writeString(nested.resolve("marker.fail"), "marker");
        Path temp = Files.createDirectories(nested.resolve(".part")).resolve("data");
        Files.writeString(temp, "private");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(files);
            JsonObject move = invoke(new MoveFiles(), ctx, "sourcePath", "/src", "targetPath", "/dest",
                    "createParentDirectories", "true", "includeFiles", ".*\\.csv");
            success(move);
            Assert.assertEquals(move.getAsJsonArray("filesMoved").size(), 2, move.toString());
            Assert.assertEquals(Files.readString(dir.resolve("dest/sub/a.csv")), "A");
            Assert.assertEquals(Files.readString(dir.resolve("dest/emptyAfterMove/b.csv")), "B");
            Assert.assertFalse(Files.exists(empty));
            Assert.assertEquals(Files.readString(nested.resolve("keep.txt")), "left");
            JsonObject delete = invoke(new DeleteFileOrFolder(), ctx, "path", "/src");
            success(delete);
            Assert.assertEquals(delete.get("numOfDeletedFiles").getAsInt(), 1, delete.toString());
            Assert.assertEquals(Files.readString(nested.resolve("marker.fail")), "marker");
            Assert.assertEquals(Files.readString(temp), "private");
        }
    }

    @Test
    public void deleteRequiresBothTheLegacyPatternAndTheIncludeFilter() throws Exception {
        Path dir = WriteFileTest.directory();
        Path folder = Files.createDirectory(dir.resolve("data"));
        Files.writeString(folder.resolve("one.csv"), "selected");
        Files.writeString(folder.resolve("one.txt"), "excluded by pattern");
        Files.writeString(folder.resolve("two.csv"), "excluded by include");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            JsonObject result = invoke(new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/data",
                    "matchingPattern", ".*[.]csv", "includeFiles", "one.*", "timeBetweenSizeCheck", "1");
            success(result);
            Assert.assertEquals(result.get("numOfDeletedFiles").getAsInt(), 1);
            Assert.assertFalse(Files.exists(folder.resolve("one.csv")));
            Assert.assertEquals(Files.readString(folder.resolve("one.txt")), "excluded by pattern");
            Assert.assertEquals(Files.readString(folder.resolve("two.csv")), "excluded by include");
        }
    }

    @Test
    public void folderMoveContinuesAfterAnExistingTargetAndKeepsItsSource() throws Exception {
        Path dir = WriteFileTest.directory();
        Path source = Files.createDirectories(dir.resolve("src"));
        Path target = Files.createDirectories(dir.resolve("dst"));
        Files.writeString(source.resolve("a"), "new A");
        Files.writeString(source.resolve("b"), "B");
        Files.writeString(target.resolve("a"), "old A");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            JsonObject result = invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/src",
                    "targetPath", "/dst");
            error(result, "700103");
            Assert.assertEquals(Files.readString(source.resolve("a")), "new A");
            Assert.assertEquals(Files.readString(target.resolve("a")), "old A");
            Assert.assertEquals(Files.readString(target.resolve("b")), "B");
            Assert.assertFalse(Files.exists(source.resolve("b")));
            Assert.assertEquals(result.getAsJsonArray("filesLeft").size(), 1);
            Assert.assertEquals(result.getAsJsonArray("filesMoved").size(), 1);
        }
    }

    @Test
    public void folderMoveCopyPathCommitsEachMemberBeforeRemovingItsSource() throws Exception {
        Path dir = WriteFileTest.directory();
        Path source = Files.createDirectories(dir.resolve("src"));
        Path destination = Files.createDirectories(dir.resolve("dst"));
        for (String name : new String[]{"first", "second", "third"}) {
            Files.writeString(source.resolve(name), "source " + name);
        }
        String[] listing = source.toFile().list();
        Assert.assertNotNull(listing);
        Assert.assertEquals(listing.length, 3);
        String conflict = listing[1];
        Files.writeString(destination.resolve(conflict), "earlier target");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            JsonObject result = invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/src",
                    "targetPath", "/dst", "isSourceMounted", "true", "enableLock", "true");
            error(result, "700103");
            Assert.assertEquals(result.getAsJsonArray("filesMoved").size(), 2);
            Assert.assertEquals(result.getAsJsonArray("filesLeft").get(0).getAsJsonObject().get("name").getAsString(),
                    conflict);
            for (String name : listing) {
                if (name.equals(conflict)) {
                    Assert.assertEquals(Files.readString(source.resolve(name)), "source " + name);
                    Assert.assertEquals(Files.readString(destination.resolve(name)), "earlier target");
                } else {
                    Assert.assertFalse(Files.exists(source.resolve(name)));
                    Assert.assertEquals(Files.readString(destination.resolve(name)), "source " + name);
                }
            }
        }
    }

    @Test
    public void twoLocalMovesToOneNameKeepTheOtherSource() throws Exception {
        Path dir = WriteFileTest.directory();
        Files.createDirectory(dir.resolve("dst"));
        Files.writeString(dir.resolve("one"), "one");
        Files.writeString(dir.resolve("two"), "two");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            MoveFiles mediator = new MoveFiles();
            Callable<JsonObject> one = () -> concurrentMove(files, mediator, ready, "one");
            Callable<JsonObject> two = () -> concurrentMove(files, mediator, ready, "two");
            Future<JsonObject> first = pool.submit(one);
            Future<JsonObject> second = pool.submit(two);
            JsonObject a = first.get(30, TimeUnit.SECONDS);
            JsonObject b = second.get(30, TimeUnit.SECONDS);
            String winner = Files.readString(dir.resolve("dst/name"));
            String loser = winner.equals("one") ? "two" : "one";
            success(winner.equals("one") ? a : b);
            error(winner.equals("one") ? b : a, "700103");
            Assert.assertEquals(Files.readString(dir.resolve(loser)), loser);
            Assert.assertFalse(Files.exists(dir.resolve(winner)));
            Assert.assertFalse(Files.exists(dir.resolve("dst/name.lock")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void folderCopyAndRenameHonourNestedLocksAndKeepBusinessPartFiles() throws Exception {
        Path dir = WriteFileTest.directory();
        Path folder = Files.createDirectories(dir.resolve("reports/sub"));
        String owner = "4711:otherhost:10.0.0.9:" + System.currentTimeMillis() + ":v2:" + UUID.randomUUID();
        Files.writeString(folder.resolve("locked"), "unpublished");
        Files.writeString(folder.resolve("locked.lock"), owner);
        Files.writeString(folder.resolve("business.part"), "valid");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(files);
            JsonObject copy = invoke(new CopyFiles(), ctx, "sourcePath", "/reports", "targetPath", "/archive",
                    "includeParent", "true", "renameTo", "batch", "atomicWrite", "false");
            success(copy);
            Assert.assertEquals(Files.readString(dir.resolve("archive/batch/sub/business.part")), "valid");
            Assert.assertFalse(Files.exists(dir.resolve("archive/batch/sub/locked")));
            Assert.assertEquals(Files.readString(folder.resolve("locked.lock")), owner);
            error(invoke(new RenameFiles(), ctx, "path", "/reports", "renameTo", "closed"), "700106");
            Assert.assertFalse(Files.exists(dir.resolve("closed")));
            Files.delete(folder.resolve("locked.lock"));
            success(invoke(new RenameFiles(), ctx, "path", "/reports", "renameTo", "closed"));
            Assert.assertEquals(Files.readString(dir.resolve("closed/sub/locked")), "unpublished");
        }
    }

    @Test
    public void moveToSameEntryUsesTheProvidersReplaceRule() throws Exception {
        Path dir = WriteFileTest.directory();
        Files.writeString(dir.resolve("self"), "unchanged");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(files);
            error(invoke(new MoveFiles(), ctx, "sourcePath", "/self", "targetPath", "/",
                    "isSourceMounted", "true"), "700103");
            success(invoke(new MoveFiles(), ctx, "sourcePath", "/self", "targetPath", "/",
                    "isSourceMounted", "true", "overwrite", "true"));
            Assert.assertEquals(Files.readString(dir.resolve("self")), "unchanged");
        }
    }

    private JsonObject concurrentMove(FilePoolFixture files, MoveFiles mediator, CountDownLatch ready, String file)
            throws Exception {
        Axis2MessageContext message = WriteFileTest.message(files);
        ready.countDown();
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        return invoke(mediator, message, "sourcePath", "/" + file, "targetPath", "/dst", "renameTo", "name");
    }
}
