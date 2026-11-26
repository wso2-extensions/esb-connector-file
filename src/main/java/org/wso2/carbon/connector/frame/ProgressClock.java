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
package org.wso2.carbon.connector.frame;

import org.wso2.carbon.connector.utils.Const;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.log.UriMask;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ProgressClock {
    private final String connection;
    private final String operation;
    private final LongSupplier nanos;
    private long last;

    ProgressClock(String connection, String operation, LongSupplier nanos) {
        this.connection = connection;
        this.operation = operation;
        this.nanos = nanos;
        reset("start");
    }

    public void reset(String cause) {
        last = nanos.getAsLong();
        if (TestPoints.enabled()) {
            TestPoints.event("progress.reset", connection, "connection=" + Const.CONNECTOR_NAME + ":" + UriMask.mask(connection), "op=" + operation,
                    "cause=" + UriMask.mask(cause));
        }
    }

    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - last);
    }
}
