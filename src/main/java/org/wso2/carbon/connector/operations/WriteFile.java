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
import org.wso2.carbon.connector.filelock.FileLockManager;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileWriteMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.relay.ExpandingMessageFormatter;

import java.io.BufferedReader;
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
    private static final String UPDATE_LAST_MODIFIED_TIMESTAMP = "updateLastModified";
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

        String targetFilePath = null;
        FileObject targetFile = null;
        FileOperationResult result;
        FileLockManager fileLockManager = null;
        boolean lockAcquired = false;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        try {

            Config config = readAndValidateInputs(messageContext);
            targetFilePath = config.targetFilePath;
            fileSystemHandlerConnection = Utils.getFileSystemHandler(connectionName);

            //if compress is enabled we need to resolve a zip file
            targetFilePath = getModifiedFilePathForCompress(targetFilePath, messageContext);

            targetFilePath = fileSystemHandlerConnection.getBaseDirectoryPath() + targetFilePath;

            FileSystemManager fsManager = fileSystemHandlerConnection.getFsManager();
            FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
            targetFile = fsManager.resolveFile(targetFilePath, fso);

            fileLockManager = fileSystemHandlerConnection.getFileLockManager();

            if (targetFile.isFolder()) {
                throw new IllegalPathException("Path does not point to a file " + targetFilePath);
            }

            //lock the file if enabled
            if (config.enableLocking) {
                lockAcquired = fileLockManager.tryAndAcquireLock(targetFilePath, Const.DEFAULT_LOCK_TIMEOUT);
                if (!lockAcquired) {
                    throw new FileLockException("Failed to acquire lock for file "
                            + targetFilePath + ". Another process maybe processing it. ");
                }
            }

            int byteCountWritten;

            byteCountWritten = (int) writeToFile(targetFile, messageContext, config);
            if (!targetFile.getURL().toString().startsWith(Const.FTP_PROTOCOL_PREFIX) && config.updateLastModified) {
                targetFile.getContent().setLastModifiedTime(System.currentTimeMillis());
            }
            result = new FileOperationResult(
                    OPERATION_NAME,
                    true,
                    byteCountWritten);


            if (config.includeResultTo.equals(Const.MESSAGE_BODY)) {
                Utils.setResultAsPayload(messageContext, result);
            } else if (config.includeResultTo.equals(Const.MESSAGE_PROPERTY)) {
                OMElement resultEle = Utils.generateOperationResult(messageContext, result);
                messageContext.setProperty(config.resultPropertyName, resultEle);
            }

        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + targetFilePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + targetFilePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);

        } catch (FileOperationException | IOException e) { //FileSystemException also handled here

            String errorDetail = ERROR_MESSAGE + targetFilePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (FileLockException e) {
            String errorDetail = ERROR_MESSAGE + targetFilePath;
            handleError(messageContext, e, Error.FILE_LOCKING_ERROR, errorDetail);
        } finally {
            if (targetFile != null) {
                try {
                    targetFile.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing folder object while reading files in "
                            + targetFile);
                }
            }
            if (handler.getStatusOfConnection(Const.CONNECTOR_NAME, connectionName)) {
                if (fileSystemHandlerConnection != null) {
                    handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
                }
            }

            if (fileLockManager != null && lockAcquired) {
                fileLockManager.releaseLock(targetFilePath);
            }
        }
    }

    private Config readAndValidateInputs(MessageContext msgCtx) throws InvalidConfigurationException {
        Config config = new Config();
        config.targetFilePath = Utils.lookUpStringParam(msgCtx, FILE_PATH_PARAM);
        config.enableLocking = Utils.lookUpBooleanParam(msgCtx, ENABLE_LOCK_PARAM, false);
        config.updateLastModified = Utils.lookUpBooleanParam(msgCtx, UPDATE_LAST_MODIFIED_TIMESTAMP, true);
        config.includeResultTo = Utils.lookUpStringParam(msgCtx, INCLUDE_RESULT_TO, Const.MESSAGE_BODY);
        String resultPropertyName = Utils.lookUpStringParam(msgCtx, RESULT_PROPERTY_NAME, Const.EMPTY_STRING);
        if (config.includeResultTo.equals(Const.MESSAGE_PROPERTY) && StringUtils.isEmpty(resultPropertyName)) {
            throw new InvalidConfigurationException("Parameter '" + RESULT_PROPERTY_NAME + "' is required.");
        } else {
            config.resultPropertyName = resultPropertyName;
        }
        config.compress = Utils.lookUpBooleanParam(msgCtx, COMPRESS_PARAM, false);
        config.writeMode = FileWriteMode.fromString(Utils.lookUpStringParam(msgCtx, WRITE_MODE_PARAM, FileWriteMode.OVERWRITE.toString()));
        config.contentToWrite = Utils.lookUpStringParam(msgCtx, CONTENT_OR_EXPRESSION_PARAM, Const.EMPTY_STRING);
        config.encoding = Utils.lookUpStringParam(msgCtx, ENCODING_PARAM, Const.DEFAULT_ENCODING);
        config.mimeType = Utils.lookUpStringParam(msgCtx, MIME_TYPE_PARAM, Const.EMPTY_STRING);
        config.appendNewLine = Utils.lookUpBooleanParam(msgCtx, APPEND_NEW_LINE_PARAM, false);
        config.enableStreaming = Utils.lookUpBooleanParam(msgCtx, ENABLE_STREAMING_PARAM, false);
        String appendPosition = Utils.lookUpStringParam(msgCtx, APPEND_POSITION_PARAM, String.valueOf(Integer.MAX_VALUE));
        config.appendPosition = Integer.parseInt(appendPosition);

        config.fileNameWithExtension = config.targetFilePath.
                substring(config.targetFilePath.lastIndexOf(Const.FILE_SEPARATOR) + 1);

        return config;

    }

    private class Config {
        String targetFilePath;
        String fileNameWithExtension;
        boolean enableLocking = false;
        String includeResultTo;
        String resultPropertyName;
        boolean compress = false;
        FileWriteMode writeMode = FileWriteMode.OVERWRITE;
        String contentToWrite;
        String encoding = Const.DEFAULT_ENCODING;
        String mimeType;
        boolean appendNewLine = false;
        boolean enableStreaming = false;
        int appendPosition = Integer.MAX_VALUE;
        boolean updateLastModified = true;
    }

    /**
     * Sets error to context and handle.
     *
     * @param msgCtx      Message Context to set info
     * @param e           Exception associated
     * @param error       Error code
     * @param errorDetail Error detail
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail) {
        errorDetail = Utils.maskURLPassword(errorDetail);
        Utils.setError(OPERATION_NAME, msgCtx, e, error, errorDetail);
        handleException(errorDetail, e, msgCtx);
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

        String fileNameWithExtension = targetFilePath.substring(targetFilePath.lastIndexOf(Const.FILE_SEPARATOR));

        if ((!Objects.equals(writeMode, FileWriteMode.APPEND)) && compress) {
            String fileName = fileNameWithExtension.split("\\.")[0];
            String parentFolder = targetFilePath.substring(0, targetFilePath.lastIndexOf(Const.FILE_SEPARATOR));
            targetFilePath = parentFolder + fileName + Const.ZIP_FILE_EXTENSION;
        }

        return targetFilePath;

    }

    /**
     * Perform file writing.
     *
     * @param targetFile File to write to
     * @param msgCtx     MessageContext to read configs from
     * @return Written Bytes count
     * @throws IOException            In case of I/O error
     * @throws FileOperationException In case of any application error
     * @throws IllegalPathException   In case if invalid file path
     */
    private long writeToFile(FileObject targetFile, MessageContext msgCtx, Config config)
            throws IOException, FileOperationException, IllegalPathException {

        long writtenBytesCount;

        //TODO: how to write an attachment to a file?

        if (config.enableStreaming) {
            config.appendPosition = Integer.MAX_VALUE;
            config.appendNewLine = false;
        }

        boolean contentToWriteIsProvided = false;
        if (StringUtils.isNotEmpty(config.contentToWrite)) {
            contentToWriteIsProvided = true;
            if (config.appendNewLine) {
                config.contentToWrite = config.contentToWrite + Const.NEW_LINE;
            }
        }

        switch (config.writeMode) {
            case CREATE_NEW:
                if (targetFile.exists()) {
                    throw new FileOperationException("Target file already exists. Path = "
                            + targetFile.getURL());
                } else {
                    targetFile.createFile();
                    if (contentToWriteIsProvided) {
                        writtenBytesCount = performContentWrite(targetFile, config);
                    } else {
                        writtenBytesCount = performBodyWrite(targetFile, msgCtx, false, config);
                    }
                }
                break;
            case OVERWRITE:
                targetFile.createFile();
                if (contentToWriteIsProvided) {
                    writtenBytesCount = performContentWrite(targetFile, config);
                } else {
                    writtenBytesCount = performBodyWrite(targetFile, msgCtx, false, config);
                }
                break;
            case APPEND:
                if (config.appendPosition <= 0) {
                    throw new FileOperationException("Invalid file append position. Expecting a positive value");
                }
                if (!targetFile.exists()) {
                    throw new IllegalPathException("File to append is not found: " + targetFile.getURL());
                } else {
                    if (contentToWriteIsProvided) {
                        writtenBytesCount = performContentAppend(targetFile, config);
                    } else {
                        writtenBytesCount = performBodyWrite(targetFile, msgCtx, true, config);
                    }
                }
                break;

            default:
                throw new FileOperationException("Unexpected File Write Mode: " + config.writeMode);
        }

        return writtenBytesCount;
    }

    /**
     * Execute writing static of evaluated content.
     *
     * @param targetFile File to write to
     * @param config     Input configs
     * @return Bytes written to file
     * @throws IOException In case of I/O error
     */
    private long performContentWrite(FileObject targetFile, Config config) throws IOException {
        CountingOutputStream out = null;
        try {
            if (config.compress) {
                ZipEntry zipEntry = new ZipEntry(config.fileNameWithExtension);
                ZipOutputStream zipOutputStream = new ZipOutputStream(targetFile.getContent().getOutputStream());
                zipOutputStream.putNextEntry(zipEntry);
                out = new CountingOutputStream(zipOutputStream);
            } else {
                out = new CountingOutputStream(targetFile.getContent().getOutputStream());
            }
            if (Objects.equals(config.mimeType, Const.CONTENT_TYPE_BINARY)) {
                // Write binary content decoded from a base64 string
                byte[] decoded = Base64.getDecoder().decode(config.contentToWrite);
                out.write(decoded);
            } else if (StringUtils.isNotEmpty(config.encoding)) {
                IOUtils.write(config.contentToWrite, out, config.encoding);
            } else {
                IOUtils.write(config.contentToWrite, out, Const.DEFAULT_ENCODING);
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
            IOUtils.write(Const.NEW_LINE, outputStream);
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
     * @param targetFile File to append to
     * @param config     Input configs
     * @return Number of bytes written
     * @throws IOException In case of I/O error
     */
    private long performContentAppend(FileObject targetFile, Config config) throws IOException {
        BufferedReader reader = null;
        CountingOutputStream out = null;
        try {
            reader = new BufferedReader(new InputStreamReader(targetFile.getContent().getInputStream()));
            List<String> lines = reader.lines().collect(Collectors.toList());
            if (config.appendPosition <= lines.size()) {
                lines.add(config.appendPosition - 1, config.contentToWrite);
            } else {
                if (config.appendPosition != Integer.MAX_VALUE) {
                    log.warn("FileConnector:write - Append position is greater than the existing line count of file "
                            + targetFile.getName().getBaseName()
                            + ". Hence appending the content at EOF.");
                }
                lines.add(config.contentToWrite);
            }

            out = new CountingOutputStream(targetFile.getContent().getOutputStream());
            IOUtils.writeLines(lines, null, out, config.encoding);
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
     * @param targetFile     File to write to
     * @param messageContext MessageContext to extract message from
     * @param append         True if to append content to file
     * @param config         Input config
     * @return Number of bytes written
     * @throws FileOperationException In case of connector error
     * @throws IOException            In case of I/O error
     */
    private long performBodyWrite(FileObject targetFile, MessageContext messageContext,
                                  boolean append, Config config) throws FileOperationException, IOException {

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();
        //override message type set
        if (StringUtils.isNotEmpty(config.mimeType) &&
                !(config.mimeType.equals(Const.CONTENT_TYPE_AUTOMATIC))) {

            axis2MessageContext.setProperty(Const.MESSAGE_TYPE, config.mimeType);
        }
        MessageFormatter messageFormatter;
        if (config.enableStreaming) {
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
                if (config.compress) {
                    ZipOutputStream zipOutputStream = new ZipOutputStream(targetFile.getContent().
                            getOutputStream(append));
                    ZipEntry zipEntry = new ZipEntry(config.fileNameWithExtension);
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
            if (!config.compress && config.appendNewLine) {
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
