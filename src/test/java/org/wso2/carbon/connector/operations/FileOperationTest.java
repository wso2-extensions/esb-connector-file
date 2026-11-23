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
import org.apache.axis2.format.BinaryBuilder;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.zip.GZIPOutputStream;

import static org.wso2.carbon.connector.operations.FileTreeOperationTest.invoke;

public class FileOperationTest {
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject success(FileOperation op, Axis2MessageContext message, String... params) throws Exception {
        JsonObject result = invoke(op, message, params);
        Assert.assertTrue(result.get("success").getAsBoolean(), result.toString());
        return result;
    }

    @Test
    public void chunkBoundariesAndJoinedBytes() throws Exception {
        for (String sample : Arrays.asList("ABCDEFGHIJ", "ABCDEFGHIJKL", "")) {
            assertChunks(bytes(sample), false);
        }
    }

    @Test
    public void shortReadsFillEachChunk() throws Exception {
        assertChunks(bytes("ABCDEFGHIJ"), true);
    }

    private void assertChunks(byte[] sample, boolean shortReads) throws Exception {
        InputStream input = new ByteArrayInputStream(sample) {
            @Override public synchronized int read(byte[] buffer, int off, int len) {
                return super.read(buffer, off, shortReads ? Math.min(len, 3) : len);
            }
        };
        List<Integer> sizes = new ArrayList<>();
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        int count = SplitFile.chunks(input, "a.csv", 4, (name, content) -> {
            ByteArrayOutputStream part = new ByteArrayOutputStream();
            content.writeTo(part);
            Assert.assertEquals(name, "a" + sizes.size() + ".csv");
            sizes.add(part.size());
            part.writeTo(joined);
        });
        List<Integer> expected = new ArrayList<>();
        for (int offset = 0; offset < sample.length; offset += 4) {
            expected.add(Math.min(4, sample.length - offset));
        }
        Assert.assertEquals(sizes, expected);
        Assert.assertEquals(count, expected.size());
        Assert.assertEquals(joined.toByteArray(), sample);
    }

