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
 * File Write Modes
 */
public enum FileWriteMode {

    OVERWRITE(FileWriteMode.OVERWRITE_AS_STR),
    APPEND(FileWriteMode.APPEND_AS_STR),
    CREATE_NEW(FileWriteMode.CREATE_NEW_AS_STR);

    private final String mode;

    private static final String OVERWRITE_AS_STR = "Overwrite";
    private static final String APPEND_AS_STR = "Append";
    private static final String CREATE_NEW_AS_STR = "Create New";

    FileWriteMode(String mode) {
        this.mode = mode;
    }

    /**
     * Get FileWriteMode from String value.
     *
     * @param text FileWriteMode as String
     * @return FileWriteMode
     */
    public static FileWriteMode fromString(String text) {
        for (FileWriteMode b : FileWriteMode.values()) {
            if (b.getMode().equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Get File Write mode as a string
     *
     * @return String
     */
    public String getMode() {
        return this.mode;
    }
}
