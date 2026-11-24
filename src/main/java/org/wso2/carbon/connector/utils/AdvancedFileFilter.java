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
import org.wso2.org.apache.commons.vfs2.FileFilter;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSelectInfo;
import org.wso2.org.apache.commons.vfs2.FileSystemException;

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
    private final long maxFileAgeMillis;
    private final long minFileAgeMillis;
    private Pattern includeRegex;
    private Pattern excludeRegex;

    public AdvancedFileFilter(String filterType, String includePattern, String excludePattern, String maxFileAge) {
        this(filterType, includePattern, excludePattern, maxFileAge, null);
    }

    public AdvancedFileFilter(String filterType, String includePattern, String excludePattern,
                              String maxFileAge, String minFileAge) {
        this.filterType = StringUtils.isEmpty(filterType) ? "regex" : filterType.toLowerCase();
        this.includePattern = includePattern;
        this.excludePattern = excludePattern;
        
        this.maxFileAgeMillis = duration(maxFileAge);
        this.minFileAgeMillis = duration(minFileAge);

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

    private static long duration(String age) {
        if (StringUtils.isEmpty(age)) {
            return -1;
        }
        TimeUnit unit;
        switch (Character.toLowerCase(age.charAt(age.length() - 1))) {
            case 'd': unit = TimeUnit.DAYS; break;
            case 'h': unit = TimeUnit.HOURS; break;
            case 'm': unit = TimeUnit.MINUTES; break;
            case 's': unit = TimeUnit.SECONDS; break;
            default: unit = TimeUnit.MILLISECONDS;
        }
        String value = unit == TimeUnit.MILLISECONDS ? age : age.substring(0, age.length() - 1);
        try {
            return unit.toMillis(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public boolean accept(FileSelectInfo fileSelectInfo) throws FileSystemException {
        return accepts(fileSelectInfo.getFile());
    }

    // A failed read of the file is thrown, so that the caller can tell a transport error from a file that is gone.
    boolean accepts(FileObject file) throws FileSystemException {
        String fileName = file.getName().getBaseName();
        
        // Check include pattern first
        if (includeRegex != null && !includeRegex.matcher(fileName).matches()) {
            return false;
        }
        
        // Check exclude pattern
        if (excludeRegex != null && excludeRegex.matcher(fileName).matches()) {
            return false;
        }
        
        // Check file age if specified and file is a regular file
        if ((maxFileAgeMillis > 0 || minFileAgeMillis > 0) && file.isFile()) {
            long fileLastModified = file.getContent().getLastModifiedTime();
            long currentTime = System.currentTimeMillis();
            long fileAge = currentTime - fileLastModified;
            
            if (maxFileAgeMillis > 0 && fileAge > maxFileAgeMillis
                    || minFileAgeMillis > 0 && fileAge <= minFileAgeMillis) {
                return false;
            }
        }
        
        return true;
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
