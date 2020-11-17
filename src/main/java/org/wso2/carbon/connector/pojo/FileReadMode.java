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

/**
 * File read modes
 */
public enum FileReadMode {

    COMPLETE_FILE(FileReadMode.COMPLETE_FILE_STR),
    STARTING_FROM_LINE(FileReadMode.STARTING_FROM_LINE_STR),
    UP_TO_LINE(FileReadMode.UP_TO_LINE_STR),
    BETWEEN_LINES(FileReadMode.BETWEEN_LINES_STR),
    SPECIFIC_LINE(FileReadMode.SPECIFIC_LINE_STR),
    METADATA_ONLY(FileReadMode.METADATA_ONLY_STR);

    private final String mode;

    private static final String COMPLETE_FILE_STR = "Complete File";
    private static final String STARTING_FROM_LINE_STR = "Starting From Line";
    private static final String UP_TO_LINE_STR = "Up To Line";
    private static final String BETWEEN_LINES_STR = "Between Lines";
    private static final String SPECIFIC_LINE_STR = "Specific Line";
    private static final String METADATA_ONLY_STR = "Metadata Only";

    FileReadMode(String mode) {
        this.mode = mode;
    }

    /**
     * Get FileReadMode from String value.
     *
     * @param text FileReadMode as String
     * @return FileReadMode
     */
    public static FileReadMode fromString(String text) {
        for (FileReadMode b : FileReadMode.values()) {
            if (b.getMode().equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Get File Read mode as a string
     *
     * @return String
     */
    public String getMode() {
        return this.mode;
    }

}
