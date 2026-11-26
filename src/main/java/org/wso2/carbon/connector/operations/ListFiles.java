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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileContentInfo;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;

public class ListFiles extends FileOperation {
    public ListFiles() {
        super("listFiles", Const.DIRECTORY_PATH);
    }

    @Override
    void configure(Call call) {
        String format = call.option("responseFormat", "Hierarchical");
        call.flatListing = "Flat".equals(format);
        if (!call.flatListing && !"Hierarchical".equals(format)) {
            throw new IllegalArgumentException("Unknown responseFormat found " + format);
        }
        call.prepareGuard("matchingPattern");
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject folder, JsonObject result) throws Exception {
        call.requireFolder(folder);
        Listing listing = new Listing(call);
        JsonObject directory = listing.walk(folder, 0, call.flatListing);
        if (call.flatListing) {
            JsonObject files = new JsonObject();
            files.add("files", listing.entries);
            result.add("files", files);
        } else {
            result.add("directory", directory);
        }
    }

    private static final class Listing {
        final FileSorter sorter;
        final SourceGuard guard;
        final boolean includeSystem;
        final boolean attributes;
        final boolean recursive;
        final int maxDepth;
        final JsonArray entries = new JsonArray();

        Listing(Call call) {
            sorter = call.sorter();
            guard = call.guard();
            includeSystem = call.flag("includeSystemFiles", false);
            attributes = call.flag("appendFileAttributes", false);
            recursive = call.flag("recursive", false);
            maxDepth = call.depth();
        }

        JsonObject walk(FileObject folder, int depth, boolean flat) throws Exception {
            JsonObject directory = new JsonObject();
            directory.addProperty("name", folder.getName().getBaseName());
            JsonArray files = new JsonArray();
            JsonArray directories = new JsonArray();
            FileObject[] children = folder.getChildren();
            sorter.sort(children);
            for (FileObject child : children) {
                try (FileObject entry = child) {
                    if (entry.isFolder()) {
                        if (recursive && (maxDepth < 0 || depth < maxDepth)
                                && (includeSystem || !guard.reservedDirectory(entry))) {
                            directories.add(walk(entry, depth + 1, flat));
                        }
                    } else if (guard.accepts(entry, includeSystem)) {
                        if (flat || attributes) {
                            JsonObject data = new JsonObject();
                            FileName name = entry.getName();
                            data.addProperty("name", name.getBaseName());
                            if (flat) {
                                String path = name instanceof LocalFileName
                                        ? ((LocalFileName) name).getRootFile() + name.getPath() : name.getPath();
                                data.addProperty("path", path);
                            }
                            if (attributes) {
                                FileContent content = entry.getContent();
                                data.addProperty(Const.SIZE_ELEMENT, content.getSize());
                                data.addProperty(Const.LAST_MODIFIED_TIME_ELEMENT, content.getLastModifiedTime());
                                FileContentInfo info = content.getContentInfo();
                                if (info != null) {
                                    data.addProperty(Const.CONTENT_TYPE_ELEMENT, info.getContentType());
                                    data.addProperty(Const.CONTENT_ENCODING_ELEMENT, info.getContentEncoding());
                                }
                            }
                            (flat ? entries : files).add(data);
                        } else {
                            files.add(entry.getName().getBaseName());
                        }
                    }
                }
            }
            if (files.size() != 0) {
                directory.add(Const.FILE_ELEMENT, files);
            }
            if (directories.size() != 0) {
                directory.add("directory", directories);
            }
            return directory;
        }
    }
}