    @Test
    public void invalidChunkBeforeBorrow() throws Exception {
        Path root = WriteFileTest.directory();
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(fixture);
            for (String value : Arrays.asList("0", "-1", "abc")) {
                JsonObject result = invoke(new SplitFile(), WriteFileTest.message(fixture), "splitMode", "Chunk Size",
                        "chunkSize", value, "sourceFilePath", "/missing", "targetDirectory", "/parts");
                Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), "700107");
            }
            Assert.assertEquals(pool.attempts.size(), 0);
            Assert.assertFalse(Files.exists(root.resolve("parts")));
        }
    }

    @Test
    public void publicPartNamesAndContent() throws Exception {
        Path root = WriteFileTest.directory();
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            for (String name : Arrays.asList("a.csv", "a.b.csv", "README")) {
                Files.write(root.resolve(name), bytes("ABCDE"));
                String directory = "/" + name + ".out";
                JsonObject result = success(new SplitFile(), WriteFileTest.message(fixture), "splitMode", "Chunk Size",
                        "chunkSize", "4", "sourceFilePath", "/" + name, "targetDirectory", directory);
                String stem = name.equals("README") ? name : name.substring(0, name.length() - 4);
                String extension = name.equals("README") ? "" : ".csv";
                Path out = root.resolve(directory.substring(1));
                Assert.assertEquals(result.get("numberOfSplits").getAsInt(), 2);
                Assert.assertEquals(Files.readAllBytes(out.resolve(stem + "0" + extension)), bytes("ABCD"));
                Assert.assertEquals(Files.readAllBytes(out.resolve(stem + "1" + extension)), bytes("E"));
            }
        }
    }

    @Test
    public void listCreateCheckAndFetch() throws Exception {
        Path root = WriteFileTest.directory();
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(fixture);
            for (int i = 0; i < 2; i++) {
                success(new CreateDirectory(), ctx, "directoryPath", "/files/sub", "autoCreate", "true");
            }
            Path folder = root.resolve("files");
            Files.write(folder.resolve("business.part"), bytes("part"));
            Files.write(folder.resolve("a.txt"), bytes("alpha"));
            Files.write(folder.resolve("sub/b.txt"), bytes("beta"));
            Files.write(folder.resolve("marker.fail"), bytes("marker"));
            Files.write(Files.createDirectory(folder.resolve(".part")).resolve("hidden.txt"), bytes("hidden"));
            Assert.assertTrue(success(new CheckFileExist(), ctx, "path", "/files/a.txt").get("fileExists").getAsBoolean());
            Assert.assertFalse(success(new CheckFileExist(), ctx, "path", "/missing").get("fileExists").getAsBoolean());
            JsonObject listed = success(new ListFiles(), ctx, "directoryPath", "/files", "recursive", "true");
            Assert.assertTrue(listed.toString().contains("business.part"));
            Assert.assertTrue(listed.toString().contains("b.txt"));
            Assert.assertFalse(listed.toString().contains("marker.fail"));
            Assert.assertFalse(listed.toString().contains("hidden.txt"));
            listed = success(new ListFiles(), ctx, "directoryPath", "/files", "responseFormat", "Flat",
                    "includeSystemFiles", "true", "recursive", "true", "appendFileAttributes", "true");
            Assert.assertTrue(listed.toString().contains("marker.fail"));
            Assert.assertTrue(listed.toString().contains("hidden.txt"));
            Map<String, String> params = new HashMap<>();
            params.put("directoryPath", "/files");
            params.put("matchingPattern", ".*\\.txt");
            params.put("recursive", "true");
            params.put("resultPropertyName", "contents");
            TemplateContext template = new TemplateContext("file", Collections.emptyList());
            template.setMappedValues(params);
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            ctx.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            new FetchContentToList().connect(ctx);
            Assert.assertEquals(ctx.getProperty("contents"), Arrays.asList(
                    Base64.getEncoder().encodeToString(bytes("alpha")), Base64.getEncoder().encodeToString(bytes("beta"))));
        }
    }

    @Test
    public void mergeModesPreserveOrderAndCounts() throws Exception {
        Path root = WriteFileTest.directory();
        Path parts = Files.createDirectory(root.resolve("input"));
        Files.write(parts.resolve("b.txt"), bytes("B"));
        Files.write(parts.resolve("a.txt"), bytes("A"));
        Files.write(parts.resolve("hidden.fail"), bytes("skip"));
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(fixture);
            Path target = root.resolve("joined");
            for (String atomic : Arrays.asList("true", "false")) {
                Files.write(target, bytes("old"));
                JsonObject result = success(new MergeFiles(), ctx, "sourceDirectoryPath", "/input", "targetFilePath",
                        "/joined", "writeMode", "Overwrite", "atomicWrite", atomic);
                Assert.assertEquals(Files.readAllBytes(target), bytes("AB"));
                Assert.assertEquals(result.getAsJsonObject("detail").get("numberOfMergedFiles").getAsInt(), 2);
                success(new MergeFiles(), ctx, "sourceDirectoryPath", "/input", "targetFilePath", "/joined",
                        "writeMode", "Append", "atomicWrite", atomic);
                Assert.assertEquals(Files.readAllBytes(target), bytes("ABAB"));
            }
        }
    }

    @Test
    public void archivesAndOrdinarySplitModes() throws Exception {
        Path root = WriteFileTest.directory();
        Path source = Files.createDirectories(root.resolve("docs/sub"));
        Files.write(source.resolve("notes"), bytes("notes"));
        Files.write(root.resolve("docs/lines.txt"), bytes("A\nB\nC\n"));
        Files.write(root.resolve("x.xml"), bytes("<items><item>A</item><item>B</item></items>"));
        try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(root.resolve("notes.gz")))) {
            gzip.write(bytes("zipped"));
        }
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(fixture);
            success(new SplitFile(), ctx, "sourceFilePath", "/docs/lines.txt", "targetDirectory", "/lines",
                    "splitMode", "Line Count", "lineCount", "2");
            Assert.assertEquals(Files.readAllBytes(root.resolve("lines/lines0.txt")), bytes("A\nB\n"));
            Assert.assertEquals(Files.readAllBytes(root.resolve("lines/lines1.txt")), bytes("C\n"));
            success(new SplitFile(), ctx, "sourceFilePath", "/x.xml", "targetDirectory", "/xml",
                    "splitMode", "XPATH Expression", "xpathExpression", "/items/item");
            Assert.assertTrue(new String(Files.readAllBytes(root.resolve("xml/items1.xml")),
                    StandardCharsets.UTF_8).contains("<item>A</item>"));
            success(new CompressFiles(), ctx, "sourceDirectoryPath", "/docs", "targetFilePath", "/docs.zip");
            JsonObject archive = success(new ExploreZipFile(), ctx, "zipFilePath", "/docs.zip");
            Assert.assertEquals(archive.getAsJsonArray("zipFileContent").size(), 2);
            JsonObject unpacked = success(new UnzipFile(), ctx, "sourceFilePath", "/docs.zip",
                    "targetDirectory", "/out", "includeFileNames", "true");
            Assert.assertEquals(unpacked.get("zipFileContent"), archive.get("zipFileContent"));
            Assert.assertEquals(Files.readAllBytes(root.resolve("out/sub/notes")), bytes("notes"));
            success(new UnzipFile(), ctx, "sourceFilePath", "/notes.gz", "targetDirectory", "/gzip");
            Assert.assertEquals(Files.readAllBytes(root.resolve("gzip/notes")), bytes("zipped"));
        }
    }

    @Test
    public void metadataLookAfterOwnedReadAndDelete() throws Exception {
        Path root = WriteFileTest.directory();
        Path file = Files.write(root.resolve("owned.txt"), bytes("owned"));
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            ReadFileTest reader = new ReadFileTest();
            Axis2MessageContext message = reader.message(fixture, new BinaryBuilder());
            reader.read(message, "/owned.txt", true, false);
            Assert.assertTrue(invoke(new DeleteFileOrFolder(), message, "path", "/owned.txt").get("success").getAsBoolean());
            Assert.assertFalse(success(new CheckFileExist(), message, "path", "/owned.txt").get("fileExists").getAsBoolean());
            Files.write(file, bytes("new"));
            Assert.assertTrue(success(new CheckFileExist(), message, "path", "/owned.txt").get("fileExists").getAsBoolean());
        }
    }

    @Test
    public void advancedSelectionUsesTheShippedFilter() throws Exception {
        Path root = WriteFileTest.directory();
        Path folder = Files.createDirectory(root.resolve("files"));
        Files.write(folder.resolve("a.txt"), bytes("A"));
        Files.write(folder.resolve("b.csv"), bytes("B"));
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(fixture);
            String selected = success(new ListFiles(), ctx, "directoryPath", "/files",
                    "matchingPattern", "a[.]txt", "includeFiles", "b[.]csv").toString();
            Assert.assertTrue(selected.contains("b.csv"), selected);
            Assert.assertFalse(selected.contains("a.txt"), selected);
            success(new MergeFiles(), ctx, "sourceDirectoryPath", "/files", "targetFilePath", "/joined",
                    "writeMode", "Overwrite", "filePattern", "a[.]txt", "includeFiles", "b[.]csv");
            Assert.assertEquals(Files.readAllBytes(root.resolve("joined")), bytes("B"));
        }
    }

    @Test
    public void listOptionsAreValidatedBeforeBorrow() throws Exception {
        Path root = WriteFileTest.directory();
        Files.writeString(root.resolve("match.csv"), "contents");
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(fixture);
            Axis2MessageContext ctx = WriteFileTest.message(fixture);
            assertError(invoke(new ListFiles(), ctx, "directoryPath", "/", "responseFormat", "bogus"), "700107");
            assertError(invoke(new ListFiles(), ctx, "directoryPath", "/absent", "responseFormat", "bogus"), "700107");
            assertError(invoke(new ListFiles(), ctx, "directoryPath", "/", "matchingPattern", "["), "700107");
            assertError(invoke(new ListFiles(), ctx, "directoryPath", "/", "timeBetweenSizeCheck", "invalid"), "700107");
            assertError(invoke(new MergeFiles(), ctx, "sourceDirectoryPath", "/", "targetFilePath", "/joined",
                    "timeBetweenSizeCheck", "invalid"), "700107");
            assertError(invoke(new CompressFiles(), ctx, "sourceDirectoryPath", "/", "targetFilePath", "/files.zip",
                    "timeBetweenSizeCheck", "invalid"), "700107");
            Assert.assertFalse(Files.exists(root.resolve("joined")));
            Assert.assertFalse(Files.exists(root.resolve("files.zip")));
            assertError(invoke(new ListFiles(), ctx, "directoryPath", "/", "includeFiles", "["), "700107");
            assertError(invoke(new FetchContentToList(), ctx, "directoryPath", "/", "matchingPattern", "["), "700107");
            assertError(invoke(new MergeFiles(), ctx, "sourceDirectoryPath", "/", "targetFilePath", "/joined",
                    "filePattern", "["), "700107");
            Assert.assertEquals(pool.attempts.size(), 0);
            for (String format : Arrays.asList("Flat", "Hierarchical")) {
                JsonObject result = success(new ListFiles(), ctx, "directoryPath", "/", "responseFormat", format,
                        "matchingPattern", "[", "includeFiles", "match[.]csv");
                Assert.assertTrue(result.has(format.equals("Flat") ? "files" : "directory"));
                Assert.assertTrue(result.toString().contains("match.csv"));
            }
            Assert.assertEquals(pool.attempts.size(), 2);
        }
    }

    @Test
    public void createBelowFileKeepsPoolHealthy() throws Exception {
        for (boolean autoCreate : Arrays.asList(false, true)) {
            Path root = WriteFileTest.directory();
            Path occupied = Files.writeString(root.resolve("occupied"), "original");
            try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
                FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(fixture);
                Axis2MessageContext ctx = WriteFileTest.message(fixture);
                for (String path : Arrays.asList("/occupied/child", "/occupied/missing/grandchild", "/occupied")) {
                    int before = pool.attempts.size();
                    assertError(invoke(new CreateDirectory(), ctx, "directoryPath", path,
                            "autoCreate", Boolean.toString(autoCreate)), "700108");
                    Assert.assertEquals(pool.attempts.size() - before, 1);
                    Assert.assertTrue(success(new CheckFileExist(), ctx, "path", "/occupied")
                            .get("fileExists").getAsBoolean());
                }
                success(new CreateDirectory(), ctx, "directoryPath", "/valid/deep", "autoCreate",
                        Boolean.toString(autoCreate));
                success(new CreateDirectory(), ctx, "directoryPath", "/valid/deep");
                Assert.assertTrue(Files.isDirectory(root.resolve("valid/deep")));
                Assert.assertEquals(Files.readString(occupied), "original");
            }
        }
    }

    @Test
    public void ordinaryMissingSourcesAndInvalidPath() throws Exception {
        Path root = WriteFileTest.directory();
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString())) {
            Axis2MessageContext ctx = WriteFileTest.message(fixture);
            assertError(invoke(new ListFiles(),ctx,"directoryPath","/missing"),"700102");
            assertError(invoke(new MergeFiles(),ctx,"sourceDirectoryPath","/missing","targetFilePath","/out"),"700102");
            assertError(invoke(new SplitFile(),ctx,"sourceFilePath","/missing","targetDirectory","/parts",
                    "splitMode","Chunk Size","chunkSize","4"),"700102");
            assertError(invoke(new UnzipFile(),ctx,"sourceFilePath","/missing","targetDirectory","/out"),"700102");
            assertError(invoke(new ExploreZipFile(),ctx,"zipFilePath","/missing"),"700102");
            assertError(invoke(new FetchContentToList(),ctx,"directoryPath","/missing"),"700102");
            assertError(invoke(new CompressFiles(),ctx,"sourceDirectoryPath","/missing","targetFilePath","/out.zip"),"700102");
            assertError(invoke(new CheckFileExist(),ctx),"700107");
            Assert.assertFalse(Files.exists(root.resolve("out")));
            Assert.assertFalse(Files.exists(root.resolve("parts")));
        }
    }

    private static void assertError(JsonObject result, String code) {
        Assert.assertFalse(result.get("success").getAsBoolean());
        Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), code, result.toString());
    }
}
