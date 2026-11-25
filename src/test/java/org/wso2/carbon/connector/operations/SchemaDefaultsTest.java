/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.connector.operations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;

public class SchemaDefaultsTest {
    private static final String CONNECTIONS = "localFile ftp ftps sftp smb";
    private static final String OPERATIONS = "checkExist compress copy createDirectory delete exploreZipFile "
            + "fetchDirectoryContent listFiles mergeFiles move read rename splitFile unzip write";

    @DataProvider(name = "defaults")
    public Object[][] defaults() {
        List<Object[]> cases = new ArrayList<>();
        add(cases, CONNECTIONS, "maxActiveConnections=8 maxIdleConnections=8 maxWaitTime=10000 "
                + "evictionCheckInterval=60000 minEvictionTime=300000 exhaustedAction=WHEN_EXHAUSTED_BLOCK "
                + "suspendOnConnectionFailure=true retriesBeforeSuspension=0 suspendInitialDuration=1000 "
                + "suspendProgressionFactor=1.0 suspendMaximumDuration=300000 streamIdleTimeout=60000 "
                + "fileCacheEnabled=false fileLockScheme=Local autoLockRelease=true lockStaleAge=900000 "
                + "tempFolderName=.part useTempFolder=true tempFileSuffix=.part maxFailureRetryCount=");
        add(cases, "sftp", "sftpConnectionTimeout= sftpSessionTimeout= sftpKeepAliveCount= "
                + "sftpPoolConnectionAgedTimeout= sftpKeyExchangeAlgorithms= sftpHostKeyAlgorithms= "
                + "sftpCiphers= sftpMacs= sftpPubkeyAcceptedAlgorithms= setAvoidPermission=true "
                + "strictHostKeyChecking=false privateKeyFilePath= privateKeyPassword= sftpPathFromRoot=false");
        add(cases, "ftp ftps", "ftpConnectionTimeout= ftpSocketTimeout= ftpDataTimeout= retryCount=5 "
                + "ftpTransferType=BINARY isPassive=true");
        add(cases, "sftp ftp ftps smb", "writeStallTimeout= userDirIsRoot=false encodePassword=false");
        add(cases, "smb", "smb2ReadTimeout= smb2WriteTimeout= smb2TransactTimeout= enableEncryption=false");
        add(cases, "ftps", "keyStorePath= keyStorePassword= keyPassword= trustStorePath= trustStorePassword=");
        add(cases, OPERATIONS, "maxRetries=1 diskShareAccessMask=MAXIMUM_ALLOWED");
        add(cases, "write read delete copy listFiles", "retryDelay=0");
        add(cases, "write", "enableLock=true lastModified= updateLastModified=true writeMode=Overwrite appendPosition=");
        add(cases, "move copy delete read", "enableLock=false");
        add(cases, "move", "createParentDirectories=false isSourceMounted= isTargetMounted= updateLastModified=true");
        add(cases, "move rename", "overwrite=false");
        add(cases, "read", "enableStreaming=false encoding=");
        add(cases, "listFiles", "includeSystemFiles=false");
        add(cases, "write copy mergeFiles unzip compress splitFile", "atomicWrite=true");
        add(cases, "copy delete compress listFiles mergeFiles move read", "minFileAge=");
        add(cases, "compress copy exploreZipFile mergeFiles move read rename splitFile unzip write", "timeBetweenSizeCheck=");
        add(cases, "splitFile", "chunkSize=");
        return cases.toArray(new Object[cases.size()][]);
    }

    private void add(List<Object[]> cases, String schemas, String settings) {
        for (String schema : schemas.split(" ")) {
            for (String setting : settings.split(" ")) {
                String[] pair = setting.split("=", -1);
                cases.add(new Object[]{schema, pair[0], pair[1]});
            }
        }
    }

    @Test(dataProvider = "defaults")
    public void uiAndTemplateExposeTheDefault(String schema, String parameter, String expected) throws Exception {
        try (InputStream json = resource("uischema/" + schema + ".json")) {
            JsonObject contents = new JsonParser().parse(new InputStreamReader(json, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> values = new ArrayList<>();
            collect(contents.getAsJsonArray("elements"), parameter, values);
            Assert.assertEquals(values.size(), 1, schema + "." + parameter);
            Assert.assertEquals(values.get(0), expected, schema + "." + parameter);
            String template = contents.has("connectionName") ? "config/init.xml" : "functions/" + schema + ".xml";
            try (InputStream xml = resource(template)) {
                NodeList parameters = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(xml).getElementsByTagName("parameter");
                int matches = 0;
                for (int index = 0; index < parameters.getLength(); index++) {
                    if (parameter.equals(parameters.item(index).getAttributes().getNamedItem("name").getNodeValue())) {
                        matches++;
                    }
                }
                Assert.assertEquals(matches, 1, template + " " + parameter);
            }
        }
    }

    @DataProvider(name = "resultFields")
    public Object[][] resultFields() {
        return new Object[][]{{"move", "success inferred targetComplete filesMoved filesLeft error"},
                {"rename", "success inferred targetComplete error"}, {"copy", "success targetComplete filesLeft error"},
                {"delete", "success numOfDeletedFiles filesLeft error"}};
    }

    @Test(dataProvider = "resultFields")
    public void outputSchemaKnowsTheResultFields(String schema, String fields) throws Exception {
        try (InputStream json = resource("outputschema/" + schema + ".json")) {
            JsonObject payload = new JsonParser().parse(new InputStreamReader(json, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("payload");
            Assert.assertTrue(payload.has("properties"), schema);
            for (String field : fields.split(" ")) {
                Assert.assertTrue(payload.getAsJsonObject("properties").has(field), schema + "." + field);
            }
        }
    }

    private InputStream resource(String name) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(name);
        Assert.assertNotNull(input, name);
        return input;
    }

    private void collect(JsonArray elements, String name, List<String> matches) {
        for (JsonElement element : elements) {
            JsonObject value = element.getAsJsonObject().getAsJsonObject("value");
            if (value.has("elements")) {
                collect(value.getAsJsonArray("elements"), name, matches);
            } else if (value.has("name") && name.equals(value.get("name").getAsString())) {
                matches.add(value.get("defaultValue").getAsString());
            }
        }
    }
}
