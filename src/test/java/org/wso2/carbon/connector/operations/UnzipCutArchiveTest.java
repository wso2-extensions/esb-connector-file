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

import com.google.gson.JsonObject;
import org.apache.synapse.SynapseException;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFile;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileSystem;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An archive that was cut fails that unzip alone, with an operation error, and the connection goes on. A failure of
 * the stream of the archive is still a fault of the connection. The connection has the default settings, where one
 * fault of the connection suspends it.
 */
public class UnzipCutArchiveTest {
    private static final String OPERATION_ERROR = "700108";
    private static final String CONNECTION_ERROR = "700101";
    private static final byte[] TEXT = text();

    @DataProvider(name = "archives")
    public Object[][] archives() throws IOException {
        return new Object[][]{
                {"in.gz", gzip(TEXT), "in"},
                {"in.zip", zip(TEXT, "a.txt"), "a.txt"},
        };
    }

    // The unzip may try again on a new connection, and it does not, because the archive is broken.
    @Test(dataProvider = "archives")
    public void cutArchiveFailsTheUnzipAlone(String archive, byte[] content, String entry) throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve(archive), Arrays.copyOf(content, content.length / 2));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            JsonObject result = unzip(files, archive, 1);
            Assert.assertEquals(code(result), OPERATION_ERROR, result.toString());
            Assert.assertTrue(result.toString().contains("The archive could not be read"), result.toString());
            // The operator reads the sentence, not a message code of the file system library.
            Assert.assertFalse(result.toString().contains("Unknown message with code"), result.toString());
            Assert.assertEquals(pool.attempts.size(), 1, pool.attempts.toString());
            Assert.assertFalse(Files.exists(directory.resolve("out").resolve(entry)));
            // The next operation on the same connection is not refused.
            WriteFileTest.write(WriteFileTest.message(files), "next", "x");
            Assert.assertEquals(new String(Files.readAllBytes(directory.resolve("next")), StandardCharsets.UTF_8),
                    "x");
        }
    }

    @Test
    public void wholeArchivesStillUnzip() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.gz"), gzip(TEXT));
        Files.write(directory.resolve("in.zip"), zip(TEXT, "a.txt", "sub/b.txt"));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            for (String archive : Arrays.asList("in.gz", "in.zip")) {
                JsonObject result = unzip(files, archive, 1);
                Assert.assertTrue(result.get("success").getAsBoolean(), result.toString());
            }
        }
        Assert.assertEquals(Files.readAllBytes(directory.resolve("out/in")), TEXT);
        Assert.assertEquals(Files.readAllBytes(directory.resolve("out/a.txt")), TEXT);
        Assert.assertEquals(Files.readAllBytes(directory.resolve("out/sub/b.txt")), TEXT);
    }

    // The stream of the archive fails in the middle of the unzip, as a lost connection does.
    @Test(dataProvider = "archives")
    public void failureOfTheArchiveStreamIsStillAFaultOfTheConnection(String archive, byte[] content, String entry)
            throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve(archive), content);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            breakReads(files, archive);
            // With no second try on a new connection, the failure of the stream reaches the breaker.
            JsonObject result = unzip(files, archive, 0);
            Assert.assertEquals(code(result), CONNECTION_ERROR, result.toString());
            Assert.assertFalse(Files.exists(directory.resolve("out").resolve(entry)));
            try {
                WriteFileTest.write(WriteFileTest.message(files), "next", "x");
                Assert.fail("The connection was not suspended");
            } catch (SynapseException suspended) {
                Assert.assertTrue(suspended.getMessage().contains("The connection is suspended"),
                        suspended.getMessage());
            }
        }
    }

    // A cut zip under exploreZipFile fails that operation alone, as under unzip.
    @Test
    public void cutZipFailsTheExploreAlone() throws Exception {
        Path directory = WriteFileTest.directory();
        byte[] content = zip(TEXT, "a.txt", "sub/b.txt");
        Files.write(directory.resolve("in.zip"), Arrays.copyOf(content, content.length / 2));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            JsonObject result = FileTreeOperationTest.invoke(new ExploreZipFile(), WriteFileTest.message(files),
                    "zipFilePath", "/in.zip", "maxRetries", "1");
            Assert.assertEquals(code(result), OPERATION_ERROR, result.toString());
            Assert.assertTrue(result.toString().contains("The archive could not be read"), result.toString());
            Assert.assertEquals(pool.attempts.size(), 1, pool.attempts.toString());
            WriteFileTest.write(WriteFileTest.message(files), "next", "x");
            Assert.assertEquals(new String(Files.readAllBytes(directory.resolve("next")), StandardCharsets.UTF_8),
                    "x");
        }
    }

    // The entries are written straight into their targets.
    @Test(dataProvider = "archives")
    public void cutArchiveWrittenStraightFailsTheUnzipAlone(String archive, byte[] content, String entry)
            throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve(archive), Arrays.copyOf(content, content.length / 2));
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            JsonObject result = FileTreeOperationTest.invoke(new UnzipFile(), WriteFileTest.message(files),
                    "sourceFilePath", "/" + archive, "targetDirectory", "/out", "maxRetries", "1",
                    "atomicWrite", "false");
            Assert.assertEquals(code(result), OPERATION_ERROR, result.toString());
        }
    }

    // A file that is no archive fails as a broken archive, under a gz name and under a zip name.
    @Test
    public void fileThatIsNoArchive() throws Exception {
        Path directory = WriteFileTest.directory();
        Files.write(directory.resolve("in.gz"), TEXT);
        Files.write(directory.resolve("in.zip"), TEXT);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString())) {
            JsonObject gz = unzip(files, "in.gz", 1);
            Assert.assertEquals(code(gz), OPERATION_ERROR, gz.toString());
            Assert.assertTrue(gz.toString().contains("The archive could not be read"), gz.toString());
            JsonObject zip = unzip(files, "in.zip", 1);
            Assert.assertEquals(code(zip), OPERATION_ERROR, zip.toString());
            Assert.assertTrue(zip.toString().contains("The archive could not be read"), zip.toString());
        }
    }

    // A cut that shows only at the close of the stream, as the final reply of a cut FTP transfer does. The stream of a
    // file closes itself when a read gives its end, so the failure of that close comes out of the read and is a
    // failure of the stream. The unzip is tried again on a new connection and reads the whole archive.
    @Test
    public void cutThatShowsOnlyAtTheCloseIsTriedAgain() throws Exception {
        Path directory = WriteFileTest.directory();
        byte[] content = zip(TEXT, "a.txt");
        Files.write(directory.resolve("in.zip"), content);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            hookReads(files, "in.zip", stream -> new FilterInputStream(stream) {
                private int read;

                @Override
                public int read() throws IOException {
                    return ++read > content.length / 2 ? -1 : super.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int next = read();
                    if (next < 0) {
                        return -1;
                    }
                    bytes[offset] = (byte) next;
                    return 1;
                }

                @Override
                public void close() throws IOException {
                    super.close();
                    throw new IOException("426 Connection closed, transfer aborted");
                }
            });
            int before = pool.attempts.size();
            JsonObject result = unzip(files, "in.zip", 1);
            Assert.assertTrue(result.get("success").getAsBoolean(), result.toString());
            Assert.assertEquals(pool.attempts.size() - before, 2, pool.attempts.toString());
        }
        Assert.assertEquals(Files.readAllBytes(directory.resolve("out/a.txt")), TEXT);
    }

    private static JsonObject unzip(FilePoolFixture files, String archive, int retries) throws Exception {
        return FileTreeOperationTest.invoke(new UnzipFile(), WriteFileTest.message(files),
                "sourceFilePath", "/" + archive, "targetDirectory", "/out", "maxRetries", Integer.toString(retries));
    }

    private static String code(JsonObject result) {
        return result.getAsJsonObject("error").get("code").getAsString();
    }

    private static byte[] text() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(byte[] content, String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    // The stream of the archive fails after its first bytes. The unzip meets the file through the cache of the file
    // system, so the failure is put into the local file that the provider makes.
    private static void breakReads(FilePoolFixture files, String archive) throws Exception {
        hookReads(files, archive, UnzipCutArchiveTest::breaking);
    }

    private static void hookReads(FilePoolFixture files, String archive, UnaryOperator<InputStream> hook)
            throws Exception {
        ConnectionLease lease = files.borrow();
        DefaultFileSystemManager manager =
                (DefaultFileSystemManager) ((FileSystemHandler) lease.getConnection()).getFsManager();
        manager.removeProvider("file");
        manager.addProvider("file", new DefaultLocalFileProvider() {
            @Override
            protected FileSystem doCreateFileSystem(FileName name, FileSystemOptions options) {
                String root = ((LocalFileName) name).getRootFile();
                return new LocalFileSystem(name, root, options) {
                    @Override
                    protected FileObject createFile(AbstractFileName file) {
                        return new LocalFile(this, root, file) {
                            @Override
                            protected InputStream doGetInputStream(int bufferSize) throws Exception {
                                InputStream stream = super.doGetInputStream(bufferSize);
                                return archive.equals(file.getBaseName()) ? hook.apply(stream) : stream;
                            }
                        };
                    }
                };
            }
        });
        lease.returnToPool();
    }

    private static InputStream breaking(InputStream stream) {
        return new FilterInputStream(stream) {
            private int read;

            @Override
            public int read() throws IOException {
                if (++read > 1000) {
                    throw new IOException("The connection was lost in the middle of the read");
                }
                return super.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int next = read();
                if (next < 0) {
                    return -1;
                }
                bytes[offset] = (byte) next;
                return 1;
            }
        };
    }
}
