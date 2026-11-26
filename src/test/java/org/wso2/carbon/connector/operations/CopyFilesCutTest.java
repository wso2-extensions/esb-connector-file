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
import org.apache.synapse.MessageContext;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A copy reads the source and writes the temp file on two clients of one connection. When a cut stops the read, the
 * connection is aborted before the two streams close, so their closes fail at once and the cut is detected once.
 */
public class CopyFilesCutTest {

    @Test
    public void transportErrorOfTheReadAbortsTheConnectionBeforeTheStreamsClose() throws Exception {
        Path dir = WriteFileTest.directory();
        Files.createDirectories(dir.resolve("in"));
        Files.writeString(dir.resolve("in/a.csv"), "A");
        // For each attempt, whether its manager was still alive when the source stream closed.
        List<Boolean> aliveAtClose = new ArrayList<>();
        CopyFiles operation = new CopyFiles() {
            @Override
            Walk operation(MessageContext message) throws InvalidConfigurationException {
                return new Copy(message) {
                    @Override
                    void change(Attempt attempt, FileObject source, FileObject target, String path,
                            String targetPath) throws Exception {
                        if (attempt.number > 1) {
                            super.change(attempt, source, target, path, targetPath);
                            return;
                        }
                        DefaultFileSystemManager manager =
                                (DefaultFileSystemManager) attempt.handler().getFsManager();
                        super.change(attempt, cutOnRead(source, () -> aliveAtClose.add(manager.isAliveLocally())),
                                target, path, targetPath);
                    }
                };
            }
        };
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", dir.toString())) {
            JsonObject result = FileTreeOperationTest.invoke(operation, WriteFileTest.message(files),
                    "sourcePath", "/in/a.csv", "targetPath", "/out", "createParentDirectories", "true");
            Assert.assertEquals(aliveAtClose, List.of(false), "The source stream closed before the abort");
            Assert.assertFalse(result.has("error"), result.toString());
        }
        Assert.assertEquals(Files.readString(dir.resolve("in/a.csv")), "A");
        Assert.assertEquals(Files.readString(dir.resolve("out/a.csv")), "A");
    }

    // A source whose read fails as a lost connection. Its close runs the given check first.
    private static FileObject cutOnRead(FileObject source, Runnable atClose) {
        return new DecoratedFileObject(source) {
            @Override
            public FileContent getContent() throws FileSystemException {
                FileContent content = super.getContent();
                return (FileContent) Proxy.newProxyInstance(FileContent.class.getClassLoader(),
                        new Class<?>[]{FileContent.class}, (proxy, method, args) -> {
                            if (method.getName().equals("getInputStream")) {
                                return new InputStream() {
                                    @Override
                                    public int read() throws FileSystemException {
                                        FileSystemException cut = new FileSystemException("Source connection lost");
                                        cut.setFault(Fault.TRANSPORT);
                                        throw cut;
                                    }

                                    @Override
                                    public void close() {
                                        atClose.run();
                                    }
                                };
                            }
                            try {
                                return method.invoke(content, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }
        };
    }
}
