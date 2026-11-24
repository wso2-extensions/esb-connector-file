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
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;

import java.util.Set;
import java.util.stream.Collectors;

public class HandlerCloseTest {
    @Test
    public void handlersDoNotStartExpiryTimers() throws Exception {
        try (FilePoolFixture fixture = new FilePoolFixture("LOCAL")) {
            FileSystemHandler first = fixture.factory.makeObject();
            fixture.factory.destroyObject(first);
            Set<Thread> before = timers();
            for (int count = 0; count < 100; count++) {
                FileSystemHandler handler = fixture.factory.makeObject();
                try {
                    Set<Thread> started = timers();
                    started.removeAll(before);
                    Assert.assertTrue(started.isEmpty(), started.toString());
                } finally {
                    fixture.factory.destroyObject(handler);
                }
            }
        }
    }

    private static Set<Thread> timers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "java.util.TimerThread".equals(thread.getClass().getName()))
                .collect(Collectors.toSet());
    }
}
