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

import com.google.gson.JsonObject;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;

import java.nio.file.Files;
import java.nio.file.Path;

public class CreateDirectory extends FileOperation {
    public CreateDirectory() {
        super("createDirectory", Const.DIRECTORY_PATH);
    }

    @Override
    void configure(Call call) {
        call.sourceUse = OperationFrame.Use.WRITE;
        call.retry = OperationFrame.Retry.SAFE;
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
        try {
            if (call.flag("autoCreate", false)) {
                try (FileObject parent = folder.getParent()) {
                    if (parent != null && !AtomicOps.existsNow(parent)) {
                        parent.createFolder();
                    }
                }
            }
            folder.createFolder();
        } catch (FileSystemException failure) {
            // A local type lookup below a regular file can surface as a generic NIO I/O error.
            if (folder.getName() instanceof LocalFileName
                    && ("vfs.provider/get-type.error".equals(failure.getCode())
                        || "vfs.provider/look.error".equals(failure.getCode()))
                    && failure.getCause() instanceof java.nio.file.FileSystemException) {
                String failedPath = ((java.nio.file.FileSystemException) failure.getCause()).getFile();
                for (Path path = failedPath == null ? null : Path.of(failedPath); path != null; path = path.getParent()) {
                    if (Files.isRegularFile(path)) {
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, failure.getMessage());
                    }
                }
            }
            throw failure;
        }
        String permissions = call.option("permissions");
        if (permissions != null && permissions.matches("\\d{3,4}")) {
            try {
                Utils.setFilePermissions(folder, Integer.parseInt(permissions, 8), call.connectionLog::debug);
            } catch (Exception failure) {
                attempt.handler().getContext().getHealth().namedLog().warn("Could not set directory permissions. "
                        + failure.getMessage());
            }
        }
    }
}
