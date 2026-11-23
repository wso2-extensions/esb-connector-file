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

import com.google.gson.Gson;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.integration.connector.core.pool.ConnectionPool;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FilesCache;
import org.wso2.org.apache.commons.vfs2.cache.LRUFilesCache;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class FileCacheWriteTest {
    private static final int NAMES = 20000;
    private static final String CONTENT = "0123456789abcdef";

    @Test
    public void realWriteEvictsAndReleasesUniqueNames() throws Exception {
        List<Cell> cells = Arrays.asList(measure(false), measure(true));
        String report = System.getenv("FILE_UNIT_OBSERVATIONS");
        if (report != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run", System.getProperty("connector.unit.run"));
            data.put("cells", cells);
            Files.write(Paths.get(report), new Gson().toJson(data).getBytes(StandardCharsets.UTF_8));
        }
        for (Cell cell : cells) {
            boolean enabled = cell.setting.equals("true");
            Assert.assertEquals(cell.writes, NAMES);
            Assert.assertEquals(cell.uniqueNames, NAMES);
            Assert.assertEquals(cell.endedLeases, NAMES);
            Assert.assertEquals(cell.detached, NAMES);
            Assert.assertTrue(cell.sentinelCleared);
            Assert.assertTrue(cell.retained <= (enabled ? 100 : 0), "Retained targets: " + cell.retained);
            Assert.assertEquals(cell.cache, enabled ? "LRUFilesCache" : "NullFilesCache");
            Assert.assertTrue(cell.sizes.stream().allMatch(size -> size <= (enabled ? 100 : 0)),
                    "Largest cache: " + java.util.Collections.max(cell.sizes));
            Assert.assertTrue(cell.firstAbsentAt101);
        }
    }

    private static final class Cell {
        String setting;
        String scheme;
        String cache;
        int writes;
        int uniqueNames;
        final int bytesPerWrite = CONTENT.length();
        int endedLeases;
        int detached;
        int retained;
        int collections;
        boolean sentinelCleared;
        boolean firstAbsentAt101;
        final List<Integer> sizes = new ArrayList<>(NAMES);
    }

    // Observe the target object on its first resolve. The installed cache still performs every cache operation.
    private static final class Observation {
        String expected;
        FileObject target;

        FilesCache observe(FilesCache cache) {
            return (FilesCache) Proxy.newProxyInstance(FilesCache.class.getClassLoader(),
                    new Class<?>[]{FilesCache.class}, (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(cache, args);
                            if (target == null && args != null && args.length > 0 && args[0] instanceof FileObject) {
                                FileObject file = (FileObject) args[0];
                                if (file.getName().getBaseName().equals(expected)) {
                                    target = file;
                                }
                            }
                            return value;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    private static Cell measure(boolean enabled) throws Exception {
        Path folder = WriteFileTest.directory();
        Cell cell = new Cell();
        cell.setting = enabled ? "true" : "unset";
        List<WeakReference<FileObject>> targets = new ArrayList<>(NAMES);
        String[] settings = enabled ? new String[]{"workingDir", folder.toString(), "fileCacheEnabled", "true"}
                : new String[]{"workingDir", folder.toString()};
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", settings)) {
            ConnectionLease setup = files.borrow();
            DefaultFileSystemManager manager = (DefaultFileSystemManager) FilePoolFixture.handler(setup).getFsManager();
            ConnectionPool pool = FilePoolFixture.pool(setup);
            setup.returnToPool();
            FilesCache cache = manager.getFilesCache();
            Assert.assertEquals(cache.getClass(), enabled ? LRUFilesCache.class : NullFilesCache.class);
            cell.cache = cache.getClass().getSimpleName();
            Field slot = DefaultFileSystemManager.class.getDeclaredField("filesCache");
            slot.setAccessible(true);
            Observation observation = new Observation();
            slot.set(manager, observation.observe(cache));
            try {
                Axis2MessageContext message = WriteFileTest.message(files);
                FileName first = null;
                Map<?, ?> systems = enabled ? (Map<?, ?>) FilePoolFixture.field(cache, LRUFilesCache.class,
                        "fileSystemCache") : null;
                for (int number = 0; number < NAMES; number++) {
                    String name = "n" + number + ".txt";
                    observation.expected = name;
                    WriteFileTest.write(message, name, CONTENT);
                    FileObject target = observation.target;
                    Assert.assertNotNull(target, name);
                    targets.add(new WeakReference<>(target));
                    if (!target.isAttached() && !target.isContentOpen()) {
                        cell.detached++;
                    }
                    if (pool.getNumActive() == 0 && pool.getNumIdle() == 1) {
                        cell.endedLeases++;
                    }
                    if (first == null) {
                        first = target.getName();
                        cell.scheme = first.getScheme();
                    }
                    FileSystem fs = target.getFileSystem();
                    cell.sizes.add(enabled ? ((Map<?, ?>) systems.get(fs)).size()
                            : (cache.getFile(fs, target.getName()) == null ? 0 : 1));
                    if (number == 100) {
                        cell.firstAbsentAt101 = cache.getFile(fs, first) == null;
                    }
                    Assert.assertEquals(Files.readAllBytes(folder.resolve(name)), CONTENT.getBytes(StandardCharsets.UTF_8));
                    cell.writes++;
                    observation.target = null;
                }
                try (Stream<Path> listed = Files.list(folder)) {
                    cell.uniqueNames = (int) listed.filter(Files::isRegularFile).count();
                }
                WeakReference<Object> sentinel = new WeakReference<>(new Object());
                while (cell.collections < 10 && sentinel.get() != null) {
                    System.gc();
                    Thread.sleep(30);
                    cell.collections++;
                }
                cell.sentinelCleared = sentinel.get() == null;
                cell.retained = (int) targets.stream().filter(ref -> ref.get() != null).count();
            } finally {
                slot.set(manager, cache);
            }
        } finally {
            try (Stream<Path> listed = Files.walk(folder)) {
                for (Path entry : (Iterable<Path>) listed.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(entry);
                }
            }
        }
        return cell;
    }
}
