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

import com.google.gson.JsonObject;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.util.SecurityManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileSplitMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements Split file operation
 */
public class SplitFile extends AbstractConnectorOperation {

    private static final int ENTITY_EXPANSION_LIMIT = 0;
    private static final String SOURCE_FILE_PATH_PARAM = "sourceFilePath";
    private static final String TARGET_DIRECTORY_PARAM = "targetDirectory";
    private static final String SPLIT_MODE_PARAM = "splitMode";
    private static final String CHUNK_SIZE_PARAM = "chunkSize";
    private static final String LINE_COUNT_PARAM = "lineCount";
    private static final String XPATH_EXPRESSION_PARAM = "xpathExpression";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String NUMBER_OF_SPLITS_ELE_NAME = "numberOfSplits";
    private static final String LOG_IDENTIFIER = "File Connector:splitFile";
    private static final String OPERATION_NAME = "splitFile";
    private static final String ERROR_MESSAGE = "Error while performing file:split for file ";

    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String sourceFilePath = null;
        FileObject fileToSplit = null;
        String targetDirectoryPath;
        FileObject targetDir;
        FileOperationResult result;

        FileSplitMode splitMode;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;

        try {
            String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                    (messageContext, Const.DISK_SHARE_ACCESS_MASK);
            sourceFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SOURCE_FILE_PATH_PARAM);
            targetDirectoryPath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TARGET_DIRECTORY_PARAM);

            String splitModeAsStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, SPLIT_MODE_PARAM);
            String timeBetweenSizeCheck = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, TIME_BETWEEN_SIZE_CHECK);

            if(StringUtils.isNotEmpty(splitModeAsStr)) {
                splitMode = FileSplitMode.fromString(splitModeAsStr);
                if(splitMode == null) {
                    throw new InvalidConfigurationException("Unknown '" + SPLIT_MODE_PARAM + "' is provided");
                }
            } else {
                throw new InvalidConfigurationException("Parameter '" + SPLIT_MODE_PARAM + "' is not provided");
            }

            fileSystemHandlerConnection = (FileSystemHandler) handler
                    .getConnection(Const.CONNECTOR_NAME, connectionName);
            sourceFilePath = fileSystemHandlerConnection.getBaseDirectoryPath() + sourceFilePath;
            targetDirectoryPath = fileSystemHandlerConnection.getBaseDirectoryPath() + targetDirectoryPath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);
            fileToSplit = fsManager.resolveFile(sourceFilePath, fso);
            targetDir = fsManager.resolveFile(targetDirectoryPath, fso);

            if (!fileToSplit.exists()) {
                throw new IllegalPathException("File not found: " + sourceFilePath);
            } else {
                if (!fileToSplit.isFile()) {
                    throw new IllegalPathException("Source Path does not point to a file: " + sourceFilePath);
                }
            }

            if (!targetDir.exists()) {
                targetDir.createFolder();
            }

            // Check file stability if parameter is provided
            if (!StringUtils.isEmpty(timeBetweenSizeCheck) && fileToSplit.isFile()) {
                if (!isFileStable(fileToSplit, timeBetweenSizeCheck)) {
                    handleError(messageContext, new IllegalPathException("File is not stable (still being written). Cannot split at this time."),
                            Error.OPERATION_ERROR, "File is not stable (still being written). Cannot split at this time.",
                            responseVariable, overwriteBody);
                    return;
                }
            }

            int splitFileCount = 0;

            switch (splitMode) {

                case CHUNK_SIZE:
                    String chunkSizeAsStr = (String) ConnectorUtils.
                            lookupTemplateParamater(messageContext, CHUNK_SIZE_PARAM);
                    if (StringUtils.isNotEmpty(chunkSizeAsStr)) {
                        splitFileCount = splitByChunkSize(fileToSplit,
                                targetDirectoryPath,
                                chunkSizeAsStr,
                                fsManager, fso);
                    } else {
                        throw new InvalidConfigurationException("Parameter '" + CHUNK_SIZE_PARAM + "' is not provided");
                    }
                    break;
                case LINE_COUNT:
                    String lineCountAsStr = (String) ConnectorUtils.
                            lookupTemplateParamater(messageContext, LINE_COUNT_PARAM);
                    if (StringUtils.isNotEmpty(lineCountAsStr)) {
                        splitFileCount = splitByLines(fileToSplit,
                                targetDirectoryPath,
                                lineCountAsStr,
                                fsManager,
                                fso);
                    } else {
                        throw new InvalidConfigurationException("Parameter '" + LINE_COUNT_PARAM + "' is not provided");
                    }
                    break;
                case XPATH_EXPRESSION:
                    String xpathExpression = (String) ConnectorUtils.
                            lookupTemplateParamater(messageContext, XPATH_EXPRESSION_PARAM);
                    if (StringUtils.isNotEmpty(xpathExpression)) {
                        splitFileCount = splitByXPathExpression(fileToSplit,
                                targetDirectoryPath,
                                xpathExpression,
                                fsManager,
                                fso);
                    } else {
                        throw new InvalidConfigurationException("Parameter '" + XPATH_EXPRESSION_PARAM + "' is not provided");
                    }
                    break;
                default:

                    break;
            }

            JsonObject resultJSON = generateOperationResult(messageContext,
                    new FileOperationResult(OPERATION_NAME, true));
            resultJSON.addProperty(NUMBER_OF_SPLITS_ELE_NAME, splitFileCount);
            handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, null);

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

        } catch (FileOperationException | IOException e) {       //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + sourceFilePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail, responseVariable, overwriteBody);

        } finally {
            if (fileToSplit != null) {
                try {
                    fileToSplit.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing file object while creating directory "
                            + fileToSplit);
                }
            }

            if (handler.getStatusOfConnection(Const.CONNECTOR_NAME, connectionName)) {
                if (fileSystemHandlerConnection != null) {
                    Utils.addMaxAccessMaskToFSO(fileSystemHandlerConnection.getFsOptions());
                    handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
                }
            }
        }
    }

    /**
     * Splits the file based on number of lines.
     *
     * @param fileToSplit      Source file object.
     * @param targetFolderPath Path of destination folder to write the splitted files.
     * @param splitLength      Number of lines per file.
     * @param manager          File System manager.
     * @param fso              File System Options
     * @return Number of split parts
     * @throws IOException In case of file operation error
     */
    private int splitByLines(FileObject fileToSplit, String targetFolderPath, String splitLength,
                             FileSystemManager manager, FileSystemOptions fso) throws IOException {

        BufferedReader bufferedReader = null;
        BufferedWriter bufferedWriter;
        try {

            int partNum = 0;
            int lineCount = 1;
            String currentLine;

            //open the source file
            bufferedReader = new BufferedReader(
                    new InputStreamReader(fileToSplit.getContent().getInputStream()));

            //create first file part
            String sourceFileName = fileToSplit.getName().getBaseName();
            FileObject filePart = createFilePart(targetFolderPath, sourceFileName, partNum, manager, fso);
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                    filePart.getContent().getOutputStream()));

            boolean startNextFile = false;
            while ((currentLine = bufferedReader.readLine()) != null) {
                if (startNextFile) {
                    filePart = createFilePart(targetFolderPath, sourceFileName, partNum, manager, fso);
                    bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                            filePart.getContent().getOutputStream()));
                    startNextFile = false;
                }
                bufferedWriter.write(currentLine);
                bufferedWriter.newLine();
                if (lineCount == Integer.parseInt(splitLength)) {
                    lineCount = 0;
                    partNum = partNum + 1;
                    bufferedWriter.flush();
                    bufferedWriter.close();
                    filePart.close();
                    startNextFile = true;
                }
                lineCount = lineCount + 1;
            }
            if (!startNextFile) {
                bufferedWriter.flush();
                bufferedWriter.close();
                filePart.close();
            }

            return partNum;

        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    log.error(LOG_IDENTIFIER + "Error " + "while closing the BufferedReader for file "
                            + fileToSplit.getURL());
                }
            }
        }
    }


    /**
     * Create file part as necessary.
     *
     * @param targetFolderPath Path of destination folder to write the splitted files.
     * @param sourceFileName   Name of the source file
     * @param partNum          Part number
     * @param manager          File System Manager
     * @param fso              File System Options
     * @return Created FileObject
     * @throws FileSystemException in case of an issue creating file
     */
    private FileObject createFilePart(String targetFolderPath, String sourceFileName,
                                      int partNum, FileSystemManager manager,
                                      FileSystemOptions fso) throws FileSystemException {

        String[] sourceFileNameParts = sourceFileName.split("\\.");
        String fileName = sourceFileNameParts[0];
        String extension = sourceFileNameParts[1];
        String pathToFilePart = targetFolderPath + Const.FILE_SEPARATOR
                + fileName + partNum + "." + extension;
        FileObject file = manager.resolveFile(pathToFilePart, fso);
        file.createFile();
        if (log.isDebugEnabled()) {
            log.debug(LOG_IDENTIFIER + "Created the file part "
                    + partNum + " for source file " + sourceFileName);
        }
        return file;
    }

    /**
     * Splits the file based on chunk size.
     *
     * @param sourceFileObj Source file object.
     * @param destination   Destination to write the splitted files.
     * @param chunkSize     Size of a file chunk in bytes.
     * @param manager       FileSystemManager to resolve splitted file.
     * @param options       FileSystemOptions to resolve splitted file.
     * @return Number of parts split
     * @throws IOException In case of error creating, writing to files
     */
    private int splitByChunkSize(FileObject sourceFileObj, String destination, String chunkSize,
                                 FileSystemManager manager, FileSystemOptions options) throws IOException {
        byte[] bytesIn = new byte[Integer.parseInt(chunkSize)];
        InputStream inputStream = null;
        OutputStream outputStream = null;
        BufferedOutputStream bufferedOutputStream = null;
        FileObject outputFileObj = null;
        int partNum = 0;
        String sourceFileName = sourceFileObj.getName().getBaseName();
        try {
            inputStream = new AutoCloseInputStream(sourceFileObj.getContent().getInputStream());
            while (inputStream.read(bytesIn) != -1) {
                try {
                    outputFileObj = createFilePart(destination, sourceFileName, partNum, manager, options);
                    outputStream = outputFileObj.getContent().getOutputStream();
                    bufferedOutputStream = new BufferedOutputStream(outputStream);
                    bufferedOutputStream.write(bytesIn);
                    bufferedOutputStream.flush();
                    outputStream.flush();

                } finally {
                    if (bufferedOutputStream != null) {
                        try {
                            bufferedOutputStream.close();
                        } catch (IOException e) {
                            log.warn(LOG_IDENTIFIER + "Error while closing the BufferedOutputStream: " + e.getMessage(), e);
                        }
                    }
                    if (outputStream != null) {
                        try {
                            outputStream.close();
                        } catch (IOException e) {
                            log.warn(LOG_IDENTIFIER + "Error while closing the OutputStream: " + e.getMessage(), e);
                        }
                    }
                    if (outputFileObj != null) {
                        outputFileObj.close();
                    }
                }
                partNum++;
                if (log.isDebugEnabled()) {
                    log.debug(LOG_IDENTIFIER + "created the file part " + partNum);
                }
            }

            return partNum;

        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.error(LOG_IDENTIFIER + "error while" + " closing inputStream for file "
                            + sourceFileObj.getURL());
                }
            }
        }
    }


    /**
     * Split xml document based on xpath expression.
     *
     * @param sourceFileObj   Source xml document file
     * @param destination     Destination to write the splitted files.
     * @param xpathExpression XPath expression to be used.
     * @param manager         FileSystem Manager
     * @param options         File options.
     * @return Number of split files
     * @throws FileOperationException In case of I/O error
     */
    private int splitByXPathExpression(FileObject sourceFileObj, String destination, String xpathExpression,
                                       FileSystemManager manager, FileSystemOptions options) throws FileOperationException {

        DocumentBuilderFactory documentFactory = getSecuredDocumentBuilder();
        DocumentBuilder documentBuilder;
        Document sourceXmlDocument;
        try {
            documentBuilder = documentFactory.newDocumentBuilder();
            sourceXmlDocument = documentBuilder.parse(sourceFileObj.getContent().getInputStream());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new FileOperationException("Failed to read source xml file "
                    + sourceFileObj.getName().getBaseName(), e);
        }

        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        XPathExpression expression;
        NodeList nodeList;
        try {
            expression = xpath.compile(xpathExpression);
            nodeList = (NodeList) expression.evaluate(sourceXmlDocument, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new FileOperationException("Error while evaluating xpath expression "
                    + xpathExpression, e);
        }

        OutputStream outputStream = null;
        FileObject outputFileObj = null;
        assert nodeList != null;
        for (int i = 0; i < nodeList.getLength(); ++i) {
            Document distinationXmlDocument = documentBuilder.newDocument();

            String parentNode = nodeList.item(i).getParentNode().getNodeName();
            Element root = distinationXmlDocument.createElement(parentNode);
            distinationXmlDocument.appendChild(root);
            Node currentNode = nodeList.item(i);

            Node clonedNode = currentNode.cloneNode(true);
            distinationXmlDocument.adoptNode(clonedNode);
            root.appendChild(clonedNode);

            //At the end, we save the file XML on disk
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer;
            DOMSource source = null;
            StreamResult result = null;
            try {
                transformer = transformerFactory.newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, Const.YES);
                source = new DOMSource(distinationXmlDocument);

                outputFileObj = manager.resolveFile(destination + Const.FILE_SEPARATOR
                                + parentNode + (i + 1) + ".xml", options);
                if (!outputFileObj.exists()) {
                    outputFileObj.createFile();
                }
                outputStream = outputFileObj.getContent().getOutputStream();

                result = new StreamResult(outputStream);
                transformer.transform(source, result);
                if (log.isDebugEnabled()) {
                    log.debug("File connector:split - Created the xml file part " + (i + 1));
                }
            } catch (TransformerException e) {
                throw new FileOperationException("Failed to transform " + source + " to " + result, e);
            } catch (FileSystemException e) {
                throw new FileOperationException("Error while processing the output xml file ", e);
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        log.warn("File Connector:splitFile - Error while "
                                + "closing the OutputStream: " + e.getMessage(), e);
                    }
                }
                if (outputFileObj != null) {
                    try {
                        outputFileObj.close();
                    } catch (FileSystemException e) {
                        log.warn("File Connector:splitFile - "
                                + "Error while closing the output split file", e);
                    }
                }
            }
        }
        return nodeList.getLength();
    }

    /**
     * Get document builder factory instance.
     *
     * @return documentBuilderFactory
     */
    private DocumentBuilderFactory getSecuredDocumentBuilder() {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setNamespaceAware(true);
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        try {
            documentBuilderFactory.setFeature(Constants.SAX_FEATURE_PREFIX +
                    Constants.EXTERNAL_GENERAL_ENTITIES_FEATURE, false);
            documentBuilderFactory.setFeature(Constants.SAX_FEATURE_PREFIX +
                    Constants.EXTERNAL_PARAMETER_ENTITIES_FEATURE, false);
            documentBuilderFactory.setFeature(Constants.XERCES_FEATURE_PREFIX +
                    Constants.LOAD_EXTERNAL_DTD_FEATURE, false);
            documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            log.error("Failed to load XML Processor Feature " + Constants.EXTERNAL_GENERAL_ENTITIES_FEATURE + " or " +
                    Constants.EXTERNAL_PARAMETER_ENTITIES_FEATURE + " or " + Constants.LOAD_EXTERNAL_DTD_FEATURE);
        }

        SecurityManager securityManager = new SecurityManager();
        securityManager.setEntityExpansionLimit(ENTITY_EXPANSION_LIMIT);
        documentBuilderFactory.setAttribute(Constants.XERCES_PROPERTY_PREFIX +
                Constants.SECURITY_MANAGER_PROPERTY, securityManager);
        return documentBuilderFactory;
    }

    /**
     * Check if file is stable (not being written to) by comparing file sizes
     * over a specified interval.
     * 
     * @param file File to check for stability
     * @param sizeCheckInterval Time in milliseconds to wait between size checks
     * @return true if file is stable, false if still being written
     */
    private boolean isFileStable(FileObject file, String sizeCheckInterval) {
        try {
            long interval = Long.parseLong(sizeCheckInterval);
            if (interval <= 0) {
                return true; // No stability check if interval is 0 or negative
            }
            
            long initialSize = file.getContent().getSize();
            
            // Wait for the specified interval
            Thread.sleep(interval);
            
            // Re-read file size and compare
            long finalSize = file.getContent().getSize();
            
            // File is stable if size hasn't changed
            return initialSize == finalSize;
            
        } catch (NumberFormatException e) {
            // If we can't parse the interval, assume file is stable
            log.warn("Invalid timeBetweenSizeCheck value: " + sizeCheckInterval + ". Skipping stability check.");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // If interrupted, assume file is stable
            return true;
        } catch (Exception e) {
            // If we can't check stability, assume file is stable
            log.warn("Error checking file stability: " + e.getMessage() + ". Assuming file is stable.");
            return true;
        }
    }

    /**
     * Sets error to context and handle.
     *
     * @param msgCtx      Message Context to set info
     * @param e           Exception associated
     * @param error       Error code
     * @param errorDetail Error detail
     * @param responseVariable Response variable name
     * @param overwriteBody Overwrite body
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail,
                             String responseVariable, boolean overwriteBody) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        FileOperationResult result = new FileOperationResult(OPERATION_NAME, false, error, e.getMessage());
        JsonObject resultJSON = generateOperationResult(msgCtx, result);
        handleConnectorResponse(msgCtx, responseVariable, overwriteBody, resultJSON, null, null);
        handleException(errorDetail, e, msgCtx);
    }
}
