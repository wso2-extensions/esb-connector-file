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
import org.wso2.carbon.connector.exception.FileLockException;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileWriteMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;
import org.wso2.carbon.connector.utils.FileLock;
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

    private static final String FILE_PATH_PARAM = "filePath";
    private static final String ENABLE_LOCK_PARAM = "enableLock";
    private static final String INCLUDE_RESULT_TO = "includeResultTo";
    private static final String RESULT_PROPERTY_NAME = "resultPropertyName";
    private static final String COMPRESS_PARAM = "compress";
    private static final String WRITE_MODE_PARAM = "writeMode";
    private static final String CONTENT_OR_EXPRESSION_PARAM = "contentOrExpression";
    private static final String ENCODING_PARAM = "encoding";
    private static final String MIME_TYPE_PARAM = "mimeType";
    private static final String APPEND_NEW_LINE_PARAM = "appendNewLine";
    private static final String ENABLE_STREAMING_PARAM = "enableStreaming";
    private static final String APPEND_POSITION_PARAM = "appendPosition";
    private static final String OPERATION_NAME = "write";
    private static final String ERROR_MESSAGE = "Error while performing file:write for file ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String targetFilePath = null;
        FileObject targetFile = null;
        FileOperationResult result;

        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            targetFilePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, FILE_PATH_PARAM);

            if (StringUtils.isEmpty(targetFilePath)) {
                throw new InvalidConfigurationException("Parameter '" + FILE_PATH_PARAM + "' is not provided ");
            }

            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);

            String fileNameWithExtension = targetFilePath.
                    substring(targetFilePath.lastIndexOf(FileConnectorConstants.FILE_SEPARATOR) + 1);

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
                    lookupTemplateParamater(messageContext, ENABLE_LOCK_PARAM));
            FileLock fileLock = new FileLock(targetFilePath);
            if (enableLock) {
                boolean lockAcquired = fileLock.acquireLock(fsManager, fso, FileConnectorConstants.DEFAULT_LOCK_TIMEOUT);
                if (!lockAcquired) {
                    throw new FileLockException("Failed to acquire lock for file "
                            + targetFilePath + ". Another process maybe processing it. ");
                }
            }
            int byteCountWritten;
            try {
                byteCountWritten = (int) writeToFile(targetFile, fileNameWithExtension, messageContext);
                targetFile.getContent().setLastModifiedTime(System.currentTimeMillis());
            } finally {
                if (enableLock) {
                    boolean lockReleased = fileLock.releaseLock();
                    if (!lockReleased) {
                        log.error("Failed to release lock for file "
                                + targetFilePath + ". Is it acquired?");
                    }
                }
            }

            result = new FileOperationResult(
                    OPERATION_NAME,
                    true,
                    byteCountWritten);


            String injectOperationResultTo = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, INCLUDE_RESULT_TO);

            if (injectOperationResultTo.equals(FileConnectorConstants.MESSAGE_BODY)) {
                FileConnectorUtils.setResultAsPayload(messageContext, result);
            } else if (injectOperationResultTo.equals(FileConnectorConstants.MESSAGE_PROPERTY)) {
                String resultPropertyName = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, RESULT_PROPERTY_NAME);
                if (StringUtils.isNotEmpty(resultPropertyName)) {
                    OMElement resultEle = FileConnectorUtils.generateOperationResult(messageContext, result);
                    messageContext.setProperty(resultPropertyName, resultEle);
                } else {
                    throw new InvalidConfigurationException("Property name to set operation result is required");
                }
            } else {
                throw new InvalidConfigurationException("Parameter '" + INCLUDE_RESULT_TO + "' is mandatory");
            }


        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + targetFilePath;
            result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.INVALID_CONFIGURATION,
                    e.getMessage());

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + targetFilePath;
            result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.ILLEGAL_PATH,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (FileOperationException | IOException e) { //FileSystemException also handled here
            String errorDetail = ERROR_MESSAGE + targetFilePath;
            result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.OPERATION_ERROR,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (FileLockException e) {
            String errorDetail = ERROR_MESSAGE + targetFilePath;
            result = new FileOperationResult(
                    OPERATION_NAME,
                    false,
                    Error.FILE_LOCKING_ERROR,
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
     * @param targetFilePath Path to consider and modify
     * @param msgCtx         MessageContext to read operation configs
     * @return Same or modified path as per configs
     * @throws InvalidConfigurationException In case of config error
     */
    private String getModifiedFilePathForCompress(String targetFilePath, MessageContext msgCtx)
            throws InvalidConfigurationException {

        boolean compress = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, COMPRESS_PARAM));
        String fileWriteModeAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, WRITE_MODE_PARAM);
        FileWriteMode writeMode;
        if (StringUtils.isNotEmpty(fileWriteModeAsStr)) {
            writeMode = FileWriteMode.fromString(fileWriteModeAsStr);
        } else {
            throw new InvalidConfigurationException("Mandatory parameter '" + WRITE_MODE_PARAM + "' is not provided");
        }

        String fileNameWithExtension = targetFilePath.substring(targetFilePath.lastIndexOf(FileConnectorConstants.FILE_SEPARATOR));

        if ((!Objects.equals(writeMode, FileWriteMode.APPEND)) && compress) {
            String fileName = fileNameWithExtension.split("\\.")[0];
            String parentFolder = targetFilePath.substring(0, targetFilePath.lastIndexOf(FileConnectorConstants.FILE_SEPARATOR));
            targetFilePath = parentFolder + fileName + FileConnectorConstants.ZIP_FILE_EXTENSION;
        }

        return targetFilePath;

    }

    /**
     * Perform file writing.
     *
     * @param targetFile            File to write to
     * @param fileNameWithExtension File name with extension
     * @param msgCtx                MessageContext to read configs from
     * @return Written Bytes count
     * @throws IOException                   In case of I/O error
     * @throws FileOperationException        In case of any application error
     * @throws IllegalPathException          In case if invalid file path
     * @throws InvalidConfigurationException In case of  configs validation failure
     */
    private long writeToFile(FileObject targetFile, String fileNameWithExtension, MessageContext msgCtx)
            throws IOException, FileOperationException, IllegalPathException,
            InvalidConfigurationException {

        long writtenBytesCount = 0;
        FileWriteMode writeMode;
        String fileWriteModeAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, WRITE_MODE_PARAM);
        if (StringUtils.isNotEmpty(fileWriteModeAsStr)) {
            writeMode = FileWriteMode.fromString(fileWriteModeAsStr);
        } else {
            throw new InvalidConfigurationException("Mandatory parameter '" + WRITE_MODE_PARAM + "' is not provided");
        }

        //TODO: how to write an attachment to a file?

        String contentToWrite = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, CONTENT_OR_EXPRESSION_PARAM);
        String encoding = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, ENCODING_PARAM);
        if (StringUtils.isEmpty(encoding)) {
            encoding = FileConnectorConstants.DEFAULT_ENCODING;
        }
        String mimeType = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, MIME_TYPE_PARAM);
        boolean compress = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, COMPRESS_PARAM));
        boolean appendNewLine = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, APPEND_NEW_LINE_PARAM));
        boolean enableStreaming = Boolean.parseBoolean((String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, ENABLE_STREAMING_PARAM));
        int contentAppendPosition = Integer.MAX_VALUE;
        String contentPositionAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgCtx, APPEND_POSITION_PARAM);
        if (StringUtils.isNotEmpty(contentPositionAsStr)) {
            contentAppendPosition = Integer.parseInt(contentPositionAsStr);
        }

        if (enableStreaming) {
            contentAppendPosition = Integer.MAX_VALUE;
            appendNewLine = false;
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
                    throw new FileOperationException("Target file already exists. Path = "
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
                    throw new FileOperationException("Invalid file append position. Expecting a positive value");
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
                throw new FileOperationException("Unexpected File Write Mode: " + writeMode);
        }

        return writtenBytesCount;
    }

    /**
     * Execute writing static of evaluated content.
     *
     * @param targetFile            File to write to
     * @param fileNameWithExtension File name with extension
     * @param contentToWrite        static content to write
     * @param encoding              encoding to use
     * @param mimeType              mime type of the message
     * @param compress              true if need to compress and write
     * @return Bytes written to file
     * @throws IOException In case of I/O error
     */
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

    /**
     * Append new line at the end of the file.
     *
     * @param targetFile File to append
     * @return number of bytes written
     * @throws IOException In case of I/O error
     */
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


    /**
     * Append static content to a file.
     *
     * @param targetFile      File to append to
     * @param contentToAppend Static content
     * @param encoding        Encoding to use
     * @param position        Position of the file to append
     * @return Number of bytes written
     * @throws IOException In case of I/O error
     */
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

    /**
     * Write message in MessageContext to a given file.
     *
     * @param targetFile            File to write to
     * @param fileNameWithExtension File name with extension
     * @param messageContext        MessageContext to extract message from
     * @param append                True if to append content to file
     * @param mimeType              MIME type of the message
     * @param streaming             True if to extract stream and write to file
     * @param compress              True if to compress and write the file
     * @param appendNewLine         True if to append new line at the end
     * @return
     * @throws FileOperationException In case of connector error
     * @throws IOException            In case of I/O error
     */
    private long performBodyWrite(FileObject targetFile, String fileNameWithExtension, MessageContext messageContext,
                                  boolean append, String mimeType, boolean streaming,
                                  boolean compress, boolean appendNewLine)
            throws FileOperationException, IOException {

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
            throw new FileOperationException("Error while determining message "
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
     * Get the correct formatter for message.
     *
     * @param msgContext The message context associated
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
