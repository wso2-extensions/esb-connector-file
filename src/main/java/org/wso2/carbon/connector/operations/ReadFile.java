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
import org.apache.axiom.om.OMException;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.format.DataSourceMessageBuilder;
import org.apache.axis2.format.ManagedDataSource;
import org.apache.axis2.format.ManagedDataSourceFactory;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.apache.synapse.transport.passthru.util.BinaryRelayBuilder;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.core.util.PayloadUtils;
import org.wso2.carbon.connector.exception.ConnectorOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileReadMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.FileConnectorConstants;
import org.wso2.carbon.connector.utils.FileConnectorUtils;
import org.wso2.carbon.connector.utils.FileObjectDataSource;

import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements Read File operation
 */
public class ReadFile extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String operationName = "read";
        String errorMessage = "Error while performing file:read for file/directory ";

        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String sourcePath = null;
        FileObject fileObject = null;
        FileReadMode readMode = FileReadMode.COMPLETE_FILE;
        FileOperationResult result;

        try {

            String connectionName = FileConnectorUtils.getConnectionName(messageContext);
            sourcePath = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "path");

            if (StringUtils.isEmpty(sourcePath)) {
                throw new InvalidConfigurationException("Parameter 'path' is not provided ");
            }

            FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                    .getConnection(FileConnectorConstants.CONNECTOR_NAME, connectionName);
            String workingDirRelativePAth = sourcePath;
            sourcePath = fileSystemHandler.getBaseDirectoryPath() + sourcePath;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            fileObject = fsManager.resolveFile(sourcePath, fso);


            if (!fileObject.exists()) {
                throw new IllegalPathException("File or folder not found: " + sourcePath);
            }

            if (fileObject.isFolder()) {
                String filePattern = (String) ConnectorUtils.
                        lookupTemplateParamater(messageContext, "filePattern");
                //select file to read
                fileObject = selectFileToRead(fileObject, filePattern);
                workingDirRelativePAth = workingDirRelativePAth + File.separator + fileObject.getName().getBaseName();
            }

            //set metadata as context properties
            setFileProperties(workingDirRelativePAth, fileObject, messageContext);

            //lock the file if enabled
            boolean lockFile = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "enableLock"));
            if (lockFile) {
                //TODO: seems API does not have anything default
            }


            String fileReadModeAsStr = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "readMode");
            if (StringUtils.isNotEmpty(fileReadModeAsStr)) {
                readMode = FileReadMode.fromString(fileReadModeAsStr);
            } else {
                throw new InvalidConfigurationException("Mandatory parameter 'readMode' is not provided");
            }

            //if we need to read metadata only, no need to touch content
            if (Objects.equals(readMode, FileReadMode.METADATA_ONLY)) {
                result = new FileOperationResult(
                        operationName,
                        true);
                FileConnectorUtils.setResultAsPayload(messageContext, result);
                return;
            }

            String contentPropertyName = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "contentProperty");
            String contentType = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "contentType");
            String encoding = (String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "encoding");
            boolean isStreaming = Boolean.parseBoolean((String) ConnectorUtils.
                    lookupTemplateParamater(messageContext, "enableStreaming"));


            if (StringUtils.isEmpty(contentType)) {
                contentType = getContentType(fileObject);
            }

            if (log.isDebugEnabled()) {
                log.debug("FileConnector:read  - preparing to read file " + sourcePath
                        + " of Content-type : " + contentType);
            }

            setCharsetEncoding(encoding, contentType, messageContext);

            //read and build file content
            if (isStreaming) {
                //here underlying stream to the file content is not closed. We keep it open
                setStreamToSynapse(fileObject, contentPropertyName, messageContext, contentType);
            } else {
                //this will close input stream automatically after building message
                try (InputStream inputStream = readFile(fileObject, readMode, messageContext)) {
                    buildSynapseMessage(inputStream, contentPropertyName, messageContext, contentType);
                }
            }

            //TODO:MTOM Support?

            //TODO:finally, release the lock if acquired


        } catch (InvalidConfigurationException e) {

            String errorDetail = errorMessage + sourcePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.INVALID_CONFIGURATION,
                    e.getMessage());

            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (IllegalPathException e) {

            String errorDetail = errorMessage + sourcePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.ILLEGAL_PATH,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } catch (ConnectorOperationException | IOException e) { //FileSystemException also handled here
            String errorDetail = errorMessage + sourcePath;
            result = new FileOperationResult(
                    operationName,
                    false,
                    Error.OPERATION_ERROR,
                    e.getMessage());
            FileConnectorUtils.setResultAsPayload(messageContext, result);
            handleException(errorDetail, e, messageContext);

        } finally {

            if (fileObject != null) {
                try {
                    fileObject.close();
                } catch (FileSystemException e) {
                    log.error(FileConnectorConstants.CONNECTOR_NAME
                            + ":Error while closing folder object while reading files in "
                            + fileObject);
                }
            }
        }
    }


    /**
     * Select file to read from the directory provided.
     *
     * @param directory   directory to scan
     * @param filePattern file pattern to search files
     * @return File selected
     * @throws FileSystemException         in case of file related issue
     * @throws ConnectorOperationException if no file can be selected
     */
    private FileObject selectFileToRead(FileObject directory, String filePattern)
            throws FileSystemException, ConnectorOperationException {

        FileObject fileToRead = null;
        FileObject[] children = directory.getChildren();

        if (children == null || children.length == 0) {

            throw new ConnectorOperationException("There is no immediate files to read in the folder " + directory.getURL());

        } else if (StringUtils.isNotEmpty(filePattern)) {

            boolean bFound = false;
            for (FileObject child : children) {
                if (child.getName().getBaseName().matches(filePattern)) {
                    fileToRead = child;
                    bFound = true;
                    break;
                }
            }
            if (!bFound) {
                throw new ConnectorOperationException("There is no immediate files to "
                        + "read that matches with given pattern in the folder "
                        + directory.getURL());
            }

        } else {

            fileToRead = children[0];

        }

        return fileToRead;
    }

    private void setFileProperties(String filePath, FileObject file,
                                   MessageContext msgContext) throws FileSystemException {

        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
        String lastModifiedTime = sdf.format(file.getContent().getLastModifiedTime());
        msgContext.setProperty(FileConnectorConstants.FILE_LAST_MODIFIED_TIME, lastModifiedTime);
        msgContext.setProperty(FileConnectorConstants.FILE_IS_DIR, file.isFolder());
        msgContext.setProperty(FileConnectorConstants.FILE_PATH, filePath);
        msgContext.setProperty(FileConnectorConstants.FILE_URL, file.getName().getFriendlyURI());
        msgContext.setProperty(FileConnectorConstants.FILE_NAME, file.getName().getBaseName());
        msgContext.setProperty(FileConnectorConstants.FILE_NAME_WITHOUT_EXTENSION, file.getName().
                getBaseName().split("\\.")[0]);
        //The size of the file, in bytes
        //TODO: check if this reads the content. If so, can be expensive.
        msgContext.setProperty(FileConnectorConstants.FILE_SIZE, file.getContent().getSize());
    }

    /**
     * Get content-type using VFS library. This uses 'FileNameMap' of Java.
     * This works for .xml, .txt, .png files etc.
     *
     * @param file file object
     * @return content type of file or null
     * @throws FileSystemException in case of file system issue
     */
    private String getContentType(FileObject file) throws FileSystemException {
        return file.getContent().getContentInfo().getContentType();
    }

    /**
     * Extract the charset encoding from the configured content type and
     * set the CHARACTER_SET_ENCODING property as e.g. SOAPBuilder relies on this.
     *
     * @param charSetEnc  Charset Encoding to use. If null this will be derived
     * @param contentType Content-type of the message
     * @param msgContext  Synapse message context involved
     */
    private void setCharsetEncoding(String charSetEnc, String contentType, MessageContext msgContext) {
        if (StringUtils.isEmpty(charSetEnc)) {
            try {
                charSetEnc = new ContentType(contentType).getParameter("charset");
            } catch (ParseException ex) {
                log.warn("FileConnector:read - Invalid encoding type.", ex);
            }
        }
        msgContext.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEnc);
    }

    /**
     * Process Stream and generate a new stream
     * as per reading conditions.
     *
     * @param in        Original Input Stream
     * @param startLine Line to start reading
     * @param endLine   Line to read up to
     * @return Processed stream
     * @throws ConnectorOperationException in case of input error
     */
    private InputStream processStream(InputStream in, int startLine, int endLine)
            throws ConnectorOperationException {

        Stream<String> tempStream;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        if ((startLine > 0) && (endLine > 0) && (endLine > startLine)) {
            tempStream = reader.lines().skip((long) startLine - 1).limit((long) (endLine - startLine + 1));
        } else if (startLine > 0) {
            tempStream = reader.lines().skip((long) startLine - 1);
        } else if (endLine > 0) {
            tempStream = reader.lines().limit(endLine);
        } else {
            throw new ConnectorOperationException("File connector:read Error while processing stream ");
        }
        return new ByteArrayInputStream(tempStream.collect(
                Collectors.joining(FileConnectorConstants.NEW_LINE)).toString().getBytes());
    }


    private InputStream readFile(FileObject file, FileReadMode readMode, MessageContext msgContext)
            throws InvalidConfigurationException, ConnectorOperationException {

        int startLine = 0;
        int endLine = 0;
        int specificLine = 0;

        //read input params
        String startLineAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, "startLineNum");
        String endLineAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, "endLineNum");
        String specificLineAsStr = (String) ConnectorUtils.
                lookupTemplateParamater(msgContext, "lineNum");

        if (StringUtils.isNotEmpty(startLineAsStr)) {
            startLine = Integer.parseInt(startLineAsStr);
        }
        if (StringUtils.isNotEmpty(endLineAsStr)) {
            endLine = Integer.parseInt(endLineAsStr);
        }
        if (StringUtils.isNotEmpty(specificLineAsStr)) {
            specificLine = Integer.parseInt(specificLineAsStr);
        }

        InputStream processedStream;

        try {
            InputStream in = new AutoCloseInputStream(file.getContent().getInputStream());

            switch (readMode) {

                case STARTING_FROM_LINE:

                    if (startLine == 0) {
                        throw new InvalidConfigurationException("Parameter 'startLineNum' is required for selected read mode");
                    } else if (startLine < 0) {
                        throw new InvalidConfigurationException("Parameter 'startLineNum' must be positive");
                    }
//                    char tmpChar = 'a';
//                    int count = 0;
//                    while (tmpChar != -1 && count < startLine) {
//                        tmpChar = (char) in.read();
//                        if (tmpChar == '\n')
//                            count++;
//                    }
//                    processedStream = in;
                    processedStream = processStream(in, startLine, 0);
                    break;

                case UP_TO_LINE:

                    if (endLine == 0) {
                        throw new InvalidConfigurationException("Parameter 'endLineNum' is required for selected read mode");
                    } else if (endLine < 0) {
                        throw new InvalidConfigurationException("Parameter 'endLineNum' must be positive");
                    }
                    processedStream = processStream(in, 1, endLine);
                    break;

                case BETWEEN_LINES:

                    if (startLine == 0 || endLine == 0) {
                        throw new InvalidConfigurationException("Parameters 'startLineNum' and"
                                + " 'endLineNumber' are required for selected read mode");
                    } else if (startLine < 0 || endLine < 0) {
                        throw new InvalidConfigurationException("Parameter 'startLineNum' and"
                                + " 'endLineNumber' must be positive");
                    } else if (endLine < startLine) {
                        throw new InvalidConfigurationException("Parameter 'endLineNumber' "
                                + "should be greater than 'startLineNum'");
                    }
                    processedStream = processStream(in, startLine, endLine);
                    break;

                case SPECIFIC_LINE:

                    if (specificLine == 0) {
                        throw new InvalidConfigurationException("Parameter 'lineNum' is required for selected read mode");
                    } else if (specificLine < 0) {
                        throw new InvalidConfigurationException("Parameter 'lineNum' should be positive");
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));

                    Optional lineFound = reader.lines().skip((long) (specificLine - 1)).findFirst();
                    String line = "";
                    if (lineFound.isPresent()) {
                        line = (String) lineFound.get();
                    }
                    processedStream = new ByteArrayInputStream(line.getBytes());
                    break;

                case COMPLETE_FILE:

                    processedStream = in;
                    break;

                default:
                    throw new IllegalStateException("Unexpected value: " + readMode);
            }

            return processedStream;

        } catch (IOException e) {
            throw new ConnectorOperationException("File connector:read - Error while reading file ", e);
        }
    }


    /**
     * Set InputStream to the file to Synapse.
     * We will not close it as it will be read by another operation.
     *
     * @param file                File to read
     * @param contentPropertyName Property name to set content
     * @param msgCtx              MessageContext
     * @param contentType         MIME type of the message to build
     * @throws ConnectorOperationException In case of synapse related or runtime issue
     */
    private void setStreamToSynapse(FileObject file, String contentPropertyName, MessageContext msgCtx,
                                    String contentType) throws ConnectorOperationException {

        try {
            ManagedDataSource dataSource = ManagedDataSourceFactory.create(new FileObjectDataSource(file, contentType));
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            Builder builder = selectSynapseMessageBuilder(msgCtx, contentType);
            if (builder instanceof DataSourceMessageBuilder) {
                OMElement documentElement = ((DataSourceMessageBuilder) builder).
                        processDocument(dataSource, contentType, axis2MsgCtx);
                if (StringUtils.isNotEmpty(contentPropertyName)) {
                    msgCtx.setProperty(contentPropertyName, documentElement);
                } else {
                    msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
                }
            } else {
                log.error("FileConnector:read - selected builder for streaming should be DataSourceMessageBuilder. "
                        + "Maybe it is not registered to the contentType of this message");
                throw new ConnectorOperationException("Required builder for streaming not found");
            }
        } catch (AxisFault e) {
            throw new ConnectorOperationException("Axis2 error while setting file content stream to synapse", e);
        } catch (OMException e) {
            throw new ConnectorOperationException("Error while setting file content stream to synapse", e);
        }
    }

    /**
     * Build synapse message using inputStream. This will read the stream
     * completely and build the complete message.
     *
     * @param inputStream         Stream to file content
     * @param contentPropertyName Property name to set content
     * @param msgCtx              Message context
     * @param contentType         MIME type of the message
     * @throws ConnectorOperationException In case of building the message
     */
    private void buildSynapseMessage(InputStream inputStream, String contentPropertyName, MessageContext msgCtx,
                                     String contentType) throws ConnectorOperationException {

        try {
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            Builder builder = selectSynapseMessageBuilder(msgCtx, contentType);
            OMElement documentElement = builder.processDocument(inputStream, contentType, axis2MsgCtx);
            //We need this to build the complete message before closing the stream
            documentElement.toString();
            if (StringUtils.isNotEmpty(contentPropertyName)) {
                msgCtx.setProperty(contentPropertyName, documentElement);
            } else {
                msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            }
        } catch (AxisFault e) {
            throw new ConnectorOperationException("Axis2 error while building message from Stream", e);
        } catch (OMException e) {
            throw new ConnectorOperationException("Error while building message from Stream", e);
        }
    }


    /**
     * Determine the message builder to use. Selection is done
     * on the content-type.
     *
     * @param msgCtx      MessageContext in use
     * @param contentType Content type of the message
     * @return Builder selected to build the message
     * @throws AxisFault in case of selecting the builder
     */
    private Builder selectSynapseMessageBuilder(MessageContext msgCtx, String contentType) throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                Axis2MessageContext) msgCtx).getAxis2MessageContext();

        Builder builder;
        if (StringUtils.isEmpty(contentType)) {
            log.debug("No content type specified. Using RELAY builder.");
            builder = new BinaryRelayBuilder();
        } else {
            int index = contentType.indexOf(';');
            String type = index > 0 ? contentType.substring(0, index) : contentType;
            builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
            if (builder == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No message builder found for type '" + type + "'. Falling back "
                            + "to" + " RELAY builder.");
                }
                builder = new BinaryRelayBuilder();
            }
        }
        return builder;
    }
}
