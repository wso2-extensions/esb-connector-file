/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.connector.utils;

import org.apache.commons.lang.StringUtils;
import org.wso2.org.apache.commons.vfs2.FileSelectInfo;
import org.wso2.org.apache.commons.vfs2.FileSelector;
import org.wso2.org.apache.commons.vfs2.FileSystemException;

import java.util.concurrent.TimeUnit;

/**
 * Advanced file selector supporting multiple filtering criteria including
 * Ant-style patterns, regex patterns, include/exclude filters, file age filtering,
 * and file stability checking for copy operations.
 */
public class AdvancedFileSelector implements FileSelector {

    private AdvancedFileFilter advancedFilter;
    private long fileSizeCheckInterval;
    
    public AdvancedFileSelector(String filterType, String includePattern, String excludePattern, 
                               String maxFileAge, String sizeCheckInterval) {
        this.advancedFilter = new AdvancedFileFilter(filterType, includePattern, excludePattern, maxFileAge);
        
        // Parse file size check interval if provided
        if (!StringUtils.isEmpty(sizeCheckInterval)) {
            try {
                this.fileSizeCheckInterval = Long.parseLong(sizeCheckInterval);
            } catch (NumberFormatException e) {
                this.fileSizeCheckInterval = 0; // No stability check
            }
        } else {
            this.fileSizeCheckInterval = 0; // No stability check
        }
    }

    @Override
    public boolean includeFile(FileSelectInfo fileSelectInfo) throws FileSystemException {
        // Use the advanced filter for basic include/exclude/age filtering
        if (!advancedFilter.accept(fileSelectInfo)) {
            return false;
        }
        
        // If file stability check is enabled and this is a file (not directory)
        if (fileSizeCheckInterval > 0 && fileSelectInfo.getFile().isFile()) {
            return isFileStable(fileSelectInfo);
        }
        
        return true;
    }

    @Override
    public boolean traverseDescendents(FileSelectInfo fileSelectInfo) throws FileSystemException {
        // Always traverse directories for complete filtering
        return fileSelectInfo.getFile().isFolder();
    }
    
    /**
     * Check if file is stable (not being written to) by comparing file sizes
     * over a specified interval.
     * 
     * @param fileSelectInfo File selection info
     * @return true if file is stable, false if still being written
     */
    private boolean isFileStable(FileSelectInfo fileSelectInfo) throws FileSystemException {
        try {
            long initialSize = fileSelectInfo.getFile().getContent().getSize();
            
            // Wait for the specified interval
            Thread.sleep(fileSizeCheckInterval);
            
            // Re-read file size and compare
            long finalSize = fileSelectInfo.getFile().getContent().getSize();
            
            // File is stable if size hasn't changed
            return initialSize == finalSize;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // If interrupted, assume file is stable
            return true;
        } catch (Exception e) {
            // If we can't check stability, assume file is stable
            return true;
        }
    }
}
