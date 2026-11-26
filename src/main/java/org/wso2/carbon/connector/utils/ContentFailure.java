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
package org.wso2.carbon.connector.utils;

import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;

/**
 * A failure of the content of a file or of the message body. It fails the operation alone and is no fault of the
 * connection. Its text is the sentence itself, where the file system exception of the library takes its first
 * argument as the code of a message.
 */
public final class ContentFailure extends FileSystemException {
    private static final long serialVersionUID = 1L;

    public ContentFailure(String text, Throwable cause) {
        super(text, cause);
        setFault(Fault.APPLICATION);
    }

    @Override
    public String getMessage() {
        return getCode();
    }
}
