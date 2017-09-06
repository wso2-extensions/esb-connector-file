/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.connector.util;

import org.apache.axiom.attachments.SizeAwareDataSource;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Data source that reads data from a VFS {@link FileObject}.
 * This class is similar to VFS' own FileObjectDataSource implementation, but in addition
 * implements {@link SizeAwareDataSource}.
 * @since 2.0.9
 */
public class FileObjectDataSource implements SizeAwareDataSource {
    private final FileObject file;
    private final String contentType;

    public FileObjectDataSource(FileObject file, String contentType) {
        this.file = file;
        this.contentType = contentType;
    }

    /**
     * Get the file size.
     *
     * @return Size of the file.
     */
    public long getSize() {
        try {
            return file.getContent().getSize();
        } catch (FileSystemException ex) {
            return -1;
        }
    }

    /**
     * Get the content type.
     *
     * @return Content type.
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Get the name of the file.
     *
     * @return The name of the file
     */
    public String getName() {
        return file.getName().getURI();
    }

    /**
     * Get the input stream.
     *
     * @return Input stream.
     * @throws IOException
     */
    public InputStream getInputStream() throws IOException {
        return file.getContent().getInputStream();
    }

    /**
     * Get the output stream.
     *
     * @return Output stream.
     * @throws IOException
     */
    public OutputStream getOutputStream() throws IOException {
        return file.getContent().getOutputStream();
    }
}
