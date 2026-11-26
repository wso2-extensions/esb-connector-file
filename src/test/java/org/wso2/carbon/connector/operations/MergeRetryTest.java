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
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeRetryTest {

    @DataProvider
    public Object[][] readFailures() {
        return new Object[][] {
                {false, "a", 2, "AB"}, {true, "a", 2, "oldAB"},
                {false, "b", 2, "AB"}, {true, "b", 1, "oldA"}
        };
    }

    @Test(dataProvider = "readFailures")
    public void sourceReadFailureRetriesOnlyWhileReplayIsSafe(boolean existing, String failedChild,
            int expectedAttempts, String expectedBytes) throws Exception {
        Path root = WriteFileTest.directory();
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("a"), "A");
        Files.writeString(source.resolve("b"), "B");
        Path target = root.resolve("joined");
        if (existing) {
            Files.writeString(target, "old");
        }
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger faults = new AtomicInteger();
        MergeFiles operation = new MergeFiles() {
            @Override
            void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
                attempts.incrementAndGet();
                FileObject input = attempt.number == 1 ? new DecoratedFileObject(folder) {
                    @Override
                    public FileObject[] getChildren() throws FileSystemException {
                        FileObject[] children = super.getChildren();
                        for (int i = 0; i < children.length; i++) {
                            if (children[i].getName().getBaseName().equals(failedChild)) {
                                children[i] = failOnOpen(children[i], () -> {
                                    faults.incrementAndGet();
                                    if (!existing) {
                                        Assert.assertFalse(Files.exists(target), "Partial merge became visible");
                                    }
                                });
                            }
                        }
                        return children;
                    }
                } : folder;
                super.perform(call, attempt, input, result);
            }
        };
        JsonObject result = run(root, operation);
        if (expectedAttempts == 1) {
            Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), "700101");
        } else {
            Assert.assertEquals(result.getAsJsonObject("detail").get("totalWrittenBytes").getAsInt(), 2);
        }
        Assert.assertEquals(faults.get(), 1);
        Assert.assertEquals(attempts.get(), expectedAttempts);
        Assert.assertEquals(Files.readString(target), expectedBytes);
    }

    private static FileObject failOnOpen(FileObject child, Runnable verify) {
        return new DecoratedFileObject(child) {
            @Override
            public FileContent getContent() throws FileSystemException {
                FileContent content = super.getContent();
                return (FileContent) Proxy.newProxyInstance(FileContent.class.getClassLoader(),
                        new Class<?>[]{FileContent.class}, (proxy, method, args) -> {
                            if (method.getName().equals("getInputStream")) {
                                verify.run();
                                FileSystemException failure = new FileSystemException("Source connection lost");
                                failure.setFault(Fault.TRANSPORT);
                                throw failure;
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

    @Test
    public void uncertainFirstPublicationDoesNotReplayAsAppend() throws Exception {
        Path root = WriteFileTest.directory();
        Files.writeString(Files.createDirectory(root.resolve("source")).resolve("a"), "A");
        AtomicInteger attempts = new AtomicInteger();
        MergeFiles operation = new MergeFiles() {
            @Override
            void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
                attempts.incrementAndGet();
                super.perform(call, attempt, folder, result);
                Assert.assertEquals(call.retry(), OperationFrame.Retry.CREATE_NEW);
                // The local provider returned success. Model a lost reply at the commit boundary,
                // restoring the flag cleared by the successful member completion.
                attempt.commitCalled();
                throw new UnclearResultException("Lost publication reply", null, MovePhase.COMMIT, null, false);
            }
        };
        JsonObject result = run(root, operation);
        Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), "700111");
        Assert.assertEquals(attempts.get(), 1);
        Assert.assertEquals(Files.readString(root.resolve("joined")), "A");
    }

    private static JsonObject run(Path root, MergeFiles operation) throws Exception {
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL", "workingDir", root.toString(),
                "fileLockScheme", "Cluster", "suspendOnConnectionFailure", "false")) {
            return FileTreeOperationTest.invoke(operation, WriteFileTest.message(fixture),
                    "sourceDirectoryPath", "/source", "targetFilePath", "/joined", "writeMode", "Append",
                    "atomicWrite", "true", "maxRetries", "1");
        }
    }
}
