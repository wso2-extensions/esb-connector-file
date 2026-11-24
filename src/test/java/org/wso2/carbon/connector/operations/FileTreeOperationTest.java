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
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;
import org.wso2.vfs.shared.write.AtomicWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AccessDeniedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
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

    @Test
    public void unknownFilterTypeOrAnAgeThatDoesNotParseIsRefusedBeforeAnyFileIsTouched() throws Exception {
        Path dir = WriteFileTest.directory();
        Files.createDirectories(dir.resolve("in"));
        Files.writeString(dir.resolve("in/a.csv"), "A");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            JsonObject delete = invoke(new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/in",
                    "fileFilterType", "glob", "includeFiles", "*.csv");
            error(delete, "700107");
            Assert.assertTrue(delete.toString().contains("fileFilterType") && delete.toString().contains("glob"),
                    delete.toString());
            JsonObject move = invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/in",
                    "targetPath", "/out", "createParentDirectories", "true", "maxFileAge", "one day");
            error(move, "700107");
            Assert.assertTrue(move.toString().contains("maxFileAge") && move.toString().contains("one day"),
                    move.toString());
        }
        Assert.assertEquals(Files.readString(dir.resolve("in/a.csv")), "A");
        Assert.assertFalse(Files.exists(dir.resolve("out")));
    }

    @Test
    public void deleteWithAPatternOrAFilterStaysAtTheFirstLevelAndKeepsTheFolder() throws Exception {
        for (String[] filter : new String[][] {{"matchingPattern", ".*\\.csv"}, {"includeFiles", ".*\\.csv"}}) {
            Path dir = WriteFileTest.directory();
            Path folder = Files.createDirectories(dir.resolve("in/sub"));
            Files.writeString(dir.resolve("in/a.csv"), "A");
            Files.writeString(dir.resolve("in/keep.txt"), "K");
            Files.writeString(folder.resolve("b.csv"), "B");
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
                JsonObject delete = invoke(new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/in",
                        filter[0], filter[1]);
                success(delete);
                Assert.assertFalse(Files.exists(dir.resolve("in/a.csv")), filter[0]);
                Assert.assertEquals(Files.readString(dir.resolve("in/keep.txt")), "K", filter[0]);
                // A matching file in a sub folder stays, as with 6.x.
                Assert.assertEquals(Files.readString(folder.resolve("b.csv")), "B", filter[0]);
                Assert.assertTrue(Files.isDirectory(dir.resolve("in")), filter[0]);
            }
        }
    }

    @Test
    public void moveWithAPatternOrAFilterKeepsItsFoldersAndWalksEverySubFolder() throws Exception {
        for (String[] filter : new String[][] {{"filePattern", ".*\\.csv"}, {"includeFiles", ".*\\.csv"}}) {
            Path dir = WriteFileTest.directory();
            Path folder = Files.createDirectories(dir.resolve("in/sub"));
            Files.writeString(dir.resolve("in/a.csv"), "A");
            Files.writeString(folder.resolve("b.csv"), "B");
            try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
                success(invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/in",
                        "targetPath", "/out", "createParentDirectories", "true", filter[0], filter[1]));
                Assert.assertEquals(Files.readString(dir.resolve("out/a.csv")), "A", filter[0]);
                Assert.assertEquals(Files.readString(dir.resolve("out/sub/b.csv")), "B", filter[0]);
                // Every file matched, and the source folder and its emptied sub folder stay, as with 6.x.
                Assert.assertTrue(Files.isDirectory(dir.resolve("in")), filter[0]);
                Assert.assertTrue(Files.isDirectory(folder), filter[0]);
            }
        }
    }

    @Test
    public void deleteWithAPatternKeepsTheFolderWhenNothingIsLeftInIt() throws Exception {
        Path dir = WriteFileTest.directory();
        Files.createDirectories(dir.resolve("in"));
        Files.writeString(dir.resolve("in/a.csv"), "A");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            success(invoke(new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/in",
                    "matchingPattern", ".*\\.csv"));
            Assert.assertFalse(Files.exists(dir.resolve("in/a.csv")));
            Assert.assertTrue(Files.isDirectory(dir.resolve("in")));
        }
    }

    @Test
    public void memberWhoseTypeCanNotBeReadIsLeftAndTheWalkGoesOn() throws Exception {
        for (String operation : new String[] {"move", "copy", "delete"}) {
            Path dir = WriteFileTest.directory();
            Path sub = Files.createDirectories(dir.resolve("in/sub"));
            Files.writeString(dir.resolve("in/a.csv"), "A");
            Files.writeString(sub.resolve("x.csv"), "X");
            // With no search right on the folder its listing works and the type look of a member fails, as an SMB2
            // file with no rights refuses the open for its attributes.
            Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("r--r--r--"));
            try {
                try {
                    Files.readAttributes(sub.resolve("x.csv"), BasicFileAttributes.class);
                    throw new SkipException("This user reads a file with no rights on its folder");
                } catch (AccessDeniedException expected) {
                    // The fixture works.
                }
                try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
                    JsonObject result = operation.equals("delete")
                            ? invoke(new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/in")
                            : invoke(operation.equals("move") ? new MoveFiles() : new CopyFiles(),
                                    WriteFileTest.message(files), "sourcePath", "/in", "targetPath", "/out",
                                    "createParentDirectories", "true");
                    error(result, "700108");
                    Set<String> left = new HashSet<>();
                    for (JsonElement item : result.getAsJsonArray("filesLeft")) {
                        left.add(item.getAsJsonObject().get("name").getAsString());
                    }
                    Assert.assertEquals(left, Set.of("sub/x.csv"), operation);
                }
            } finally {
                Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Assert.assertEquals(Files.readString(sub.resolve("x.csv")), "X", operation);
            Assert.assertEquals(Files.exists(dir.resolve("in/a.csv")), operation.equals("copy"), operation);
            if (!operation.equals("delete")) {
                Assert.assertEquals(Files.readString(dir.resolve("out/a.csv")), "A", operation);
            }
        }
    }

    @Test
    public void sourceFolderInFilesLeftShowsItsAddressWithTheMaskedPassword() {
        // The connector tests have no server with a password, so the label rule is checked on its own.
        Assert.assertEquals(FileTreeOperation.Walk.label("sftp://user:secret-word@host/in", ""),
                "sftp://user:***@host/in");
        Assert.assertEquals(FileTreeOperation.Walk.label("sftp://user:secret-word@host/in/sub", "sub"), "sub");
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
            // A move with a filter keeps its folders, as 6.x did.
            Assert.assertTrue(Files.isDirectory(empty));
            Assert.assertEquals(Files.readString(nested.resolve("keep.txt")), "left");
            JsonObject delete = invoke(new DeleteFileOrFolder(), ctx, "path", "/src");
            success(delete);
            Assert.assertEquals(delete.get("numOfDeletedFiles").getAsInt(), 1, delete.toString());
            Assert.assertEquals(Files.readString(nested.resolve("marker.fail")), "marker");
            Assert.assertEquals(Files.readString(temp), "private");
        }
    }

    @DataProvider
    public Object[][] mixedSelectors() {
        return new Object[][] {{false, ".*[.]csv"}, {true, ".*[.]csv"}, {false, "["}, {true, "["}};
    }

    @Test(dataProvider = "mixedSelectors")
    public void advancedCopyAndMoveIgnoreInactiveLegacyPattern(boolean move, String legacy) throws Exception {
        Path root = WriteFileTest.directory();
        Path source = Files.createDirectory(root.resolve("src"));
        Files.writeString(source.resolve("one.txt"), "selected");
        Files.writeString(source.resolve("two.csv"), "survivor");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            success(invoke(move ? new MoveFiles() : new CopyFiles(), WriteFileTest.message(files),
                    "sourcePath", "/src", "targetPath", "/dest", "createParentDirectories", "true",
                    move ? "filePattern" : "sourceFilePattern", legacy, "includeFiles", "one.*"));
            Assert.assertEquals(Files.readString(root.resolve("dest/one.txt")), "selected");
            Assert.assertFalse(Files.exists(root.resolve("dest/two.csv")));
            Assert.assertEquals(Files.readString(source.resolve("two.csv")), "survivor");
            if (move) {
                Assert.assertFalse(Files.exists(source.resolve("one.txt")));
            } else {
                Assert.assertEquals(Files.readString(source.resolve("one.txt")), "selected");
            }
        }
    }

    @DataProvider
    public Object[][] legacyAndStability() {
        return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
    }

    @Test(dataProvider = "legacyAndStability")
    public void explicitStabilitySelectsAdvancedCopyAndMove(boolean move, boolean stability) throws Exception {
        Path root = WriteFileTest.directory();
        Path source = Files.createDirectory(root.resolve("src"));
        Files.writeString(source.resolve("one.txt"), "one");
        Files.writeString(source.resolve("two.csv"), "two");
        List<String> options = new ArrayList<>(java.util.Arrays.asList("sourcePath", "/src", "targetPath", "/dst",
                "createParentDirectories", "true", move ? "filePattern" : "sourceFilePattern", ".*[.]txt"));
        if (stability) {
            options.add("timeBetweenSizeCheck");
            options.add("0");
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            success(invoke(move ? new MoveFiles() : new CopyFiles(), WriteFileTest.message(files),
                    options.toArray(new String[0])));
            Assert.assertEquals(Files.readString(root.resolve("dst/one.txt")), "one");
            Assert.assertEquals(Files.exists(root.resolve("dst/two.csv")), stability);
            Assert.assertEquals(Files.exists(source.resolve("two.csv")), !move || !stability);
        }
    }

    @Test
    public void namedMoveChecksActiveLegacyGuardBeforeChangingEitherPath() throws Exception {
        Path root = WriteFileTest.directory();
        Path source = Files.writeString(root.resolve("one.txt"), "only");
        Path target = Files.createDirectory(root.resolve("out")).resolve("one.txt");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            error(invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/one.txt", "targetPath", "/out",
                    "filePattern", ".*[.]csv", "includeFiles", ".*[.]txt"), "700108");
            Assert.assertEquals(Files.readString(source), "only");
            Assert.assertFalse(Files.exists(target));
            error(invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/one.txt", "targetPath", "/out",
                    "filePattern", "[", "includeFiles", ".*[.]txt"), "700107");
            Assert.assertEquals(Files.readString(source), "only");
            Assert.assertFalse(Files.exists(target));
            success(invoke(new MoveFiles(), WriteFileTest.message(files), "sourcePath", "/one.txt", "targetPath", "/out",
                    "filePattern", "one[.]txt", "includeFiles", ".*[.]txt"));
            Assert.assertFalse(Files.exists(source));
            Assert.assertEquals(Files.readString(target), "only");
        }
    }

    @Test
    public void appendMergePreparesMissingParentsForAtomicAndDirectWrites() throws Exception {
        Path root = WriteFileTest.directory();
        Files.writeString(Files.createDirectory(root.resolve("src")).resolve("one"), "A");
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString(),
                "fileLockScheme", "Cluster")) {
            for (boolean atomic : new boolean[]{true, false}) {
                String target = "new" + atomic + "/sub/joined";
                success(invoke(new MergeFiles(), WriteFileTest.message(files), "sourceDirectoryPath", "/src",
                        "targetFilePath", "/" + target, "writeMode", "Append", "atomicWrite", String.valueOf(atomic)));
                Assert.assertEquals(Files.readString(root.resolve(target)), "A");
            }
        }
    }

    @DataProvider
    public Object[][] mergeWriters() {
        return new Object[][] {{"A", "B", ""}, {"B", "A", ""}, {"A", "B", "old"}, {"B", "A", "old"}};
    }

    @Test(dataProvider = "mergeWriters")
    public void cooperatingAppendWritersKeepBothContributions(String slow, String fast, String initial)
            throws Exception {
        Path root = WriteFileTest.directory();
        Path target = root.resolve("new/joined");
        if (!initial.isEmpty()) {
            Files.createDirectory(target.getParent());
            Files.writeString(target, initial);
        }
        for (String writer : new String[]{slow, fast}) {
            Files.writeString(Files.createDirectory(root.resolve(writer)).resolve("item"), writer);
        }
        MergeFiles delayed = new MergeFiles() {
            @Override
            void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
                FileObject source = new DecoratedFileObject(folder) {
                    @Override
                    public FileObject[] getChildren() throws FileSystemException {
                        runIndependentWriter(root, fast);
                        return super.getChildren();
                    }
                };
                super.perform(call, attempt, source, result);
            }
        };
        append(root, slow, delayed);
        Assert.assertEquals(Files.readString(target), initial + fast + slow);
    }

    // Each process has its own local lock registry. The writers share only the file system and its Cluster lock.
    private static void runIndependentWriter(Path root, String writer) throws FileSystemException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Path log = root.resolve("peer.log");
        try {
            Process peer = new ProcessBuilder(java, "-cp", classpath, PeerMerge.class.getName(), root.toString(), writer)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                Assert.assertTrue(peer.waitFor(30, TimeUnit.SECONDS), "Peer merge timed out");
                Assert.assertEquals(peer.exitValue(), 0, Files.readString(log));
            } finally {
                if (peer.isAlive()) {
                    peer.destroyForcibly();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FileSystemException(interrupted);
        } catch (java.io.IOException error) {
            throw new FileSystemException(error);
        }
    }

    public static final class PeerMerge {
        public static void main(String[] args) throws Exception {
            append(Path.of(args[0]), args[1], new MergeFiles());
        }
    }

    private static void append(Path root, String writer, MergeFiles merge) throws Exception {
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString(),
                "fileLockScheme", "Cluster")) {
            JsonObject result = invoke(merge, WriteFileTest.message(fixture), "sourceDirectoryPath", "/" + writer,
                    "targetFilePath", "/new/joined", "writeMode", "Append");
            success(result);
            Assert.assertEquals(result.getAsJsonObject("detail").get("totalWrittenBytes").getAsInt(), 1);
        }
    }

    @Test
    public void inaccessibleParentFailsReadAndDeleteAndPreservesChild() throws Exception {
        Path root = WriteFileTest.directory();
        Path parent = Files.createDirectory(root.resolve("blocked"));
        Path child = Files.writeString(parent.resolve("item"), "preserved");
        if (!Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            throw new org.testng.SkipException("Requires POSIX directory permissions");
        }
        Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(parent);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            ReadFileTest reader = new ReadFileTest();
            Axis2MessageContext ctx = reader.message(files, new BinaryBuilder());
            try {
                reader.read(ctx, "/absent/item", false, false);
                Assert.fail("Missing read succeeded");
            } catch (SynapseException missing) {
                Assert.assertEquals(ctx.getProperty("ERROR_CODE"), "700102");
            }
            Files.setPosixFilePermissions(parent, Collections.emptySet());
            if (Files.isReadable(child)) {
                throw new org.testng.SkipException("Requires a user subject to directory permissions");
            }
            JsonObject delete = invoke(new DeleteFileOrFolder(), WriteFileTest.message(files), "path", "/blocked/item");
            Assert.assertTrue(delete.has("error"), delete.toString());
            try {
                reader.read(ctx, "/blocked/item", false, false);
                Assert.fail("Read from inaccessible parent succeeded");
            } catch (SynapseException denied) {
                Assert.assertNotEquals(ctx.getProperty("ERROR_CODE"), "700102");
            }
        } finally {
            Files.setPosixFilePermissions(parent, original);
        }
        Assert.assertEquals(Files.readString(child), "preserved");
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
