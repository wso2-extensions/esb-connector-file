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
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.passthru.util.BinaryRelayBuilder;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.exception.FileLockException;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.filelock.FileLockManager;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileReadMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.FileObjectDataSource;

import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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

    private static final String PATH_PARAM = "path";
    private static final String FILE_PATTERN_PARAM = "filePattern";
    private static final String ENABLE_LOCK_PARAM = "enableLock";
    private static final String READ_MODE_PARAM = "readMode";
    private static final String INCLUDE_RESULT_TO = "includeResultTo";
    private static final String RESULT_PROPERTY_NAME = "resultPropertyName";
    private static final String CONTENT_TYPE_PARAM = "contentType";
    private static final String ENCODING_PARAM = "encoding";
    private static final String ENABLE_STREAMING_PARAM = "enableStreaming";
    private static final String START_LINE_NUM_PARAM = "startLineNum";
    private static final String END_LINE_NUM_PARAM = "endLineNum";
    private static final String LINE_NUM_PARAM = "lineNum";
    private static final String CHARSET_PARAM = "charset";
    private static final String OPERATION_NAME = "read";
    private static final String ERROR_MESSAGE = "Error while performing file:read for file/directory ";

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {

        String sourcePath = null;
        FileObject fileObject = null;
        FileOperationResult result;
        FileLockManager fileLockManager = null;
        boolean lockAcquired = false;

        try {

            String connectionName = Utils.getConnectionName(messageContext);
            Config config = readAndValidateInputs(messageContext);

            FileSystemHandler fileSystemHandler = Utils.getFileSystemHandler(connectionName);
            String workingDirRelativePAth = config.path;
            sourcePath = fileSystemHandler.getBaseDirectoryPath() + config.path;

            FileSystemManager fsManager = fileSystemHandler.getFsManager();
            FileSystemOptions fso = fileSystemHandler.getFsOptions();
            fileObject = fsManager.resolveFile(sourcePath, fso);

            fileLockManager = fileSystemHandler.getFileLockManager();

            if (!fileObject.exists()) {
                throw new IllegalPathException("File or folder not found: " + sourcePath);
            }

            if (fileObject.isFolder()) {
                //select file to read
                fileObject = selectFileToRead(fileObject, config.filePattern);
                workingDirRelativePAth = workingDirRelativePAth + Const.FILE_SEPARATOR
                        + fileObject.getName().getBaseName();
                sourcePath = fileSystemHandler.getBaseDirectoryPath() + workingDirRelativePAth;
            }

            //lock the file if enabled
            if (config.enableLock) {
                lockAcquired = fileLockManager.tryAndAcquireLock(sourcePath, Const.DEFAULT_LOCK_TIMEOUT);
                if (!lockAcquired) {
                    throw new FileLockException("Failed to acquire lock for file "
                            + sourcePath + ". Another process maybe processing it. ");
                }
            }

            readFileMetadata(fileObject, messageContext, workingDirRelativePAth);

            //if we need to read metadata only, no need to touch content
            if (Objects.equals(config.readMode, FileReadMode.METADATA_ONLY)) {
                result = new FileOperationResult(OPERATION_NAME, true);
                Utils.setResultAsPayload(messageContext, result);
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("FileConnector:read  - preparing to read file content " + sourcePath
                        + " of Content-type : " + config.contentType);
            }

            readFileContent(fileObject, messageContext, config);

            //TODO:MTOM Support?


        } catch (InvalidConfigurationException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail);

        } catch (IllegalPathException e) {

            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail);

        } catch (FileOperationException | IOException e) { //FileSystemException also handled here
            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.OPERATION_ERROR, errorDetail);

        } catch (FileLockException e) {
            String errorDetail = ERROR_MESSAGE + sourcePath;
            handleError(messageContext, e, Error.FILE_LOCKING_ERROR, errorDetail);

        } finally {

            if (fileObject != null) {
                try {
                    fileObject.close();
                } catch (FileSystemException e) {
                    log.error(Const.CONNECTOR_NAME
                            + ":Error while closing folder object while reading files in "
                            + fileObject);
                }
            }

            if (fileLockManager != null && lockAcquired) {
                fileLockManager.releaseLock(sourcePath);
            }
        }
    }

    private class Config {
        String path;
        String filePattern;
        boolean enableLock;
        FileReadMode readMode = FileReadMode.COMPLETE_FILE;
        String includeResultTo;
        String resultPropertyName;
        String contentType;
        String encoding;
        boolean enableStreaming;
        int startLineNum;
        int endLineNum;
        int lineNum;
        String charSet;
    }

    private Config readAndValidateInputs(MessageContext msgCtx) throws InvalidConfigurationException {
        Config config = new Config();
        config.path = Utils.
                lookUpStringParam(msgCtx, PATH_PARAM);
        config.filePattern = Utils.
                lookUpStringParam(msgCtx, FILE_PATTERN_PARAM, Const.MATCH_ALL_REGEX);
        config.enableLock = Utils.
                lookUpBooleanParam(msgCtx, ENABLE_LOCK_PARAM, false);
        config.readMode = FileReadMode.fromString(Utils.
                lookUpStringParam(msgCtx, READ_MODE_PARAM, FileReadMode.COMPLETE_FILE.getMode()));
        config.includeResultTo = Utils.
                lookUpStringParam(msgCtx, INCLUDE_RESULT_TO, Const.MESSAGE_BODY);
        config.resultPropertyName = Utils.
                lookUpStringParam(msgCtx, RESULT_PROPERTY_NAME, Const.EMPTY_STRING);
        config.contentType = Utils.
                lookUpStringParam(msgCtx, CONTENT_TYPE_PARAM, Const.EMPTY_STRING);
        config.encoding = Utils.
                lookUpStringParam(msgCtx, ENCODING_PARAM, Const.DEFAULT_ENCODING);
        config.enableStreaming = Utils.
                lookUpBooleanParam(msgCtx, ENABLE_STREAMING_PARAM, false);
        config.startLineNum = Integer.
                parseInt(Utils.lookUpStringParam(msgCtx, START_LINE_NUM_PARAM, "0"));
        config.endLineNum = Integer.
                parseInt(Utils.lookUpStringParam(msgCtx, END_LINE_NUM_PARAM, "0"));
        config.lineNum = Integer.
                parseInt(Utils.lookUpStringParam(msgCtx, LINE_NUM_PARAM, "0"));
        config.charSet = Utils.
                lookUpStringParam(msgCtx, CHARSET_PARAM, Const.EMPTY_STRING);

        if (config.includeResultTo.equals(Const.MESSAGE_PROPERTY)
                && StringUtils.isEmpty(config.resultPropertyName)) {
            throw new InvalidConfigurationException("Parameter resultPropertyName is not provided");
        }

        if(config.readMode == null) {
            throw new InvalidConfigurationException("Unknown file read mode");
        }

        switch (config.readMode) {
            case STARTING_FROM_LINE:
                if (config.startLineNum == 0) {
                    throw new InvalidConfigurationException("Parameter '"
                            + START_LINE_NUM_PARAM + "' is required for selected read mode");
                } else if (config.startLineNum < 0) {
                    throw new InvalidConfigurationException("Parameter '"
                            + START_LINE_NUM_PARAM + "' must be positive");
                }
                break;
            case UP_TO_LINE:
                if (config.endLineNum == 0) {
                    throw new InvalidConfigurationException("Parameter '"
                            + END_LINE_NUM_PARAM + "' is required for selected read mode");
                } else if (config.endLineNum < 0) {
                    throw new InvalidConfigurationException("Parameter '"
                            + END_LINE_NUM_PARAM + "' must be positive");
                }
                break;
            case BETWEEN_LINES:
                if (config.startLineNum == 0 || config.endLineNum == 0) {
                    throw new InvalidConfigurationException("Parameters 'startLineNum' and"
                            + " 'endLineNumber' are required for selected read mode");
                } else if (config.startLineNum < 0 || config.endLineNum < 0) {
                    throw new InvalidConfigurationException("Parameter 'startLineNum' and"
                            + " 'endLineNumber' must be positive");
                } else if (config.endLineNum < config.startLineNum) {
                    throw new InvalidConfigurationException("Parameter 'endLineNumber' "
                            + "should be greater than 'startLineNum'");
                }
                break;
            case SPECIFIC_LINE:
                if (config.lineNum == 0) {
                    throw new InvalidConfigurationException("Parameter 'lineNum' is required for selected read mode");
                } else if (config.lineNum < 0) {
                    throw new InvalidConfigurationException("Parameter 'lineNum' should be positive");
                }
                break;
            case COMPLETE_FILE:
                break;
            case METADATA_ONLY:
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + config.readMode.toString());
        }

        return config;
    }

    private void readFileMetadata(FileObject file, MessageContext msgCtx, String workingDirRelativePAth)
            throws FileSystemException {
        //set metadata as context properties
        setFileProperties(workingDirRelativePAth, file, msgCtx);
    }

    private void readFileContent(FileObject file, MessageContext msgCtx, Config config)
            throws IOException, FileOperationException {

        if (StringUtils.isEmpty(config.contentType)) {
            config.contentType = getContentType(file);
        }
        setCharsetEncoding(config.encoding, config.contentType, msgCtx);
        //read and build file content
        if (config.enableStreaming) {
            //here underlying stream to the file content is not closed. We keep it open
            setStreamToSynapse(file, config.resultPropertyName, msgCtx, config.contentType);
        } else {
            //this will close input stream automatically after building message
            try (InputStream inputStream = readFile(file, config)) {
                buildSynapseMessage(inputStream, config.resultPropertyName, msgCtx, config.contentType);
            }
        }
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
     * Select file to read from the directory provided.
     *
     * @param directory   directory to scan
     * @param filePattern file pattern to search files
     * @return File selected
     * @throws FileSystemException    in case of file related issue
     * @throws FileOperationException if no file can be selected
     */
    private FileObject selectFileToRead(FileObject directory, String filePattern)
            throws FileSystemException, FileOperationException {

        FileObject fileToRead = null;
        FileObject[] children = directory.getChildren();

        if (children == null || children.length == 0) {

            throw new FileOperationException("There is no immediate files to read in the folder " + directory.getURL());

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
                throw new FileOperationException("There is no immediate files to "
                        + "read that matches with given pattern in the folder "
                        + directory.getURL());
            }

        } else {

            fileToRead = children[0];

        }

        return fileToRead;
    }

    /**
     * Set properties of file being read into the messageContext.
     *
     * @param filePath   Path of the file being read
     * @param file       File object being read
     * @param msgContext MessageContext associated
     * @throws FileSystemException If relevant information cannot be read from file
     */
    private void setFileProperties(String filePath, FileObject file,
                                   MessageContext msgContext) throws FileSystemException {

        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
        String lastModifiedTime = sdf.format(file.getContent().getLastModifiedTime());
        msgContext.setProperty(Const.FILE_LAST_MODIFIED_TIME, lastModifiedTime);
        msgContext.setProperty(Const.FILE_IS_DIR, file.isFolder());
        msgContext.setProperty(Const.FILE_PATH, filePath);
        msgContext.setProperty(Const.FILE_URL, file.getName().getFriendlyURI());
        msgContext.setProperty(Const.FILE_NAME, file.getName().getBaseName());
        msgContext.setProperty(Const.FILE_NAME_WITHOUT_EXTENSION, file.getName().
                getBaseName().split("\\.")[0]);
        //The size of the file, in bytes
        msgContext.setProperty(Const.FILE_SIZE, file.getContent().getSize());
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
                charSetEnc = new ContentType(contentType).getParameter(CHARSET_PARAM);
            } catch (ParseException ex) {
                log.warn("FileConnector:read - Invalid encoding type.", ex);
            }
        }
        ((Axis2MessageContext) msgContext).getAxis2MessageContext().
                setProperty(Const.SET_CHARACTER_ENCODING, true);
        ((Axis2MessageContext) msgContext).getAxis2MessageContext().
                setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEnc);
    }

    /**
     * Process Stream and generate a new stream
     * as per reading conditions.
     *
     * @param in        Original Input Stream
     * @param startLine Line to start reading
     * @param endLine   Line to read up to
     * @return Processed stream
     * @throws FileOperationException in case of input error
     */
    private InputStream processStream(InputStream in, int startLine, int endLine)
            throws FileOperationException {

        Stream<String> tempStream;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        if ((startLine > 0) && (endLine > 0) && (endLine > startLine)) {
            tempStream = reader.lines().skip((long) startLine - 1).limit((long) (endLine - startLine + 1));
        } else if (startLine > 0) {
            tempStream = reader.lines().skip((long) startLine - 1);
        } else if (endLine > 0) {
            tempStream = reader.lines().limit(endLine);
        } else {
            throw new FileOperationException("File connector:read Error while processing stream ");
        }
        return new ByteArrayInputStream(tempStream.collect(
                Collectors.joining(Const.NEW_LINE)).getBytes());
    }


    /**
     * Read file and generate a InputStream.
     *
     * @param file     File to read
     * @param config   Input config
     * @return InputStream to the file
     * @throws FileOperationException In case of I/O error
     */
    private InputStream readFile(FileObject file, Config config)
            throws FileOperationException {

        InputStream processedStream;

        try {
            InputStream in = new AutoCloseInputStream(file.getContent().getInputStream());
            switch (config.readMode) {
                case STARTING_FROM_LINE:
                    processedStream = processStream(in, config.startLineNum, 0);
                    break;
                case UP_TO_LINE:
                    processedStream = processStream(in, 1, config.endLineNum);
                    break;
                case BETWEEN_LINES:
                    processedStream = processStream(in, config.startLineNum, config.endLineNum);
                    break;
                case SPECIFIC_LINE:
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    Optional lineFound = reader.lines().skip((long) (config.lineNum - 1)).findFirst();
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
                    throw new IllegalStateException("Unexpected value: " + config.readMode.toString());
            }
            return processedStream;

        } catch (IOException e) {
            throw new FileOperationException("File connector:read - Error while reading file ", e);
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
     * @throws FileOperationException In case of synapse related or runtime issue
     */
    private void setStreamToSynapse(FileObject file, String contentPropertyName, MessageContext msgCtx,
                                    String contentType) throws FileOperationException {

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
                throw new FileOperationException("Required builder for streaming not found");
            }
        } catch (AxisFault e) {
            throw new FileOperationException("Axis2 error while setting file content stream to synapse", e);
        } catch (OMException e) {
            throw new FileOperationException("Error while setting file content stream to synapse", e);
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
     * @throws FileOperationException In case of building the message
     */
    private void buildSynapseMessage(InputStream inputStream, String contentPropertyName, MessageContext msgCtx,
                                     String contentType) throws FileOperationException {

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
            throw new FileOperationException("Axis2 error while building message from Stream", e);
        } catch (OMException e) {
            throw new FileOperationException("Error while building message from Stream", e);
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
                            + "to RELAY builder.");
                }
                builder = new BinaryRelayBuilder();
            }
        }
        return builder;
    }
}
