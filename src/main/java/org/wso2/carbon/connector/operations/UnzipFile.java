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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.ContentFailure;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;

public class UnzipFile extends FileOperation {
    public UnzipFile() {
        super("unzipFile", "sourceFilePath", "targetDirectory");
    }

    @Override
    void configure(Call call) {
        call.prepareGuard(null);
        call.sourceUse = OperationFrame.Use.SOURCE;
        call.retry = OperationFrame.Retry.SAFE;
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject source, JsonObject result) throws Exception {
        call.requireSource(attempt, source, call.guard());
        JsonArray names = call.flag("includeFileNames", false) ? new JsonArray() : null;
        try (FileObject folder = attempt.handler().resolveFile(call.target)) {
            if (!AtomicOps.existsNow(folder)) {
                folder.createFolder();
            }
            if ("gz".equals(source.getName().getExtension())) {
                String name = source.getName().getBaseName().replace(".gz", "");
                try (FileObject target = folder.resolveFile(name, NameScope.CHILD)) {
                    call.write(attempt, FileTreeOperation.Walk.uri(target), false, output -> {
                        try (ArchiveStream raw = new ArchiveStream(source.getContent().getInputStream());
                             InputStream input = raw.decode(() -> new GZIPInputStream(raw))) {
                            copy(raw.decoder(input), output, attempt, false);
                        }
                    });
                }
                if (names != null) {
                    names.add(name);
                }
            } else {
                String encoding = Utils.validateArchiveEncoding(call.option(Const.FILE_NAME_ENCODING), call.connectionLog::warn);
                ArchiveStream raw = new ArchiveStream(partInput(attempt, source));
                try (ZipArchiveInputStream zip = new ZipArchiveInputStream(raw, encoding, true, true)) {
                    InputStream entries = raw.decoder(zip);
                    ZipArchiveEntry entry;
                    while ((entry = raw.decode(zip::getNextZipEntry)) != null) {
                        requireHandler(attempt);
                        try (FileObject target = folder.resolveFile(entry.getName(), NameScope.DESCENDENT)) {
                            if (entry.isDirectory()) {
                                target.createFolder();
                            } else {
                                try (FileObject parent = target.getParent()) {
                                    parent.createFolder();
                                }
                                call.write(attempt, FileTreeOperation.Walk.uri(target), false,
                                        output -> copy(entries, output, attempt, false));
                                if (names != null) {
                                    names.add(entry.getName());
                                }
                            }
                        }
                    }
                }
            }
        }
        if (names != null) {
            result.add(Const.ZIP_FILE_CONTENT_ELEMENT, names);
        }
    }

    public static void extractGzip(FileObject source, FileObject target) throws IOException {
        try (InputStream input = new GZIPInputStream(source.getContent().getInputStream());
             OutputStream output = target.getContent().getOutputStream()) {
            copy(input, output, null, false);
        }
    }

    // Keeps the failure of the stream of the archive. A failure of the decoder when that stream did not fail is a
    // broken archive. It fails this operation alone and is no fault of the connection.
    private static final class ArchiveStream extends FilterInputStream {
        private IOException failure;

        ArchiveStream(InputStream source) { super(source); }

        @Override public int read() throws IOException { return source(in::read); }
        @Override public long skip(long count) throws IOException { return source(() -> in.skip(count)); }
        @Override public int available() throws IOException { return source(in::available); }

        @Override public int read(byte[] data, int offset, int length) throws IOException {
            return source(() -> in.read(data, offset, length));
        }

        @Override public void close() throws IOException {
            source(() -> {
                in.close();
                return null;
            });
        }

        private <T> T source(Read<T> read) throws IOException {
            try {
                return read.run();
            } catch (IOException error) {
                failure = error;
                throw error;
            }
        }

        <T> T decode(Read<T> read) throws IOException {
            try {
                return read.run();
            } catch (IOException error) {
                if (failure != null) {
                    throw error;
                }
                throw new ContentFailure("The archive could not be read", error);
            }
        }

        // Only the reads of the decoder pass here, so a failure of the target that is written is not changed.
        InputStream decoder(InputStream decoder) {
            return new FilterInputStream(decoder) {
                @Override public int read() throws IOException { return decode(super::read); }

                @Override public int read(byte[] data, int offset, int length) throws IOException {
                    return decode(() -> super.read(data, offset, length));
                }
            };
        }
    }

    private interface Read<T> {
        T run() throws IOException;
    }
}
