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
import org.apache.axis2.AxisFault;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
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
                byteCountWritten = (int) writeToFile(targetFile, messageContext);
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


    private long writeToFile(FileObject targetFile, MessageContext msgCtx) throws IOException,
            ConnectorOperationException, IllegalPathException, InvalidConfigurationException {

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
        String mimeType = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "mimeType");
        boolean compress = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "compress"));
        boolean enableStreaming = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "enableStreaming"));
        int contentAppendPosition = Integer.parseInt((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, "appendPosition"));

        boolean contentPropertyIsPresent = false;
        if (StringUtils.isNotEmpty(contentToWrite)) {
            contentPropertyIsPresent = true;
        }


        switch (writeMode) {
            case CREATE_NEW:
                if (targetFile.exists()) {
                    throw new ConnectorOperationException("Target file already exists. Path = "
                            + targetFile.getURL());
                } else {
                    targetFile.createFile();
                    if (contentPropertyIsPresent) {
                        writtenBytesCount = performContentWrite(targetFile, contentToWrite, encoding, mimeType, compress);
                    } else {
                        writtenBytesCount = performBodyWrite(targetFile, msgCtx, false, mimeType, enableStreaming, compress);
                    }

                }
                break;
            case OVERWRITE:
                targetFile.createFile();
                if (contentPropertyIsPresent) {
                    writtenBytesCount = performContentWrite(targetFile, contentToWrite, encoding, mimeType, compress);
                } else {
                    writtenBytesCount = performBodyWrite(targetFile, msgCtx, false, mimeType, enableStreaming, compress);
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
                        writtenBytesCount = performBodyWrite(targetFile, msgCtx, true, mimeType, enableStreaming, compress);
                    }
                }
                break;

            default:
                throw new ConnectorOperationException("Unexpected File Write Mode: " + writeMode);
        }

        return writtenBytesCount;
    }

    private long performContentWrite(FileObject targetFile, String contentToWrite,
                                     String encoding, String mimeType, boolean compress) throws IOException {
        CountingOutputStream out = null;
        try {
            if (compress) {
                out = new CountingOutputStream(new ZipOutputStream(targetFile.getContent().getOutputStream()));
            } else {
                out = new CountingOutputStream(targetFile.getContent().getOutputStream());
            }
            if (mimeType.equals(FileConnectorConstants.CONTENT_TYPE_BINARY)) {
                // Write binary content decoded from a base64 string
                byte[] decoded = Base64.getDecoder().decode(contentToWrite);
                out.write(decoded);
            }
            if (StringUtils.isNotEmpty(encoding)) {
                IOUtils.write(contentToWrite, out, encoding);
            } else {
                IOUtils.write(contentToWrite, out, FileConnectorConstants.DEFAULT_ENCODING);
            }
            return out.resetByteCount();
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
                log.warn("FileConnector:write - Position is greater than the existing line count of file"
                        + targetFile.getName().getBaseName()
                        + ". Hence appending the content at EOF");
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

    private long performBodyWrite(FileObject targetFile, MessageContext messageContext,
                                  boolean append, String mimeType, boolean streaming, boolean compress)
            throws ConnectorOperationException, FileSystemException, AxisFault {

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();
        //override message type set
        if (StringUtils.isNotEmpty(mimeType)) {
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
            try {
                if (compress) {
                    outputStream = new CountingOutputStream(new ZipOutputStream(targetFile.getContent().
                            getOutputStream(append)));
                } else {
                    outputStream = new CountingOutputStream(targetFile.getContent().getOutputStream(append));
                }
                messageFormatter.writeTo(axis2MessageContext, format, outputStream, true);
                return outputStream.getByteCount();
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
