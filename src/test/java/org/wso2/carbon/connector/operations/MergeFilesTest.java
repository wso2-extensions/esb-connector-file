/*
 * Copyright (c) 2026, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.pojo.MergeFileResult;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Unit tests of the merge itself (local file system): the order of the merged files and the append flag of the target stream.
 */
public class MergeFilesTest {

    private static FileSystemManager manager;
    private Path dir;

    /** A manager with the local file provider only: the test needs no other protocol. */
    private static synchronized FileSystemManager vfs() throws FileSystemException {
        if (manager == null) {
            DefaultFileSystemManager m = new DefaultFileSystemManager();
            m.addProvider("file", new DefaultLocalFileProvider());
            m.init();
            manager = m;
        }
        return manager;
    }

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("mergefiles-test");
        Files.createDirectory(dir.resolve("source"));
    }

    @AfterMethod
    public void tearDown() throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static FileSorter byName() {
        return new FileSorter(FileSorter.SORT_ATTRIB_NAME, FileSorter.SORT_ORDER_ASCENDING);
    }

    private String merged() throws IOException {
        return new String(Files.readAllBytes(dir.resolve("target.csv")), StandardCharsets.UTF_8);
    }

    private void write(String name, String text) throws IOException {
        Files.write(dir.resolve("source").resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private FileObject[] children() throws IOException {
        // the order the file system hands them over is deliberately reversed: the merge must not depend on it
        FileObject[] c = vfs().resolveFile(dir.resolve("source").toUri().toString()).getChildren();
        List<FileObject> l = new ArrayList<>(java.util.Arrays.asList(c));
        l.sort(Comparator.comparing((FileObject f) -> f.getName().getBaseName()).reversed());
        return l.toArray(new FileObject[0]);
    }

    private FileObject target(String initial) throws IOException {
        Path t = dir.resolve("target.csv");
        Files.write(t, initial.getBytes(StandardCharsets.UTF_8));
        return vfs().resolveFile(t.toUri().toString());
    }

    /** Wraps the target so the append flag of getOutputStream(boolean) is recorded. */
    private static FileObject recording(FileObject real, List<Boolean> appendFlags) {
        FileContent content = (FileContent) Proxy.newProxyInstance(MergeFilesTest.class.getClassLoader(), new Class[]{FileContent.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOutputStream") && args != null && args.length == 1 && args[0] instanceof Boolean) {
                        appendFlags.add((Boolean) args[0]);
                    }
                    try {
                        return method.invoke(real.getContent(), args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return (FileObject) Proxy.newProxyInstance(MergeFilesTest.class.getClassLoader(), new Class[]{FileObject.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getContent") && (args == null || args.length == 0)) {
                        return content;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test(description = "files are merged in the order of their names, whatever order the file system returns")
    public void mergesInNameOrder() throws IOException {
        write("p0000002.csv", "C\n");
        write("header.csv", "H\n");
        write("p0000001.csv", "B\n");
        write("ignored.txt", "X\n");
        FileObject target = target("");
        MergeFileResult result = new MergeFiles().mergeFiles(target, "(header|p[0-9]{7})\\.csv", null, children(), false,
                byName());
        Assert.assertEquals(new String(Files.readAllBytes(dir.resolve("target.csv")), StandardCharsets.UTF_8), "H\nB\nC\n");
        Assert.assertEquals(result.getNumberOfMergedFiles(), 3);
    }

    @Test(description = "a new or re-created target (Overwrite) is opened without the append flag")
    public void overwriteOpensWithoutAppendFlag() throws IOException {
        write("a.csv", "A\n");
        List<Boolean> flags = new ArrayList<>();
        new MergeFiles().mergeFiles(recording(target(""), flags), null, null, children(), false, byName());
        Assert.assertEquals(flags, java.util.Collections.singletonList(false));
        Assert.assertEquals(new String(Files.readAllBytes(dir.resolve("target.csv")), StandardCharsets.UTF_8), "A\n");
    }

    @Test(description = "an existing target in Append mode is opened with the append flag and keeps what is already there")
    public void appendKeepsTheExistingContent() throws IOException {
        write("a.csv", "A\n");
        List<Boolean> flags = new ArrayList<>();
        new MergeFiles().mergeFiles(recording(target("OLD\n"), flags), null, null, children(), true, byName());
        Assert.assertEquals(flags, java.util.Collections.singletonList(true));
        Assert.assertEquals(new String(Files.readAllBytes(dir.resolve("target.csv")), StandardCharsets.UTF_8), "OLD\nA\n");
    }

    @Test(description = "the advanced filter merges in the order of the file names as well")
    public void advancedFilterMergesInNameOrder() throws IOException {
        write("p0000002.csv", "C\n");
        write("header.csv", "H\n");
        write("p0000001.csv", "B\n");
        write("ignored.txt", "X\n");
        FileObject target = target("");
        AdvancedFileFilter filter = new AdvancedFileFilter("regex", "(header|p[0-9]{7})\\.csv", null, null);
        MergeFileResult result = new MergeFiles().mergeFilesWithAdvancedFilter(target, filter, null, children(), false,
                byName());
        Assert.assertEquals(new String(Files.readAllBytes(dir.resolve("target.csv")), StandardCharsets.UTF_8), "H\nB\nC\n");
        Assert.assertEquals(result.getNumberOfMergedFiles(), 3);
    }

    @Test(description = "Descending reverses the merge order")
    public void mergesInDescendingOrder() throws IOException {
        write("a.csv", "A\n");
        write("b.csv", "B\n");
        write("c.csv", "C\n");
        new MergeFiles().mergeFiles(target(""), null, null, children(), false,
                new FileSorter(FileSorter.SORT_ATTRIB_NAME, FileSorter.SORT_ORDER_DESCENDING));
        Assert.assertEquals(merged(), "C\nB\nA\n");
    }
}
