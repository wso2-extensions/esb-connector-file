/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.connector.exception;

import org.apache.commons.vfs2.FileSystemException;

/**
 * Exception thrown when a connection is suspended.
 * This is a lightweight exception to avoid excessive stack trace logging.
 */
public class ConnectionSuspendedException extends FileSystemException {

    public ConnectionSuspendedException(String connectionName, long remainingTime) {
        super(String.format("Connection '%s' is suspended for %d more milliseconds",
                           connectionName, remainingTime));
    }

    public ConnectionSuspendedException(String connectionName, String reason) {
        super(String.format("Connection '%s' failed: %s", connectionName, reason));
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // Override to avoid expensive stack trace generation for expected failures
        return this;
    }
}
