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

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

import org.apache.commons.logging.LogFactory;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class SourceGuardTest {
    @DataProvider
    public Object[][] sizeChecks() {
        return new Object[][] {{5L, false, true}, {6L, false, false}, {5L, true, false}};
    }

    @Test(dataProvider = "sizeChecks")
    public void sizeCheckNeedsTwoSuccessfulEqualObservations(long second, boolean unavailable, boolean stable)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        ClassLoader loader = getClass().getClassLoader();
        FileName name = (FileName) Proxy.newProxyInstance(loader, new Class<?>[]{FileName.class},
                (o, method, args) -> method.getName().equals("getBaseName") ? "data" : "file:///test/data");
        FileContent content = (FileContent) Proxy.newProxyInstance(loader, new Class<?>[]{FileContent.class},
                (o, method, args) -> {
                    if (!method.getName().equals("getSize")) {
                        throw new AssertionError(method);
                    }
                    if (calls.incrementAndGet() == 1) {
                        return 5L;
                    }
                    Assert.assertEquals(refreshes.get(), 1);
                    if (unavailable) {
                        throw new FileSystemException("The size is unavailable");
                    }
                    return second;
                });
        FileObject file = (FileObject) Proxy.newProxyInstance(loader, new Class<?>[]{FileObject.class},
                (o, method, args) -> {
                    switch (method.getName()) {
                        case "getName": return name;
                        case "isFile": return true;
                        case "getContent": return content;
                        case "refresh": refreshes.incrementAndGet(); return null;
                        default: throw new AssertionError(method);
                    }
                });
        SourceGuard guard = new SourceGuard(Pattern.compile("data"),
                new AdvancedFileFilter(null, null, null, null), 1, ".part", ".part", null);
        Assert.assertEquals(guard.accepts(file), stable);
        Assert.assertEquals(calls.get(), 2);
    }

    // A file whose size and time can not be read, with the fault of the given kind.
    private static FileObject unreadable(Fault fault) {
        ClassLoader loader = SourceGuardTest.class.getClassLoader();
        FileName name = (FileName) Proxy.newProxyInstance(loader, new Class<?>[]{FileName.class},
                (o, method, args) -> method.getName().equals("getBaseName") ? "data" : "file:///test/data");
        FileContent content = (FileContent) Proxy.newProxyInstance(loader, new Class<?>[]{FileContent.class},
                (o, method, args) -> {
                    FileSystemException failure = new FileSystemException("The look failed");
                    failure.setFault(fault);
                    throw failure;
                });
        return (FileObject) Proxy.newProxyInstance(loader, new Class<?>[]{FileObject.class},
                (o, method, args) -> {
                    switch (method.getName()) {
                        case "getName": return name;
                        case "isFile": return true;
                        case "getContent": return content;
                        case "refresh": return null;
                        default: throw new AssertionError(method);
                    }
                });
    }

    @Test
    public void transportErrorOfALookIsThrownAndAnyOtherFailedLookIsNotAccepted() throws Exception {
        ErrorClassifier classifier = new ErrorClassifier(
                NamedLog.connection(LogFactory.getLog(SourceGuardTest.class), "guard", new RepeatLimiter()));
        SourceGuard sized = new SourceGuard(Pattern.compile("data"), new AdvancedFileFilter(null, null, null, null),
                1, ".part", ".part", classifier);
        SourceGuard aged = new SourceGuard(Pattern.compile("data"), new AdvancedFileFilter(null, null, null, "1d"),
                0, ".part", ".part", classifier);
        for (SourceGuard guard : new SourceGuard[] {sized, aged}) {
            try {
                guard.accepts(unreadable(Fault.TRANSPORT));
                Assert.fail("A transport error of the look is thrown");
            } catch (FileSystemException expected) {
                // The attempt ends on it.
            }
            Assert.assertFalse(guard.accepts(unreadable(Fault.APPLICATION)));
        }
    }
}
