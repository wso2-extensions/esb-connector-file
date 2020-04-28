/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.connector.integrationTest.FileConnector;

import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.connector.integration.test.base.ConnectorIntegrationTestBase;
import org.wso2.connector.integration.test.base.RestResponse;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration test class for file connector
 */
public class FileConnectorIntegrationTest extends ConnectorIntegrationTestBase {

    private final Map<String, String> esbRequestHeadersMap = new HashMap<String, String>();

    /**
     * Set up the environment.
     */
    @BeforeClass(alwaysRun = true)
    public void setEnvironment() throws Exception {
        String connectorName = System.getProperty("connector_name") + "-connector-" +
                System.getProperty("connector_version") + ".zip";
        init(connectorName);
        getApiConfigProperties();
        esbRequestHeadersMap.put("Accept-Charset", "UTF-8");
        esbRequestHeadersMap.put("Content-Type", "application/json");
        esbRequestHeadersMap.put("Accept", "application/json");
        connectorProperties.put("filePath", getFilePath("out/createFile.txt"));
        connectorProperties.put("source", getFilePath("in/sampleText.txt"));
        connectorProperties.put("splitFile", getFilePath("in/splitFile.csv"));
        connectorProperties.put("splitFileXml", getFilePath("in/products.xml"));
        connectorProperties.put("writeTo", getFilePath("out/merge/"));
        connectorProperties.put("address", getFilePath("in/sendFile.txt"));
        connectorProperties.put("archiveFileLocation", getFilePath("out/sampleText.zip"));
        connectorProperties.put("copyFrom", getFilePath("in"));
        connectorProperties.put("copyTo", getFilePath("out"));
        connectorProperties.put("appendFile", getFilePath("in/appendFile.txt"));
        connectorProperties.put("destination", getFilePath("out"));
        connectorProperties.put("moveFrom", getFilePath("in/moveFile.txt"));
        connectorProperties.put("moveTo", getFilePath("out/moveFile.txt"));
        connectorProperties.put("nonExistingSource", getFilePath("out/nonExistingFile.txt"));
        connectorProperties.put("nonExistingDestination", getFilePath("out/nonExistingFile.txt"));
        connectorProperties.put("targetPath", getFilePath("out"));
        connectorProperties.put("archiveDestination", getFilePath("out/test" +
                ".zip"));
    }

    public static String getFilePath(String fileName) {
        if (StringUtils.isNotBlank(fileName)) {
            return Paths
                    .get(System.getProperty("framework.resource.location"), "sampleFiles", fileName)
                    .toString();
        }
        return null;
    }

