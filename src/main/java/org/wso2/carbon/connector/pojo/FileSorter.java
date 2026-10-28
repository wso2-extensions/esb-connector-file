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

package org.wso2.carbon.connector.pojo;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.carbon.connector.utils.Const;

import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Sorts files by an attribute (name, size or last modified time) in ascending or descending order.
 * Files with the same attribute value are ordered by name, so the result is always deterministic.
 */
public class FileSorter {

    private static final Log log = LogFactory.getLog(FileSorter.class);
    private static final String LOG_PREFIX = "[" + Const.CONNECTOR_NAME + "]";
    public static final String SORT_ATTRIB_NAME = "Name";
    public static final String SORT_ATTRIB_SIZE = "Size";
    public static final String SORT_ATTRIB_MODIFIED_TIME = "LastModifiedTime";
    public static final String SORT_ORDER_ASCENDING = "Ascending";
    public static final String SORT_ORDER_DESCENDING = "Descending";

    private static final Comparator<FileObject> BY_NAME =
            Comparator.comparing((FileObject file) -> file.getName().getBaseName())
                    .thenComparing(FileObject::getName);

    private final String sortAttribute;
    private final boolean isAscending;

    public FileSorter(String sortAttribute, String sortOrder) {

        if (SORT_ATTRIB_NAME.equals(sortAttribute) || SORT_ATTRIB_SIZE.equals(sortAttribute)
                || SORT_ATTRIB_MODIFIED_TIME.equals(sortAttribute)) {
            this.sortAttribute = sortAttribute;
        } else {
            log.warn(LOG_PREFIX + "Unknown sort attribute " + sortAttribute + ". Sorting by " + SORT_ATTRIB_NAME);
            this.sortAttribute = SORT_ATTRIB_NAME;
        }
        if (SORT_ORDER_DESCENDING.equals(sortOrder)) {
            this.isAscending = false;
        } else {
            if (!SORT_ORDER_ASCENDING.equals(sortOrder)) {
                log.warn(LOG_PREFIX + "Unknown sort order " + sortOrder + ". Sorting in " + SORT_ORDER_ASCENDING
                        + " order");
            }
            this.isAscending = true;
        }
    }

    /**
     * Sorts the given files in place.
     *
     * @param input Files to sort
     */
    public void sort(FileObject[] input) {

        Comparator<FileObject> comparator;
        switch (sortAttribute) {
            case SORT_ATTRIB_SIZE:
                comparator = byAttribute(input, file -> file.getContent().getSize());
                break;
            case SORT_ATTRIB_MODIFIED_TIME:
                comparator = byAttribute(input, file -> file.getContent().getLastModifiedTime());
                break;
            default:
                comparator = BY_NAME;
                break;
        }
        Arrays.sort(input, isAscending ? comparator : comparator.reversed());
    }

    /**
     * Reads the attribute of each file once, so the comparison stays consistent during the sort. A file whose
     * attribute cannot be read (e.g. a folder has no size) is treated as having the smallest value.
     */
    private static Comparator<FileObject> byAttribute(FileObject[] input, AttributeReader reader) {

        Map<FileObject, Long> values = new IdentityHashMap<>();
        for (FileObject file : input) {
            long value;
            try {
                value = reader.read(file);
            } catch (FileSystemException e) {
                if (log.isDebugEnabled()) {
                    log.debug(LOG_PREFIX + "Unable to read the sort attribute of " + file.getName().getBaseName(), e);
                }
                value = Long.MIN_VALUE;
            }
            values.put(file, value);
        }
        return Comparator.comparingLong((FileObject file) -> values.get(file)).thenComparing(BY_NAME);
    }

    @FunctionalInterface
    private interface AttributeReader {

        long read(FileObject file) throws FileSystemException;
    }
}
