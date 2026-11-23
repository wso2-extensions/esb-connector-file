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
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;

public class ExploreZipFile extends FileOperation {
    public ExploreZipFile() {
        super("exploreZipFile", "zipFilePath");
    }

    @Override
    void configure(Call call) {
        call.prepareGuard(null);
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject source, JsonObject result) throws Exception {
        if (!AtomicOps.existsNow(source)) {
            throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "The zip file does not exist");
        }
        if (!call.guard().stable(source)) {
            throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The zip file is not stable");
        }
        String encoding = Utils.validateArchiveEncoding(call.option(Const.FILE_NAME_ENCODING), call.connectionLog::warn);
        JsonArray names = new JsonArray();
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(source.getContent().getInputStream(),
                encoding, true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextZipEntry()) != null) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        result.add(Const.ZIP_FILE_CONTENT_ELEMENT, names);
    }
}
