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
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.wso2.carbon.connector.utils.Const;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Contains comparators used to compare
 * files and sort
 */
public class FileSorter {

    private static final Log log = LogFactory.getLog(FileSorter.class);
    private static final String LOG_PREFIX = "[" + Const.CONNECTOR_NAME + "]";
    private static final String SORT_ATTRIB_NAME = "Name";
    private static final String SORT_ATTRIB_SIZE = "Size";
    private static final String SORT_ATTRIB_MODIFIED_TIME = "LastModifiedTime";
    private static final String SORT_ORDER_ASCENDING = "Ascending";
    private static final String SORT_ORDER_DESCENDING = "Descending";


    private String sortAttribute;
    private boolean isAscending;

    public FileSorter(String sortAttribute, String sortOder) {
        this.sortAttribute = sortAttribute;
        if (sortOder.equals(SORT_ORDER_ASCENDING)) {
            this.isAscending = true;
        } else if (sortOder.equals(SORT_ORDER_DESCENDING)) {
            this.isAscending = false;
        } else {
            log.error(LOG_PREFIX + "Unknown Sort order. Default = " + SORT_ORDER_ASCENDING);
        }
    }

    public void sort(FileObject[] input) {
        switch (this.sortAttribute) {
            case SORT_ATTRIB_NAME:
                sortByName(input);
                break;
            case SORT_ATTRIB_SIZE:
                sortBySize(input);
                break;
            case SORT_ATTRIB_MODIFIED_TIME:
                sortByLastModifiedTime(input);
                break;
            default:
                log.error("Unknown Sort Attribute " + sortAttribute);
                break;
        }
    }

    private void sortByName(FileObject[] input) {
        if (isAscending) {
            Arrays.sort(input, new FileNameAscComparator());
        } else {
            Arrays.sort(input, new FileNameDesComparator());
        }
    }

    private void sortBySize(FileObject[] input) {
        if (isAscending) {
            Arrays.sort(input, new FileSizeAscComparator());
        } else {
            Arrays.sort(input, new FileSizeDesComparator());
        }
    }

    private void sortByLastModifiedTime(FileObject[] input) {
        if (isAscending) {
            Arrays.sort(input, new TimeStampAscComparator());
        } else {
            Arrays.sort(input, new TimeStampDesComparator());
        }
    }


    //By Name

    class FileNameAscComparator implements Comparator<FileObject> {
        public int compare(FileObject o1, FileObject o2) {
            return o1.getName().compareTo(o2.getName());
        }
    }

    class FileNameDesComparator implements Comparator<FileObject> {
        public int compare(FileObject o1, FileObject o2) {
            return o2.getName().compareTo(o1.getName());
        }
    }

    //By Size

    class FileSizeAscComparator implements Comparator<FileObject> {
        public int compare(FileObject o1, FileObject o2) {
            long lDiff = 0L;
            try {
                lDiff = o1.getContent().getSize() - o2.getContent().getSize();
            } catch (FileSystemException e) {
                //ignore as it is expected when comparing folders
            }
            return (int) lDiff;
        }
    }

    class FileSizeDesComparator implements Comparator<FileObject> {
        public int compare(FileObject o1, FileObject o2) {
            long lDiff = 0L;
            try {
                lDiff = o2.getContent().getSize() - o1.getContent().getSize();
            } catch (FileSystemException e) {
                //ignore as it is expected when comparing folders
            }
            return (int) lDiff;
        }
    }

    class TimeStampAscComparator implements Comparator<FileObject> {
        public int compare(FileObject o1, FileObject o2) {
            try {
                long time1 = o1.getContent().getLastModifiedTime();
                long time2 = o2.getContent().getLastModifiedTime();
                return Long.compare(time1, time2);
            } catch (FileSystemException e) {
                log.warn(LOG_PREFIX + "Unable to compare lastmodified timestamp of the two files.", e);
            }
            return 0;
        }
    }

    class TimeStampDesComparator implements Comparator<FileObject> {
        public int compare(FileObject o1, FileObject o2) {
            try {
                long time1 = o1.getContent().getLastModifiedTime();
                long time2 = o2.getContent().getLastModifiedTime();
                return Long.compare(time2, time1);
            } catch (FileSystemException e) {
                log.warn(LOG_PREFIX + "Unable to compare lastModified timestamp of the two files.", e);
            }
            return 0;
        }
    }

}
