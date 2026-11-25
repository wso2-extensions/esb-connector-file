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

import org.wso2.carbon.connector.utils.ContentFailure;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Keeps the failure of the stream of an archive. A failure of the decoder when that stream did not fail is a broken
 * archive. It fails the operation alone and is no fault of the connection.
 */
final class ArchiveStream extends FilterInputStream {
    interface Read<T> {
        T run() throws IOException;
    }

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
