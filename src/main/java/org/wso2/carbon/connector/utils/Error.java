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

/**
 * Contains error codes and details
 * related to file connector
 */
public enum Error {

    CONNECTION_ERROR("700101", "FILE:CONNECTION_ERROR"),
    ILLEGAL_PATH("700102", "FILE:ILLEGAL_PATH"),
    FILE_ALREADY_EXISTS("700103", "FILE:FILE_ALREADY_EXISTS"),
    RETRY_EXHAUSTED("700104", "FILE:RETRY_EXHAUSTED"),
    ACCESS_DENIED("700105", "FILE:ACCESS_DENIED"),
    FILE_LOCKING_ERROR("700106", "FILE:FILE_LOCKING_ERROR"),
    INVALID_CONFIGURATION("700107", "FILE:INVALID_CONFIGURATION"),
    OPERATION_ERROR("700108", "FILE:OPERATION_ERROR");

    private final String code;
    private final String message;

    /**
     * Create an error code.
     *
     * @param code    error code represented by number
     * @param message error message
     */
    Error(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getErrorCode() {
        return this.code;
    }

    public String getErrorDetail() {
        return this.message;
    }
}
