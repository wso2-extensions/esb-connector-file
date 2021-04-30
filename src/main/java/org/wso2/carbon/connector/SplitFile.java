/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.connector;

import org.apache.axiom.om.OMElement;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.synapse.MessageContext;
import org.codehaus.jettison.json.JSONException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.ResultPayloadCreate;
import org.xml.sax.SAXException;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.util.SecurityManager;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.*;

/**
 * Splits the file into multiple chunks and writes them to a file system.
 */
public class SplitFile extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(SplitFile.class);
    private StandardFileSystemManager manager = null;
    private static final int ENTITY_EXPANSION_LIMIT = 0;

    public void connect(MessageContext messageContext) {
        String fileLocation = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.FILE_LOCATION);
        String chunkSize = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.CHUNK_SIZE);
        String destination = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.NEW_FILE_LOCATION);
        String numberOfLines = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.NUMBER_OF_LINES);
        String xpathExpression = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.XPATH_EXPRESSION);

        boolean resultStatus = splitFile(fileLocation, chunkSize, destination, numberOfLines, xpathExpression,
                messageContext);
        generateOutput(messageContext, resultStatus);
    }

    /**
     * @param fileLocation   Location of the source file.
     * @param chunkSize      size of the chunks to split the file.
     * @param destination    Location of the destination to write the splitted files.
     * @param messageContext Message context.
     * @return Status true/false.
     */
    private boolean splitFile(String fileLocation, String chunkSize, String destination, String splitLength,
                              String xpathExpression, MessageContext messageContext) {
        FileObject sourceFileObj = null;
        try {
            manager = FileConnectorUtils.getManager();
            FileSystemOptions sourceFso = FileConnectorUtils.getSourceFso(messageContext, fileLocation, manager);
            FileSystemOptions destinationFso = FileConnectorUtils.getTargetFso(messageContext, destination, manager);

            sourceFileObj = manager.resolveFile(fileLocation, sourceFso);
            if (!sourceFileObj.exists() || sourceFileObj.getType() != FileType.FILE) {
                handleException("File does not exists, or source is not a file in the location: " + fileLocation,
                        messageContext);
            } else {
                if (StringUtils.isNotEmpty(splitLength)) {
                    splitByLines(sourceFileObj, destination, splitLength, destinationFso, messageContext);
                } else if (StringUtils.isNotEmpty(chunkSize)) {
                    splitByChunkSize(sourceFileObj, destination, chunkSize, destinationFso, messageContext);
                } else if (StringUtils.isNotEmpty(xpathExpression)) {
                    splitByXPathExpression(sourceFileObj, destination, xpathExpression, destinationFso, messageContext);
                }
            }
        } catch (IOException e) {
            handleException("Error while processing the file", e, messageContext);
        } finally {
            if (sourceFileObj != null) {
                try {
                    sourceFileObj.close();
                } catch (FileSystemException e) {
                    log.warn("Error while closing the sourceFileObj: " + e.getMessage(), e);
                }
            }
            if (manager != null) {
                manager.close();
            }
        }
        return true;
    }

    /**
     * Splits the file based on chunk size.
     * @param sourceFileObj Source file object.
     * @param destination Destination to write the splitted files.
     * @param chunkSize Size of a file chunk in bytes.
     * @param options File options.
     * @param messageContext Message context
     */
    private void splitByChunkSize(FileObject sourceFileObj, String destination, String chunkSize,
                                  FileSystemOptions options, MessageContext messageContext) {
        byte[] bytesIn = new byte[Integer.parseInt(chunkSize)];
        InputStream inputStream;
        OutputStream outputStream = null;
        BufferedOutputStream bufferedOutputStream = null;
        FileObject outputFileObj = null;
        int partNum = 0;
        try {
            inputStream = new AutoCloseInputStream(sourceFileObj.getContent().getInputStream());
            while (inputStream.read(bytesIn) != -1) {
                    String outputFileName = destination + File.separator
                            + String.valueOf(sourceFileObj.getName().getBaseName()) + partNum;
                    try {
                        outputFileObj = manager.resolveFile(outputFileName, options);
                        if (!outputFileObj.exists()) {
                            outputFileObj.createFile();
                        }
                        outputStream = outputFileObj.getContent().getOutputStream();
                        bufferedOutputStream = new BufferedOutputStream(outputStream);
                        bufferedOutputStream.write(bytesIn);
                        bufferedOutputStream.flush();
                        outputStream.flush();
                    } catch (IOException e) {
                        handleException("Error while processing the file", e, messageContext);
                    } finally {
                        if (bufferedOutputStream != null) {
                            try {
                                bufferedOutputStream.close();
                            } catch (IOException e) {
                                log.warn("Error while closing the BufferedOutputStream: " + e.getMessage(), e);
                            }
                        }
                        if (outputStream != null) {
                            try {
                                outputStream.close();
                            } catch (IOException e) {
                                log.warn("Error while closing the OutputStream: " + e.getMessage(), e);
                            }
                        }
                        if (outputFileObj != null) {
                            outputFileObj.close();
                        }
                    }
                    partNum++;
                    if (log.isDebugEnabled()) {
                        log.debug("Created the file part " + partNum);
                    }
                }
        } catch (IOException e) {
            handleException("Error while processing the file", e, messageContext);
        }
    }

    /**
     * Splits the file based on number of lines.
     * @param sourceFileObj Source file object.
     * @param destination Destination to write the splitted files.
     * @param splitLength Number of lines per file.
     * @param options File options.
     * @param messageContext Message context
     */
    private void splitByLines(FileObject sourceFileObj, String destination, String splitLength,
                              FileSystemOptions options, MessageContext messageContext) {
        BufferedReader bufferedReader = null;
        BufferedWriter bufferedWriter;
        String outputFileName;
        int partNum = 0;
        String line;
        int count = 1;
        FileObject outputFileObj;
        try {
            bufferedReader = new BufferedReader(
                    new InputStreamReader(sourceFileObj.getContent().getInputStream()));
            outputFileName = destination + File.separator
                    + String.valueOf(sourceFileObj.getName().getBaseName()) + partNum;
            outputFileObj = manager.resolveFile(outputFileName, options);
            if (!outputFileObj.exists()) {
                outputFileObj.createFile();
            }
            if (log.isDebugEnabled()) {
                log.debug("Created the file part " + partNum);
            }
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                    outputFileObj.getContent().getOutputStream()));
            boolean isClosed = false;
            while ((line = bufferedReader.readLine()) != null) {
                if (isClosed) {
                    outputFileName = destination + File.separator
                            + String.valueOf(sourceFileObj.getName().getBaseName()) + partNum;
                    outputFileObj = manager.resolveFile(outputFileName, options);
                    if (!outputFileObj.exists()) {
                        outputFileObj.createFile();
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Created the file part " + partNum);
                    }
                    bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                            outputFileObj.getContent().getOutputStream()));
                    isClosed = false;
                }
                bufferedWriter.write(line);
                bufferedWriter.newLine();
                if (count == Integer.parseInt(splitLength)) {
                    count = 0;
                    partNum++;
                    bufferedWriter.flush();
                    bufferedWriter.close();
                    outputFileObj.close();
                    isClosed = true;
                }
                count++;
            }
            if (!isClosed) {
                bufferedWriter.flush();
                bufferedWriter.close();
                outputFileObj.close();
            }
        } catch (IOException e) {
            handleException("Error while processing the file", e, messageContext);
        } finally {
            if(bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    log.warn("Error while closing the BufferedReader");
                }
            }
        }
    }

    /**
     * Split xml document based on xpath expression.
     *
     * @param sourceFileObj   Source file object.
     * @param destination     Destination to write the splitted files.
     * @param xpathExpression XPath expression to be used.
     * @param options File options.
     * @param messageContext  Message context.
     */
    private void splitByXPathExpression(FileObject sourceFileObj, String destination, String xpathExpression,
                                        FileSystemOptions options, MessageContext messageContext) {

        DocumentBuilderFactory documentFactory = getSecuredDocumentBuilder();
        DocumentBuilder documentBuilder = null;
        Document sourceXmlDocument = null;
        try {
            documentBuilder = documentFactory.newDocumentBuilder();
            sourceXmlDocument = documentBuilder.parse(sourceFileObj.getContent().getInputStream());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            handleException("Failed to read source xml file ", e, messageContext);
        }

        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        XPathExpression expression;
        NodeList nodeList = null;
        try {
            expression = xpath.compile(xpathExpression);
            nodeList = (NodeList) expression.evaluate(sourceXmlDocument, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            handleException("Error while evaluating xpath expression ", e, messageContext);
        }

        OutputStream outputStream = null;
        FileObject outputFileObj = null;
        assert nodeList != null;
        for (int i = 0; i < nodeList.getLength(); ++i) {
            assert documentBuilder != null;
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
                transformer.setOutputProperty(OutputKeys.INDENT, FileConstants.YES);
                source = new DOMSource(distinationXmlDocument);

                outputFileObj = manager.resolveFile(destination + File.separator + parentNode + (i + 1) + ".xml"
                        , options);
                if (!outputFileObj.exists()) {
                    outputFileObj.createFile();
                }
                outputStream = outputFileObj.getContent().getOutputStream();

                result = new StreamResult(outputStream);
                transformer.transform(source, result);
                if (log.isDebugEnabled()) {
                    log.debug("Created the xml file part " + (i + 1));
                }
            } catch (TransformerException e) {
                handleException("Failed to transform " + source + " to " + result, e, messageContext);
            } catch (FileSystemException e) {
                handleException("Error while processing the output xml file ", e, messageContext);
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        log.warn("Error while closing the OutputStream: " + e.getMessage(), e);
                    }
                }
                if (outputFileObj != null) {
                    try {
                        outputFileObj.close();
                    } catch (FileSystemException e) {
                        handleException("Error while closing the file", e, messageContext);
                    }
                }
            }
        }
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
     * Generate the output payload
     *
     * @param messageContext The message context that is processed by a handler in the handle method
     * @param resultStatus   Result of the status (true/false)
     */
    private void generateOutput(MessageContext messageContext, boolean resultStatus) {
        ResultPayloadCreate resultPayload = new ResultPayloadCreate();
        String response = FileConstants.START_TAG + resultStatus + FileConstants.END_TAG;

        try {
            OMElement element = resultPayload.performSearchMessages(response);
            resultPayload.preparePayload(messageContext, element);
        } catch (XMLStreamException | IOException | JSONException e) {
            handleException(e.getMessage(), e, messageContext);
        }
    }

}