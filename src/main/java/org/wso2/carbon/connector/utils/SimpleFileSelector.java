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

import org.apache.commons.vfs2.FileSelectInfo;

import java.util.regex.Pattern;

/**
 * Case insensitive Java regex based file selector.
 */
public class SimpleFileSelector implements org.apache.commons.vfs2.FileSelector {

    private Pattern pattern;

    /**
     * Create case insensitive Java regex based file selector.
     *
     * @param patternStr Pattern as a Regex expression
     */
    public SimpleFileSelector(String patternStr) {
        this.pattern = Pattern.compile(patternStr);
    }

    @Override
    public boolean includeFile(FileSelectInfo fileSelectInfo) throws Exception {
        String fileName = fileSelectInfo.getFile().getName().getBaseName();
        return pattern.matcher(fileName).matches();
    }

    @Override
    public boolean traverseDescendents(FileSelectInfo fileSelectInfo) throws Exception {
        return true;
    }
}
