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
package org.wso2.carbon.connector.operations;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

final class FrameUnitEvidence {
    private FrameUnitEvidence() { }

    static final class Checks {
        private final java.util.List<String> errors = new java.util.ArrayList<>();

        void assertEquals(Object actual, Object expected) {
            if (!java.util.Objects.equals(actual, expected)) {
                fail("Expected " + expected + ", observed " + actual);
            }
        }

        void fail(String message) { errors.add(message); }

        void assertAll() {
            if (!errors.isEmpty()) {
                throw new AssertionError(errors.toString());
            }
        }
    }

    static void write(String execution, Object observations) throws IOException {
        String path = System.getenv("FILE_UNIT_OBSERVATIONS");
        if (path != null && System.getProperty("connector.unit.run") != null) {
            JsonObject report = new JsonObject();
            report.addProperty("run", System.getProperty("connector.unit.run"));
            report.addProperty("execution", execution);
            java.net.URL core = org.wso2.integration.connector.core.connection.ConnectionHandler.class
                    .getProtectionDomain().getCodeSource().getLocation();
            try (java.io.InputStream input = core.openStream()) {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    digest.update(buffer, 0, count);
                }
                report.addProperty("loadedCoreSHA256",
                        new String(org.apache.commons.codec.binary.Hex.encodeHex(digest.digest())));
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new AssertionError(error);
            }
            report.add("observations", observations instanceof JsonElement
                    ? (JsonElement) observations : new Gson().toJsonTree(observations));
            Files.write(Paths.get(path), report.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
        }
    }
}
