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

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.format.BinaryFormatter;
import org.apache.axis2.format.PlainTextFormatter;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseTransportException;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.ConnectorOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileWriteMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Implements Write file connector operation
 */
public class WriteFile extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String operationName = "write";
        String errorMessage = "Error while performing file:write for file ";

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String targetFilePath = null;
        FileObject targetFile = null;
        FileOperationResult result;

        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            targetFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "filePath");

            if (StringUtils.isEmpty(targetFilePath)) {
                throw new InvalidConfigurationException("Parameter 'filePath' is not provided ");
            }

            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);

            String fileNameWithExtension = targetFilePath.substring(targetFilePath.lastIndexOf(File.separator));

            //if compress is enabled we need to resolve a zip file
            targetFilePath = getModifiedFilePathForCompress(targetFilePath, messageContext);

            targetFilePath = fileSystemHandler.getBaseDirectoryPath() + targetFilePath;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            targetFile = fsManager.resolveFile(targetFilePath, fso);

            if (targetFile.isFolder()) {
                throw new IllegalPathException("Path does not point to a file " + targetFilePath);
            }

            //lock the file if enabled
            boolean enableLock = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "enableLock"));
            if (enableLock) {
                //TODO if locking is enabled, lock file
            }

            int byteCountWritten;
            try {
                byteCountWritten = (int) writeToFile(targetFile, fileNameWithExtension, messageContext);
                targetFile.getContent().setLastModifiedTime(System.currentTimeMillis());
            } finally {
                //TODO:finally, release the lock if acquired
            }

            result = new FileOperationResult(
                    operationName,
                    true,
                    byteCountWritten);

            String fileWriteResultProperty = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "fileWriteResultProperty");

            if (StringUtils.isNotEmpty(fileWriteResultProperty)) {
                OMElement resultEle = FileConnectorUtils.generateOperationResult(messageContext, result);
                messageContext.setProperty(fileWriteResultProperty, resultEle);
            } else {
                FileConnectorUtils.setResultAsPayload(messageContext, result);
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = errorMessage + targetFilePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.INVALID_CONFIGURATION,
                    e.getMessage());

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (IllegalPathException e) {

            String errorDetail = errorMessage + targetFilePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.ILLEGAL_PATH,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (ConnectorOperationException | IOException e) { //FileSystemException also handled here
            String errorDetail = errorMessage + targetFilePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.OPERATION_ERROR,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (targetFile != null) {
                try {
                    targetFile.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing folder object while reading files in "
                            + targetFile);
                }
            }
        }
    }


    /**
     * Modify passed targetFilePath as per conditions if file compress is enabled.
     * Otherwise return same targetFilePath as passed.
     *
     * @param targetFilePath
     * @param msgCtx
     * @return
     * @throws InvalidConfigurationException
     */
    private String getModifiedFilePathForCompress(String targetFilePath, MessageContext msgCtx)
            throws InvalidConfigurationException {

        boolean compress = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "compress"));
        String fileWriteModeAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "writeMode");
        FileWriteMode writeMode;
        if (StringUtils.isNotEmpty(fileWriteModeAsStr)) {
            writeMode = FileWriteMode.fromString(fileWriteModeAsStr);
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'writeMode' is not provided");
        }

        String fileNameWithExtension = targetFilePath.substring(targetFilePath.lastIndexOf(File.separator));

        if ((!Objects.equals(writeMode, FileWriteMode.APPEND)) && compress) {
            String fileName = fileNameWithExtension.split("\\.")[0];
            String parentFolder = targetFilePath.substring(0, targetFilePath.lastIndexOf(File.separator));
            targetFilePath = parentFolder + fileName + FileConnectorConstants.ZIP_FILE_EXTENSION;
        }

        return targetFilePath;

    }

    private long writeToFile(FileObject targetFile, String fileNameWithExtension, MessageContext msgCtx)
            throws IOException, ConnectorOperationException, IllegalPathException,
            InvalidConfigurationException {

        long writtenBytesCount = 0;
        FileWriteMode writeMode;
        String fileWriteModeAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "writeMode");
        if (StringUtils.isNotEmpty(fileWriteModeAsStr)) {
            writeMode = FileWriteMode.fromString(fileWriteModeAsStr);
        } else {
            throw new InvalidConfigurationException("Mandatory parameter 'writeMode' is not provided");
        }

        //TODO: how to write an attachment to a file?

        String contentToWrite = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "contentOrExpression");
        String encoding = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "encoding");
        if (StringUtils.isEmpty(encoding)) {
            encoding = FileConnectorConstants.DEFAULT_ENCODING;
        }
        String mimeType = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "mimeType");
        boolean compress = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "compress"));
        boolean appendNewLine = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "appendNewLine"));
        boolean enableStreaming = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "enableStreaming"));
        int contentAppendPosition = Integer.MAX_VALUE;
        String contentPositionAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "appendPosition");
        if (StringUtils.isNotEmpty(contentPositionAsStr)) {
            contentAppendPosition = Integer.parseInt(contentPositionAsStr);
        }


        boolean contentPropertyIsPresent = false;
        if (StringUtils.isNotEmpty(contentToWrite)) {
            contentPropertyIsPresent = true;
            if (appendNewLine) {
                contentToWrite = contentToWrite + FileConnectorConstants.NEW_LINE;
            }
        }


        switch (writeMode) {
            case CREATE_NEW:
                if (targetFile.exists()) {
                    throw new ConnectorOperationException("Target file already exists. Path = "
                            + targetFile.getURL());
                } else {
                    targetFile.createFile();
                    if (contentPropertyIsPresent) {
                        writtenBytesCount = performContentWrite(targetFile, fileNameWithExtension, contentToWrite, encoding, mimeType, compress);
                    } else {
                        writtenBytesCount = performBodyWrite(targetFile, fileNameWithExtension, msgCtx, false, mimeType, enableStreaming, compress, appendNewLine);

                    }
                }
                break;
            case OVERWRITE:
                targetFile.createFile();
                if (contentPropertyIsPresent) {
                    writtenBytesCount = performContentWrite(targetFile, fileNameWithExtension, contentToWrite, encoding, mimeType, compress);
                } else {
                    writtenBytesCount = performBodyWrite(targetFile, fileNameWithExtension, msgCtx, false, mimeType, enableStreaming, compress, appendNewLine);
                }
                break;
            case APPEND:
                if (contentAppendPosition <= 0) {
                    throw new ConnectorOperationException("Invalid file append position. Expecting a positive value");
                }
                if (!targetFile.exists()) {
                    throw new IllegalPathException("File to append is not found: " + targetFile.getURL());
                } else {
                    if (contentPropertyIsPresent) {
                        writtenBytesCount = performContentAppend(targetFile, contentToWrite, encoding, contentAppendPosition);
                    } else {
                        writtenBytesCount = performBodyWrite(targetFile, fileNameWithExtension, msgCtx, true, mimeType, enableStreaming, compress, appendNewLine);
                    }
                }
                break;

            default:
                throw new ConnectorOperationException("Unexpected File Write Mode: " + writeMode);
        }

        return writtenBytesCount;
    }

    private long performContentWrite(FileObject targetFile, String fileNameWithExtension, String contentToWrite,
                                     String encoding, String mimeType, boolean compress) throws IOException {
        CountingOutputStream out = null;
        try {
            if (compress) {
                ZipEntry zipEntry = new ZipEntry(fileNameWithExtension);
                ZipOutputStream zipOutputStream = new ZipOutputStream(targetFile.getContent().getOutputStream());
                zipOutputStream.putNextEntry(zipEntry);
                out = new CountingOutputStream(zipOutputStream);
            } else {
                out = new CountingOutputStream(targetFile.getContent().getOutputStream());
            }
            if (Objects.equals(mimeType, FileConnectorConstants.CONTENT_TYPE_BINARY)) {
                // Write binary content decoded from a base64 string
                byte[] decoded = Base64.getDecoder().decode(contentToWrite);
                out.write(decoded);
            }
            if (StringUtils.isNotEmpty(encoding)) {
                IOUtils.write(contentToWrite, out, encoding);
            } else {
                IOUtils.write(contentToWrite, out, FileConnectorConstants.DEFAULT_ENCODING);
            }
            return out.getByteCount();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                log.error("FileConnector:write - Error while closing OutputStream: "
                        + targetFile.getName().getBaseName(), e);
            }
        }
    }

    private long appendNewLine(FileObject targetFile) throws IOException {
        CountingOutputStream outputStream = null;
        try {
            outputStream = new CountingOutputStream(targetFile.getContent().getOutputStream(true));
            IOUtils.write(FileConnectorConstants.NEW_LINE, outputStream);
            return outputStream.getByteCount();
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                log.error("FileConnector:write - Error while closing output stream for file "
                        + targetFile.getName().getBaseName() + " when appending new line");
            }
        }
    }


    private long performContentAppend(FileObject targetFile, String contentToAppend,
                                      String encoding, int position) throws IOException {
        BufferedReader reader = null;
        CountingOutputStream out = null;
        try {
            reader = new BufferedReader(new InputStreamReader(targetFile.getContent().getInputStream()));
            List<String> lines = reader.lines().collect(Collectors.toList());
            if (position <= lines.size()) {
                lines.add(position - 1, contentToAppend);
            } else {
                if (position != Integer.MAX_VALUE) {
                    log.warn("FileConnector:write - Append position is greater than the existing line count of file "
                            + targetFile.getName().getBaseName()
                            + ". Hence appending the content at EOF.");
                }
                lines.add(contentToAppend);
            }

            out = new CountingOutputStream(targetFile.getContent().getOutputStream());
            if (StringUtils.isEmpty(encoding)) {
                IOUtils.writeLines(lines, null, out, FileConnectorConstants.DEFAULT_ENCODING);
            } else {
                IOUtils.writeLines(lines, null, out, encoding);
            }
            return out.getByteCount();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                log.error("FileConnector:write - Error while closing BufferedReader for file: "
                        + targetFile.getName().getBaseName(), e);
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                log.error("FileConnector:write - Error while closing OutputStream: for file "
                        + targetFile.getName().getBaseName(), e);
            }
        }
    }

    private long performBodyWrite(FileObject targetFile, String fileNameWithExtension, MessageContext messageContext,
                                  boolean append, String mimeType, boolean streaming,
                                  boolean compress, boolean appendNewLine)
            throws ConnectorOperationException, IOException {

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();
        //override message type set
        if (StringUtils.isNotEmpty(mimeType) &&
                !(mimeType.equals(FileConnectorConstants.CONTENT_TYPE_AUTOMATIC))) {

            axis2MessageContext.setProperty(FileConnectorConstants.MESSAGE_TYPE, mimeType);
        }
        MessageFormatter messageFormatter;
        if (streaming) {
            //this will get data handler and access input stream set
            messageFormatter = new ExpandingMessageFormatter();
        } else {
            messageFormatter = getMessageFormatter(axis2MessageContext);
        }
        OMOutputFormat format = BaseUtils.getOMOutputFormat(axis2MessageContext);
        if (Objects.isNull(messageFormatter)) {
            throw new ConnectorOperationException("Error while determining message "
                    + "formatter to use when writing file" + targetFile.getName().getBaseName());
        } else {
            CountingOutputStream outputStream = null;
            long writtenByesCount = 0;
            try {
                if (compress) {
                    ZipOutputStream zipOutputStream = new ZipOutputStream(targetFile.getContent().
                            getOutputStream(append));
                    ZipEntry zipEntry = new ZipEntry(fileNameWithExtension);
                    zipOutputStream.putNextEntry(zipEntry);
                    outputStream = new CountingOutputStream(zipOutputStream);
                } else {
                    outputStream = new CountingOutputStream(targetFile.getContent().getOutputStream(append));
                }
                messageFormatter.writeTo(axis2MessageContext, format, outputStream, true);
                writtenByesCount = outputStream.getByteCount();
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        log.error("FileConnector:write - error "
                                + "while closing output stream for file " + targetFile.getName().getBaseName());
                    }
                }
            }
            if (!compress && appendNewLine) {
                writtenByesCount = writtenByesCount + appendNewLine(targetFile);
            }
            return writtenByesCount;
        }
    }


    /**
     * Get the correct formatter for message
     *
     * @param msgContext The message context that is generated for processing the file
     */
    private MessageFormatter getMessageFormatter(org.apache.axis2.context.MessageContext msgContext) {
        OMElement firstChild = msgContext.getEnvelope().getBody().getFirstElement();
        if (firstChild != null) {
            if (BaseConstants.DEFAULT_BINARY_WRAPPER.equals(firstChild.getQName())) {
                return new BinaryFormatter();
            } else if (BaseConstants.DEFAULT_TEXT_WRAPPER.equals(firstChild.getQName())) {
                return new PlainTextFormatter();
            }
        }
        try {
            return MessageProcessorSelector.getMessageFormatter(msgContext);
        } catch (Exception e) {
            throw new BaseTransportException("Unable to get the message formatter to use");
        }
    }
}
