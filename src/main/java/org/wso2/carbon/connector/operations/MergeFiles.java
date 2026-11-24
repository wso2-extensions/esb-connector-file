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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.pojo.MergeFileResult;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;

public class MergeFiles extends FileOperation {
    public MergeFiles() {
        super("mergeFiles", "sourceDirectoryPath", "targetFilePath");
    }

    @Override
    void configure(Call call) {
        call.prepareGuard("filePattern");
        call.knownOutput = true;
        call.prepareParentBeforeLock = !Const.OVERWRITE.equals(call.option("writeMode"));
        call.retry = call.prepareParentBeforeLock ? OperationFrame.Retry.APPEND : OperationFrame.Retry.SAFE;
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
        call.requireFolder(folder);
        try (FileObject target = attempt.handler().resolveFile(call.target)) {
            SourceGuard guard = call.guard();
            FileObject[] children = folder.getChildren();
            call.sorter().sort(children);
            List<FileObject> selected = new ArrayList<>();
            for (FileObject child : children) {
                if (!child.getName().equals(target.getName()) && guard.accepts(child)
                        && attempt.guardMember(FileTreeOperation.Walk.uri(child)) == OperationFrame.Member.READY) {
                    selected.add(child);
                }
            }
            boolean appendMode = !Const.OVERWRITE.equals(call.option("writeMode"));
            if (appendMode) {
                if (!attempt.hasShare(call.target)) {
                    call.beforeWholeLock(attempt, call.target);
                }
                attempt.lockTarget(call.target, OperationFrame.Lock.WHOLE);
            }
            boolean appendExisting = appendMode && AtomicOps.existsNow(target);
            boolean writesDirectly = appendMode && (appendExisting || !call.flag("atomicWrite", true));
            if (appendMode) {
                // A retry after the first publication would now append the same source bytes.
                call.retry = writesDirectly ? OperationFrame.Retry.APPEND : OperationFrame.Retry.CREATE_NEW;
            }
            JsonObject detail = new JsonObject();
            call.write(attempt, call.target, appendExisting, output -> {
                MergeFileResult merged = merge(output, selected, attempt, writesDirectly);
                detail.addProperty("numberOfMergedFiles", merged.getNumberOfMergedFiles());
                detail.addProperty("totalWrittenBytes", merged.getNumberOfTotalWrittenBytes());
            });
            result.add("detail", detail);
        }
    }

    private MergeFileResult merge(OutputStream output, List<FileObject> selected, Attempt attempt, boolean append)
            throws IOException {
        int files = 0;
        long total = 0;
        for (FileObject file : selected) {
            try (InputStream input = file.getContent().getInputStream()) {
                long bytes = copy(input, output, attempt, append);
                if (bytes != 0) {
                    files++;
                    total += bytes;
                }
            }
        }
        return new MergeFileResult(files, total);
    }

    MergeFileResult mergeFiles(FileObject target, String pattern, String interval, FileObject[] children,
                               boolean append, FileSorter sorter) throws IOException {
        return mergeSelected(target, pattern == null ? null : Pattern.compile(pattern),
                new AdvancedFileFilter(null, null, null, null), interval, children, append, sorter);
    }

    MergeFileResult mergeFilesWithAdvancedFilter(FileObject target, AdvancedFileFilter filter, String interval,
                                                FileObject[] children, boolean append, FileSorter sorter)
            throws IOException {
        return mergeSelected(target, null, filter, interval, children, append, sorter);
    }

    private MergeFileResult mergeSelected(FileObject target, Pattern pattern, AdvancedFileFilter filter, String interval,
                                          FileObject[] children, boolean append, FileSorter sorter) throws IOException {
        SourceGuard guard = new SourceGuard(pattern, filter, interval == null ? 0 : Long.parseLong(interval),
                ".part", ".part", null);
        FileObject[] sorted = children.clone();
        sorter.sort(sorted);
        List<FileObject> selected = new ArrayList<>();
        for (FileObject child : sorted) {
            if (!child.getName().equals(target.getName()) && guard.accepts(child)) {
                selected.add(child);
            }
        }
        try (OutputStream output = target.getContent().getOutputStream(append)) {
            return merge(output, selected, null, append);
        }
    }
}
