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
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;

public class CompressFiles extends FileOperation {
    public CompressFiles() {
        super("compress", "sourceDirectoryPath", "targetFilePath");
    }

    @Override
    void configure(Call call) {
        call.prepareGuard(null);
        call.knownOutput = true;
        call.sourceUse = OperationFrame.Use.SOURCE;
        call.retry = OperationFrame.Retry.SAFE;
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject source, JsonObject result) throws Exception {
        if (!AtomicOps.existsNow(source)) {
            throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "File or directory to compress does not exist");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        int count;
        SourceGuard guard = call.guard();
        try (FileObject target = attempt.handler().resolveFile(call.target)) {
            if (target.getName().getExtension().isEmpty()) {
                throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "Target File path does not resolve to a file");
            }
            if (source.isFolder()) {
                boolean advanced = call.option("includeFiles") != null || call.option("excludeFiles") != null
                        || call.option("fileFilterType") != null || call.option("maxFileAge") != null
                        || call.option("minFileAge") != null || call.option("subDirectoryMaxDepth") != null
                        || call.option("timeBetweenSizeCheck") != null;
                count = collect(call, attempt, source, target, guard, entries, "", 0, advanced);
            } else {
                call.requireSource(attempt, source, guard);
                entries.put(source.getName().getBaseName(), call.source);
                count = 1;
            }
        }
        call.write(attempt, call.target, false, output -> {
            try (ZipOutputStream zip = new ZipOutputStream(new CloseShieldOutputStream(output))) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    try (FileObject file = attempt.handler().resolveFile(entry.getValue());
                         InputStream input = file.getContent().getInputStream()) {
                        zip.putNextEntry(new ZipEntry(entry.getKey()));
                        copy(input, zip, attempt, false);
                        zip.closeEntry();
                    }
                }
            }
        });
        result.addProperty("NumberOfFilesAdded", count);
    }

    private int collect(Call call, Attempt attempt, FileObject folder, FileObject target, SourceGuard guard,
                        Map<String, String> entries, String prefix, int depth, boolean advanced) throws Exception {
        int count = 0;
        if (advanced && call.depth() >= 0 && depth >= call.depth()) {
            return count;
        }
        for (FileObject child : folder.getChildren()) {
            try (FileObject file = child) {
                String name = prefix + file.getName().getBaseName();
                if (file.getName().equals(target.getName())) {
                    continue;
                }
                if (file.isFolder()) {
                    if (!guard.reservedDirectory(file)) {
                        if (!advanced) {
                            count++;
                        }
                        if (call.flag("includeSubDirectories", true)) {
                            count += collect(call, attempt, file, target, guard, entries, name + "/", depth + 1, advanced);
                        }
                    }
                } else if (guard.accepts(file)) {
                    String path = FileTreeOperation.Walk.uri(file);
                    if (attempt.guardMember(path) == OperationFrame.Member.READY) {
                        entries.put(name, path);
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
