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
 * File Split Modes
 */
public enum FileSplitMode {

    CHUNK_SIZE(FileSplitMode.CHUNK_SIZE_STR),
    LINE_COUNT(FileSplitMode.LINE_COUNT_STR),
    XPATH_EXPRESSION(FileSplitMode.XPATH_EXPRESSION_STR);

    private final String mode;

    private static final String CHUNK_SIZE_STR = "Chunk Size";
    private static final String LINE_COUNT_STR = "Line Count";
    private static final String XPATH_EXPRESSION_STR = "XPATH Expression";

    FileSplitMode(String mode) {
        this.mode = mode;
    }


    /**
     * Get FileSplitMode from String value.
     *
     * @param text FileSplitMode as String
     * @return FileSplitMode
     */
    public static FileSplitMode fromString(String text) {
        for (FileSplitMode b : FileSplitMode.values()) {
            if (b.getMode().equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Get File Spit mode as a string
     * @return String
     */
    public String getMode() {
        return this.mode;
    }
}
