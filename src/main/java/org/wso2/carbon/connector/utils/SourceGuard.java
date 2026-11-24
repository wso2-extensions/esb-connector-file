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
package org.wso2.carbon.connector.utils;

import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.ReservedNames;

import java.util.regex.Pattern;

public final class SourceGuard {
    private final Pattern pattern;
    private final AdvancedFileFilter filter;
    private final long interval;
    private final String tempFolder;
    private final String tempSuffix;
    // Tells a transport error of a look from any other. Without one every failed look reads as not accepted.
    private final ErrorClassifier classifier;

    public SourceGuard(Pattern pattern, AdvancedFileFilter filter, long interval, String tempFolder, String tempSuffix,
                       ErrorClassifier classifier) {
        this.classifier = classifier;
        this.pattern = pattern;
        this.filter = filter;
        this.interval = interval;
        this.tempFolder = tempFolder;
        this.tempSuffix = tempSuffix;
    }

    public boolean reservedDirectory(FileObject folder) {
        return ReservedNames.isReserved(folder.getName().getBaseName(), true, tempFolder, tempSuffix);
    }

    public boolean accepts(FileObject file) throws FileSystemException {
        return accepts(file, false);
    }

    public boolean accepts(FileObject file, boolean includeSystemFiles) throws FileSystemException {
        String name = file.getName().getBaseName();
        return file.isFile() && (includeSystemFiles || !ReservedNames.isReserved(name, false, tempFolder, tempSuffix))
                && (pattern == null || pattern.matcher(name).matches()) && passes(file) && stable(file);
    }

    private boolean passes(FileObject file) throws FileSystemException {
        try {
            return filter.accepts(file);
        } catch (FileSystemException failure) {
            if (transport(failure)) {
                throw failure;
            }
            return false;
        }
    }

    // A transport error ends the operation like any other call. Any other failed look means the file is not taken.
    private boolean transport(FileSystemException failure) {
        return classifier != null
                && classifier.classify(failure, 0, ErrorClassifier.CallType.READ).getKind().isTransport();
    }

    public boolean stable(FileObject file) throws FileSystemException {
        if (interval <= 0) {
            return true;
        }
        try {
            long first = file.getContent().getSize();
            if (TestPoints.enabled()) {
                TestPoints.hold("sourceguard.between-size-reads", LockIo.pointKey(file.getName().getURI()));
            }
            Thread.sleep(interval);
            file.refresh();
            return first == file.getContent().getSize();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (FileSystemException unavailable) {
            if (transport(unavailable)) {
                throw unavailable;
            }
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
