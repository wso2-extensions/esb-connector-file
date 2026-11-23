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

import org.wso2.vfs.shared.log.NamedLog;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.util.SecurityManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.pojo.FileSplitMode;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.vfs.shared.write.AtomicWriter;

public class SplitFile extends FileOperation {
    private static final int ENTITY_EXPANSION_LIMIT = 0;

    public SplitFile() {
        super("splitFile", "sourceFilePath", "targetDirectory");
    }

    @Override
    void configure(Call call) throws InvalidConfigurationException {
        call.prepareGuard(null);
        FileSplitMode mode = FileSplitMode.fromString(call.option("splitMode", ""));
        if (mode == null) {
            throw new InvalidConfigurationException("Unknown splitMode");
        }
        if (mode == FileSplitMode.CHUNK_SIZE || mode == FileSplitMode.LINE_COUNT) {
            String parameter = mode == FileSplitMode.CHUNK_SIZE ? "chunkSize" : "lineCount";
            call.partSize = Long.parseLong(call.option(parameter, ""));
            if (call.partSize <= 0) {
                throw new InvalidConfigurationException(parameter + " must be positive");
            }
        } else if (call.option("xpathExpression") == null) {
            throw new InvalidConfigurationException("An XPath expression is required");
        }
        call.sourceUse = OperationFrame.Use.SOURCE;
        call.retry = OperationFrame.Retry.SAFE;
    }

    @Override
    void perform(Call call, Attempt attempt, FileObject source, JsonObject result) throws Exception {
        call.requireSource(attempt, source, call.guard());
        try (FileObject directory = attempt.handler().resolveFile(call.target)) {
            if (!AtomicOps.existsNow(directory)) {
                directory.createFolder();
            }
        }
        String name = source.getName().getBaseName();
        int count;
        try (InputStream input = partInput(attempt, source)) {
            Parts parts = (part, content) -> call.write(attempt,
                    FileTreeOperation.Walk.childPath(call.target, FileTreeOperation.Walk.escape(part)), false, content);
            switch (FileSplitMode.fromString(call.option("splitMode"))) {
                case CHUNK_SIZE:
                    count = chunks(input, name, call.partSize, parts);
                    break;
                case LINE_COUNT:
                    count = lines(input, name, call.partSize, parts);
                    break;
                default:
                    count = xml(input, call.option("xpathExpression"), parts, call.connectionLog);
                    break;
            }
        }
        result.addProperty("numberOfSplits", count);
    }

    interface Parts {
        void write(String name, AtomicWriter.Content content) throws Exception;
    }

    static String partName(String name, int part) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name + part : name.substring(0, dot) + part + name.substring(dot);
    }

    static int chunks(InputStream input, String name, long size, Parts parts) throws Exception {
        int count = 0;
        byte[] buffer = new byte[8192];
        int first;
        while ((first = input.read(buffer, 0, (int) Math.min(size, buffer.length))) != -1) {
            final int initial = first;
            parts.write(partName(name, count++), output -> {
                output.write(buffer, 0, initial);
                long remaining = size - initial;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                    if (read == -1) {
                        break;
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            });
        }
        return count;
    }

    private int lines(InputStream input, String name, long size, Parts parts) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        int full = 0;
        String first;
        while ((first = reader.readLine()) != null) {
            final String start = first;
            long[] written = {0};
            parts.write(partName(name, full), output -> {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));
                String line = start;
                do {
                    writer.write(line);
                    writer.newLine();
                    written[0]++;
                } while (written[0] < size && (line = reader.readLine()) != null);
                writer.flush();
            });
            if (written[0] == size) {
                full++;
            }
        }
        return full;
    }

    private int xml(InputStream input, String expression, Parts parts, NamedLog connectionLog) throws Exception {
        DocumentBuilder builder = getSecuredDocumentBuilder(connectionLog).newDocumentBuilder();
        Document document = builder.parse(input);
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().compile(expression)
                .evaluate(document, XPathConstants.NODESET);
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            String parent = node.getParentNode().getNodeName();
            Document part = builder.newDocument();
            Element root = part.createElement(parent);
            part.appendChild(root);
            root.appendChild(part.importNode(node, true));
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, Const.YES);
            parts.write(parent + (index + 1) + ".xml", output -> {
                try {
                    transformer.transform(new DOMSource(part), new StreamResult(output));
                } catch (TransformerException failure) {
                    throw new IOException(failure);
                }
            });
        }
        return nodes.getLength();
    }

    private DocumentBuilderFactory getSecuredDocumentBuilder(NamedLog connectionLog) {
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
            connectionLog.error("Failed to load XML Processor Feature " + Constants.EXTERNAL_GENERAL_ENTITIES_FEATURE + " or " +
                    Constants.EXTERNAL_PARAMETER_ENTITIES_FEATURE + " or " + Constants.LOAD_EXTERNAL_DTD_FEATURE);
        }

        SecurityManager securityManager = new SecurityManager();
        securityManager.setEntityExpansionLimit(ENTITY_EXPANSION_LIMIT);
        documentBuilderFactory.setAttribute(Constants.XERCES_PROPERTY_PREFIX +
                Constants.SECURITY_MANAGER_PROPERTY, securityManager);
        return documentBuilderFactory;
    }
}
