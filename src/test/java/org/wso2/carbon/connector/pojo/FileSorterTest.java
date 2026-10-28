/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.connector.pojo;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Unit tests of the file sorter (local file system).
 */
public class FileSorterTest {

    private static FileSystemManager manager;
    private Path dir;

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
        dir = Files.createTempDirectory("filesorter-test");
    }

    @AfterMethod
    public void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void write(String name, int size, long modifiedTime) throws IOException {
        Path file = dir.resolve(name);
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) 'x');
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedTime));
    }

    /** Sorts the children of the test folder and returns their names. */
    private List<String> sort(String attribute, String order) throws IOException {
        FileObject[] children = vfs().resolveFile(dir.toUri().toString()).getChildren();
        new FileSorter(attribute, order).sort(children);
        List<String> names = new ArrayList<>();
        for (FileObject child : children) {
            names.add(child.getName().getBaseName());
        }
        return names;
    }

    @Test(description = "Name uses case-sensitive string order")
    public void sortsByName() throws IOException {
        write("part-10.csv", 1, 1000L);
        write("part-2.csv", 1, 1000L);
        write("B.csv", 1, 1000L);
        write("a.csv", 1, 1000L);
        Assert.assertEquals(sort("Name", "Ascending"), Arrays.asList("B.csv", "a.csv", "part-10.csv", "part-2.csv"));
        Assert.assertEquals(sort("Name", "Descending"), Arrays.asList("part-2.csv", "part-10.csv", "a.csv", "B.csv"));
    }

    @Test(description = "Size orders by size and orders files of the same size by name")
    public void sortsBySizeWithNameTieBreak() throws IOException {
        write("c.csv", 5, 1000L);
        write("b.csv", 1, 1000L);
        write("a.csv", 5, 1000L);
        Assert.assertEquals(sort("Size", "Ascending"), Arrays.asList("b.csv", "a.csv", "c.csv"));
        Assert.assertEquals(sort("Size", "Descending"), Arrays.asList("c.csv", "a.csv", "b.csv"));
    }

    @Test(description = "LastModifiedTime orders by time and orders files with the same time by name")
    public void sortsByLastModifiedTimeWithNameTieBreak() throws IOException {
        write("c.csv", 1, 2000L);
        write("b.csv", 1, 3000L);
        write("a.csv", 1, 2000L);
        Assert.assertEquals(sort("LastModifiedTime", "Ascending"), Arrays.asList("a.csv", "c.csv", "b.csv"));
    }

    @Test(description = "a folder has no size: it is ordered first instead of breaking the sort")
    public void sortsFoldersBySizeWithoutFailing() throws IOException {
        write("b.csv", 3, 1000L);
        write("a.csv", 1, 1000L);
        Files.createDirectory(dir.resolve("folder"));
        Assert.assertEquals(sort("Size", "Ascending"), Arrays.asList("folder", "a.csv", "b.csv"));
    }

    @Test(description = "an unknown sort order is ascending, an unknown sort attribute sorts by name")
    public void fallsBackToNameAscending() throws IOException {
        write("b.csv", 1, 1000L);
        write("a.csv", 5, 1000L);
        Assert.assertEquals(sort("Size", "Sideways"), Arrays.asList("b.csv", "a.csv"));
        Assert.assertEquals(sort("Colour", "Ascending"), Arrays.asList("a.csv", "b.csv"));
        Assert.assertEquals(sort("Size", null), Arrays.asList("b.csv", "a.csv"));
    }
}
