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
import org.apache.commons.vfs2.FileFilter;
import org.apache.commons.vfs2.FileSelectInfo;
import org.apache.commons.vfs2.FileSystemException;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Advanced file filter supporting multiple filtering criteria including
 * Ant-style patterns, regex patterns, include/exclude filters, and file age filtering.
 */
public class AdvancedFileFilter implements FileFilter {

    private String filterType;
    private String includePattern;
    private String excludePattern;
    private long maxFileAgeMillis;
    private Pattern includeRegex;
    private Pattern excludeRegex;

    public AdvancedFileFilter(String filterType, String includePattern, String excludePattern, String maxFileAge) {
        this.filterType = StringUtils.isEmpty(filterType) ? "regex" : filterType.toLowerCase();
        this.includePattern = includePattern;
        this.excludePattern = excludePattern;
        
        // Parse max file age if provided
        if (!StringUtils.isEmpty(maxFileAge)) {
            try {
                // Assume maxFileAge is in milliseconds, but support time units
                if (maxFileAge.toLowerCase().endsWith("d")) {
                    this.maxFileAgeMillis = TimeUnit.DAYS.toMillis(Long.parseLong(maxFileAge.substring(0, maxFileAge.length() - 1)));
                } else if (maxFileAge.toLowerCase().endsWith("h")) {
                    this.maxFileAgeMillis = TimeUnit.HOURS.toMillis(Long.parseLong(maxFileAge.substring(0, maxFileAge.length() - 1)));
                } else if (maxFileAge.toLowerCase().endsWith("m")) {
                    this.maxFileAgeMillis = TimeUnit.MINUTES.toMillis(Long.parseLong(maxFileAge.substring(0, maxFileAge.length() - 1)));
                } else {
                    this.maxFileAgeMillis = Long.parseLong(maxFileAge);
                }
            } catch (NumberFormatException e) {
                this.maxFileAgeMillis = -1; // Invalid age, ignore filter
            }
        } else {
            this.maxFileAgeMillis = -1; // No age filter
        }

        // Compile patterns based on filter type
        if ("regex".equals(this.filterType)) {
            if (!StringUtils.isEmpty(includePattern)) {
                this.includeRegex = Pattern.compile(includePattern);
            }
            if (!StringUtils.isEmpty(excludePattern)) {
                this.excludeRegex = Pattern.compile(excludePattern);
            }
        } else if ("ant".equals(this.filterType)) {
            // Convert Ant-style patterns to regex
            if (!StringUtils.isEmpty(includePattern)) {
                this.includeRegex = Pattern.compile(antToRegex(includePattern));
            }
            if (!StringUtils.isEmpty(excludePattern)) {
                this.excludeRegex = Pattern.compile(antToRegex(excludePattern));
            }
        }
    }

    @Override
    public boolean accept(FileSelectInfo fileSelectInfo) {
        try {
            String fileName = fileSelectInfo.getFile().getName().getBaseName();
            
            // Check include pattern first
            if (includeRegex != null && !includeRegex.matcher(fileName).matches()) {
                return false;
            }
            
            // Check exclude pattern
            if (excludeRegex != null && excludeRegex.matcher(fileName).matches()) {
                return false;
            }
            
            // Check file age if specified and file is a regular file
            if (maxFileAgeMillis > 0 && fileSelectInfo.getFile().isFile()) {
                long fileLastModified = fileSelectInfo.getFile().getContent().getLastModifiedTime();
                long currentTime = System.currentTimeMillis();
                long fileAge = currentTime - fileLastModified;
                
                if (fileAge > maxFileAgeMillis) {
                    return false; // File is too old
                }
            }
            
            return true;
            
        } catch (FileSystemException e) {
            // If we can't read file properties, exclude it
            return false;
        }
    }

    /**
     * Convert Ant-style pattern to regex pattern
     * 
     * @param antPattern Ant-style pattern
     * @return Regex pattern string
     */
    private String antToRegex(String antPattern) {
        // Handle multiple patterns separated by comma
        if (antPattern.contains(",")) {
            String[] patterns = antPattern.split(",");
            StringBuilder regexBuilder = new StringBuilder("(");
            for (int i = 0; i < patterns.length; i++) {
                if (i > 0) regexBuilder.append("|");
                regexBuilder.append(antToRegexSingle(patterns[i].trim()));
            }
            regexBuilder.append(")");
            return regexBuilder.toString();
        } else {
            return antToRegexSingle(antPattern);
        }
    }

    /**
     * Convert single Ant-style pattern to regex
     * 
     * @param pattern Single Ant pattern
     * @return Regex pattern
     */
    private String antToRegexSingle(String pattern) {
        StringBuilder regex = new StringBuilder();
        
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        // ** matches any number of directories
                        regex.append(".*");
                        i++; // Skip next *
                    } else {
                        // * matches any characters except directory separator
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '^':
                case '$':
                case '{':
                case '}':
                case '[':
                case ']':
                case '|':
                case '\\':
                    // Escape regex special characters
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }
        
        return regex.toString();
    }
}