    /**
     * Positive test case for create file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector create File/Folder integration test")
    public void testCreateFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:create");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileCreateMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for create file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector create File/Folder integration test"
            + " with Negative parameters")
    public void testCreateFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:create");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileCreateMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for append file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector append file integration test")
    public void testAppendFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:append");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileAppendMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Positive test case for delete file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector delete file integration test",
            dependsOnMethods = {"testisFileExistFile", "testReadFile"})
    public void testDeleteFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:delete");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileDeleteMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for delete file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector delete file integration test with "
            + "Negative parameters")
    public void testDeleteFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:delete");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileDeleteMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for copy file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector copy file integration test",
            dependsOnMethods = {"testisFileExistFile"})
    public void testCopyFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:copy");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileCopyMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for copy file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector copy file integration test with  " +
            "Negative parameters")
    public void testCopyFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:copy");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileCopyMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for read specific lines of a file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read specific lines of a file integration test",
                    dependsOnMethods = {"testCreateFile"})
    public void testReadSpecifiedLines() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:readSpecifiedLines");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileReadBetweenLinesMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for read specific lines of a file method.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read specific lines of a file integration test with " +
            "Negative parameter", dependsOnMethods = {"testCreateFile"})
    public void testReadSpecifiedLinesWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:readSpecifiedLines");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileReadBetweenLinesNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for read a specific line of a file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read a specific line of a file integration test",
            dependsOnMethods = {"testCreateFile"})
    public void testReadALine() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:readALine");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileReadALineMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for read a specific line of a file method.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read a specific line of a file integration test with " +
            "Negative parameter", dependsOnMethods = {"testCreateFile"})
    public void testReadALineWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:readALine");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileReadALineNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for get size of a file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test",
            dependsOnMethods = {"testCreateFile"})
    public void testGetSizeMandatory() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:getSize");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "getFileSizeMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for get size  of a file method.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test with " +
            "Negative parameter", dependsOnMethods = {"testCreateFile"})
    public void testGetSizeWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:getSize");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "getFileSizeNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for get last modified of a file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test",
            dependsOnMethods = {"testCreateFile"})
    public void testGetLastModifiedTimeMandatory() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:getLastModifiedTime");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "getLastModifiedTimeMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for get last modified time  of a file method.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test with " +
            "Negative parameter", dependsOnMethods = {"testCreateFile"})
    public void testGetLastModifiedTimeWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:getLastModifiedTime");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "getLastModifiedTimeNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for read file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test",
            dependsOnMethods = {"testCreateFile"})
    public void testReadFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:read");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileReadMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for read file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test with " +
            "Negative parameter", dependsOnMethods = {"testCreateFile"})
    public void testReadFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:read");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileReadMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for split file method based on chunk size.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test",
            dependsOnMethods = {"testCreateFile"})
    public void testSplitFileWithChunkSize() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:splitFile");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "SplitFileWithChunkSize.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Positive test case for split file method based on number of lines.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test",
            dependsOnMethods = {"testCreateFile"})
    public void testSplitFileWithLineNumbers() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:splitFile");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "SplitFileWithLineNumbers.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Positive test case for split file method based on xpath expression.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector splitFile integration test")
    public void testSplitFileWithXPathExpression() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:splitFile");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "SplitFileWithXPathExpression.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for read file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector read file integration test with " +
            "Negative parameter", dependsOnMethods = {"testCreateFile"})
    public void testSplitFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:splitFile");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "SplitFileNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for archives file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector archive file integration test")
    public void testArchiveFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:archive");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileArchiveMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for archives file method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector archive file integration test with "
            + "Negative parameters")
    public void testArchiveFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:archive");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileArchiveMandatoryNegative.json");
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("false"));
    }

    /**
     * Positive test case for unzip method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector unZip file integration test",
            dependsOnMethods ={"testArchiveFile", "testListFileZip"} )
    public void testUnZipFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:unzip");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileUnzipMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for unzip method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector unZip file integration test with " +
            "Negative parameters")
    public void testUnZipFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:unzip");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileUnzipMandatoryNegative.json");
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("false"));
    }

    /**
     * Positive test case for fileExist method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector isFileExist file integration test")
    public void testisFileExistFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:isFileExist");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileExistMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for fileExist method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector isFileExist file integration " +
            "test with Negative parameters ")
    public void testisFileExistFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:isFileExist");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileExistMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for listFileZip method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector listFileZip file integration test",
            dependsOnMethods ={"testArchiveFile"})
    public void testListFileZip() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:listFileZip");
        RestResponse<JSONObject> esbRestResponse = sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                "FileListZipMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for listFileZip method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector listFileZip file integration " +
            "test with Negative Parameters  ")
    public void testListFileZipWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:listFileZip");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileListZipMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for move method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector move file integration test", dependsOnMethods = {"testReadFile"})
    public void testMoveFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:move");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileMoveMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
        esbRequestHeadersMap.put("Action", "urn:move");
        sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileMoveBack.json");
    }

    /**
     * Negative test case for move method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector move file integration test with " +
            "Negative parameters")
    public void testMoveFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:move");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileMoveMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for search method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector search file integration test")
    public void testSearchFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:search");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileSearchMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
    }

    /**
     * Negative test case for search method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector search file integration test with "
            + "Negative parameters")
    public void testSearchFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:search");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileSearchMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for ftp over proxy method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector ftpOverProxy file integration test")
    public void testFtpOverProxy() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:ftpOverProxy");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileFtpOverProxyMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for ftp over proxy method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector ftpOverProxy file integration test")
    public void testFtpOverProxyWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:ftpOverProxy");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileFtpOverProxyMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

    /**
     * Positive test case for send method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector VFS Send file integration test")
    public void testSendFile() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:send");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileSendMandatory.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 200);
        Assert.assertEquals(true, esbRestResponse.getBody().toString().contains("true"));
    }

    /**
     * Negative test case for send method with mandatory parameters.
     */
    @Test(groups = {"wso2.esb"}, description = "FileConnector VFS Send file integration test with " +
            "Negative parameters")
    public void testSendFileWithNegativeCase() throws Exception {
        esbRequestHeadersMap.put("Action", "urn:send");
        RestResponse<JSONObject> esbRestResponse =
                sendJsonRestRequest(proxyUrl, "POST", esbRequestHeadersMap,
                        "FileSendMandatoryNegative.json");
        Assert.assertEquals(esbRestResponse.getHttpStatusCode(), 202);
    }

}
