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

import org.wso2.vfs.shared.log.UriMask;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.format.DataSourceMessageBuilder;
import org.apache.axis2.format.ManagedDataSource;
import org.apache.axis2.format.ManagedDataSourceFactory;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.lang.StringUtils;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFile;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.data.connector.DefaultConnectorResponse;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.BinaryRelayBuilder;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.ProgressClock;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.carbon.connector.utils.SourceGuard;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileReadMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;

import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements Read File operation
 */
public class ReadFile extends OperationFrame.Mediator {

    private static final String PATH_PARAM = "path";
    private static final String FILE_PATTERN_PARAM = "filePattern";
    private static final String ENABLE_LOCK_PARAM = "enableLock";
    private static final String READ_MODE_PARAM = "readMode";
    private static final String CONTENT_TYPE_PARAM = "contentType";
    private static final String ENCODING_PARAM = "encoding";
    private static final String ENABLE_STREAMING_PARAM = "enableStreaming";
    private static final String START_LINE_NUM_PARAM = "startLineNum";
    private static final String END_LINE_NUM_PARAM = "endLineNum";
    private static final String LINE_NUM_PARAM = "lineNum";
    private static final String CHARSET_PARAM = "charset";
    private static final String FILE_FILTER_TYPE = "fileFilterType";
    private static final String INCLUDE_FILES = "includeFiles";
    private static final String EXCLUDE_FILES = "excludeFiles";
    private static final String MIN_FILE_AGE = "minFileAge";
    private static final String MAX_FILE_AGE = "maxFileAge";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String METADATA_OUTPUT_FORMAT = "metadataOutputFormat";
    private static final String OPERATION_NAME = "read";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        Config config;
        String connection;
        try {
            config = readAndValidateInputs(messageContext);
            connection = Utils.getConnectionName(messageContext);
        } catch (InvalidConfigurationException | IllegalArgumentException invalid) {
            handleError(messageContext, invalid, Error.INVALID_CONFIGURATION, invalid.getMessage(),
                    responseVariable, Boolean.TRUE.equals(overwriteBody));
            return;
        }
        runFrame(messageContext, connection, config.retries, new OperationFrame.Operation<Void>() {
            private String selected;
            private String relative;
            private SourceGuard guard;

            public LinkedHashMap<String, OperationFrame.Lock> paths() {
                LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
                paths.put(config.path, OperationFrame.Lock.READ_INPUT);
                return paths;
            }

            public OperationFrame.Use use(String path) {
                return OperationFrame.Use.READ;
            }

            public OperationFrame.Retry retry() {
                return OperationFrame.Retry.READ;
            }

            public Void removed(String path) {
                throw new IllegalStateException("A read starts a new delivery");
            }

            public void options(FileSystemHandler handler) {
                Utils.addDiskShareAccessMaskToFSO(handler.getFsOptions(), config.accessMask, handler.getContext().getHealth().namedLog());
                ConnectionConfiguration connection = handler.getContext().getConfiguration();
                guard = new SourceGuard(config.pattern, config.filter, config.interval,
                        connection.getTempFolderName(), connection.getTempFileSuffix(),
                        new ErrorClassifier(handler.getContext().getHealth().namedLog()));
            }

            public Void steps(Attempt attempt) throws Exception {
                String input = attempt.readInput();
                attempt.progress().reset("resolve");
                FileObject source = attempt.handler().resolveFile(selected == null ? input : selected);
                attempt.progress().reset("stat");
                if (!AtomicOps.existsNow(source)) {
                    throw new OperationFrame.Failure(Error.ILLEGAL_PATH, "File or folder not found: " + input);
                }
                if (selected == null && source.isFolder()) {
                    if (guard.reservedDirectory(source)) {
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The directory name is reserved");
                    }
                    FileObject directory = source;
                    source = null;
                    attempt.progress().reset("list");
                    for (FileObject child : directory.getChildren()) {
                        if (!guard.accepts(child)) {
                            continue;
                        }
                        String name = directory.getName().getRelativeName(child.getName());
                        String path = childPath(input, escapedPath(name));
                        if (attempt.guardRead(path, lockPath(child, path), config.enableLock, true)) {
                            selected = path;
                            relative = childPath(config.path, name);
                            source = child;
                            break;
                        }
                    }
                    if (source == null) {
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, "No file matches the read guards in " + input);
                    }
                } else {
                    if (!guard.accepts(source)) {
                        throw new OperationFrame.Failure(Error.OPERATION_ERROR, "The file did not pass the read guards");
                    }
                    if (selected == null) {
                        selected = input;
                        relative = config.path;
                    }
                    attempt.guardRead(selected, lockPath(source, selected), config.enableLock, false);
                }
                deliver(attempt, selected, relative, source, messageContext, config, responseVariable, overwriteBody);
                return null;
            }
        }, OPERATION_NAME, responseVariable, overwriteBody);
    }

    private static String escapedPath(String providerPath) {
        // VFS has already escaped literal percent signs. A filename delimiter must survive a second resolution.
        return providerPath.replace("?", "%3F").replace("#", "%23");
    }

    private static String lockPath(FileObject file, String operand) {
        String path = file.getName().getPath();
        String canonical = file.getName().getRootURI() + escapedPath(path.substring(1));
        int query = operand.indexOf('?');
        return query < 0 ? canonical : canonical + operand.substring(query);
    }

    private static String childPath(String parent, String name) {
        int query = parent.indexOf('?');
        String path = query < 0 ? parent : parent.substring(0, query);
        return path + (path.endsWith("/") ? "" : "/") + name + (query < 0 ? "" : parent.substring(query));
    }

    private class Config {
        String path;
        Pattern pattern;
        AdvancedFileFilter filter;
        long interval;
        int retries;
        String accessMask;
        boolean enableLock;
        FileReadMode readMode = FileReadMode.COMPLETE_FILE;
        String contentType;
        String encoding;
        boolean enableStreaming;
        int startLineNum;
        int endLineNum;
        int lineNum;
        String charSet;
        String fileFilterType;
        String includeFiles;
        String excludeFiles;
        String maxFileAge;
        String timeBetweenSizeCheck;
        String metadataOutputFormat;
    }

    private Config readAndValidateInputs(MessageContext msgCtx) throws InvalidConfigurationException {
        Config config = new Config();
        config.path = Utils.
                lookUpStringParam(msgCtx, PATH_PARAM);
        String pattern = Utils.lookUpStringParam(msgCtx, FILE_PATTERN_PARAM, null);
        config.pattern = pattern == null ? null : Pattern.compile(pattern);
        config.retries = Integer.parseInt(Utils.lookUpStringParam(msgCtx, Const.MAX_RETRY_PARAM, "1"));
        config.accessMask = Utils.lookUpStringParam(msgCtx, Const.DISK_SHARE_ACCESS_MASK, null);

        config.enableLock = Utils.
                lookUpBooleanParam(msgCtx, ENABLE_LOCK_PARAM, false);
        config.readMode = FileReadMode.fromString(Utils.
                lookUpStringParam(msgCtx, READ_MODE_PARAM, FileReadMode.COMPLETE_FILE.getMode()));
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
        config.fileFilterType = Utils.
                lookUpStringParam(msgCtx, FILE_FILTER_TYPE, Const.EMPTY_STRING);
        config.includeFiles = Utils.
                lookUpStringParam(msgCtx, INCLUDE_FILES, Const.EMPTY_STRING);
        config.excludeFiles = Utils.
                lookUpStringParam(msgCtx, EXCLUDE_FILES, Const.EMPTY_STRING);
        config.maxFileAge = Utils.
                lookUpStringParam(msgCtx, MAX_FILE_AGE, Const.EMPTY_STRING);
        config.timeBetweenSizeCheck = Utils.
                lookUpStringParam(msgCtx, TIME_BETWEEN_SIZE_CHECK, Const.EMPTY_STRING);
        config.filter = new AdvancedFileFilter(config.fileFilterType, config.includeFiles, config.excludeFiles,
                config.maxFileAge, Utils.lookUpStringParam(msgCtx, MIN_FILE_AGE, null));
        config.interval = StringUtils.isEmpty(config.timeBetweenSizeCheck) ? 0 : Long.parseLong(config.timeBetweenSizeCheck);
        config.metadataOutputFormat = Utils.
                lookUpStringParam(msgCtx, METADATA_OUTPUT_FORMAT, "default");

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

    private void deliver(Attempt attempt, String path, String relative, FileObject file, MessageContext msgCtx,
                         Config config, String responseVariable, Boolean overwriteBody) throws Exception {
        attempt.progress().reset("stat");
        long size = file.getContent().getSize();
        long modified = file.getContent().getLastModifiedTime();
        NamedLog readLog = attempt.handler().getContext().getHealth().namedLog();
        Map<String, Object> attributes = getFileProperties(relative, file, config, size, modified, readLog);
        if (config.readMode == FileReadMode.METADATA_ONLY) {
            JsonObject result = generateOperationResult(msgCtx, new FileOperationResult(OPERATION_NAME, true));
            handleConnectorResponse(msgCtx, responseVariable, overwriteBody, result, null, attributes);
        } else {
            String type = StringUtils.isEmpty(config.contentType) ? getContentType(file) : config.contentType;
            if (config.enableStreaming) {
                if (!Boolean.TRUE.equals(overwriteBody)) {
                    throw new OperationFrame.Failure(Error.OPERATION_ERROR,
                            "The content cannot be stored in a variable while streaming is enabled");
                }
                setCharsetEncoding(config.encoding, type, msgCtx, readLog);
                Builder builder = selectSynapseMessageBuilder(msgCtx, type, readLog);
                if (!(builder instanceof DataSourceMessageBuilder)) {
                    throw new FileOperationException("Required builder for streaming not found");
                }
                StreamLease lease = attempt.publishRead(msgCtx, path, file, type, size, modified);
                try {
                    lease.checkDelivery();
                    ManagedDataSource data = ManagedDataSourceFactory.create(lease);
                    OMElement element = ((DataSourceMessageBuilder) builder).processDocument(data, type,
                            ((Axis2MessageContext) msgCtx).getAxis2MessageContext());
                    SOAPEnvelope envelope = TransportUtils.createSOAPEnvelope(element);
                    lease.checkDelivery();
                    msgCtx.setEnvelope(envelope);
                    ((Axis2MessageContext) msgCtx).getAxis2MessageContext()
                            .removeProperty(PassThroughConstants.NO_ENTITY_BODY);
                } catch (Exception failure) {
                    // The builder only wraps the stream here and reads nothing of the file.
                    Exception reported = failure instanceof OperationFrame.Failure ? failure : content(failure, type);
                    lease.rejectDelivery(reported);
                    throw reported;
                }
                return;
            }
            // With the body kept, the charset of the file decodes its content only. The message keeps its own.
            org.apache.axis2.context.MessageContext axis = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
            boolean keep = !Boolean.TRUE.equals(overwriteBody);
            Object charset = axis.getLocalProperty(Constants.Configuration.CHARACTER_SET_ENCODING);
            Object charsetSet = axis.getLocalProperty(Const.SET_CHARACTER_ENCODING);
            setCharsetEncoding(config.encoding, type, msgCtx, readLog);
            ConnectorResponse response = new DefaultConnectorResponse();
            try {
                readContent(attempt, file, msgCtx, config, type, readLog, keep, response);
            } finally {
                if (keep) {
                    restore(axis, Constants.Configuration.CHARACTER_SET_ENCODING, charset);
                    restore(axis, Const.SET_CHARACTER_ENCODING, charsetSet);
                }
            }
            response.setAttributes(attributes);
            msgCtx.setVariable(responseVariable, response);
        }
        if (config.enableLock || attempt.lock(path) != null) {
            attempt.publishRead(msgCtx, path, null, null, size, modified);
        }
    }

    private static void restore(org.apache.axis2.context.MessageContext axis, String name, Object value) {
        if (value == null) {
            axis.removeProperty(name);
        } else {
            axis.setProperty(name, value);
        }
    }

    private void readContent(Attempt attempt, FileObject file, MessageContext msgCtx, Config config, String type,
                             NamedLog readLog, boolean keep, ConnectorResponse response) throws Exception {
        OMElement documentElement;
        attempt.progress().reset("open");
        ReadStream raw = new ReadStream(file.getContent().getInputStream(), attempt.progress());
        try {
            documentElement = buildSynapseMessage(readFile(raw, config), msgCtx, type, readLog);
        } catch (Exception built) {
            Exception failed = raw.failure == null ? content(built, type) : built;
            ErrorClassifier classifier = new ErrorClassifier(attempt.handler().getContext().getHealth().namedLog());
            boolean transport = classifier.classify(failed, attempt.progress().elapsedMillis(),
                    ErrorClassifier.CallType.READ).getKind().isTransport();
            if (!transport || FileObjectUtils.isInstanceOf(file, LocalFile.class)) {
                try {
                    raw.close();
                } catch (IOException cleanup) {
                    if (!transport && classifier.classify(cleanup, attempt.progress().elapsedMillis(),
                            ErrorClassifier.CallType.READ).getKind().isTransport()) {
                        attempt.discardHandler();
                    }
                    if (failed != cleanup) {
                        failed.addSuppressed(cleanup);
                    }
                }
            }
            throw failed;
        }
        attempt.progress().reset("close");
        raw.close();
        if (!keep) {
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            ((Axis2MessageContext) msgCtx).getAxis2MessageContext().
                    removeProperty(PassThroughConstants.NO_ENTITY_BODY);
        } else {
            try {
                if (Const.CONTENT_TYPE_JSON.equalsIgnoreCase(type)) {
                    org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                            Axis2MessageContext) msgCtx).getAxis2MessageContext();
                    response.setPayload(JsonParser.parseString(
                            JsonUtil.jsonPayloadToString(axis2MsgCtx)));
                } else {
                    response.setPayload(documentElement.toString());
                }
            } catch (RuntimeException failed) {
                throw content(failed, type);
            }
        }
    }

    // A failure of the build or of the variable when the stream of the file did not fail is a failure of the
    // content. It fails this read alone and is no fault of the connection.
    private static FileSystemException content(Exception failure, String type) {
        FileSystemException content = new FileSystemException("The file content could not be read"
                + (type == null ? "" : " as " + type), failure);
        content.setFault(Fault.APPLICATION);
        return content;
    }

    // Keeps the failure of the stream of the file, so that a failure of the build can be told from it.
    private static final class ReadStream extends FilterInputStream {
        private final ProgressClock progress;
        private boolean closed;
        IOException failure;

        ReadStream(InputStream source, ProgressClock progress) {
            super(source);
            this.progress = progress;
        }

        @Override
        public int read() throws IOException {
            progress.reset("read");
            try {
                return in.read();
            } catch (IOException error) {
                failure = error;
                throw error;
            }
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            progress.reset("read");
            try {
                return in.read(data, offset, length);
            } catch (IOException error) {
                failure = error;
                throw error;
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return in.skip(count);
            } catch (IOException error) {
                failure = error;
                throw error;
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return in.available();
            } catch (IOException error) {
                failure = error;
                throw error;
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    super.close();
                } catch (IOException error) {
                    failure = error;
                    throw error;
                }
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
     * @param responseVariable Response variable name
     * @param overwriteBody Overwrite body
     */
    private void handleError(MessageContext msgCtx, Exception e, Error error, String errorDetail,
                             String responseVariable, boolean overwriteBody) {

        errorDetail = Utils.maskURLPassword(errorDetail);
        FileOperationResult result = new FileOperationResult(OPERATION_NAME, false, error, e.getMessage());
        JsonObject resultJSON = generateOperationResult(msgCtx, result);
        handleConnectorResponse(msgCtx, responseVariable, overwriteBody, resultJSON, null, null);
        handleException(errorDetail, new InvalidConfigurationException(UriMask.mask(e.getMessage())), msgCtx);
    }

    /**
     * Get properties of file being read into the messageContext.
     *
     * @param filePath   Path of the file being read
     * @param file       File object being read
     * @param config     Configuration with metadata format settings
     * @throws FileSystemException If relevant information cannot be read from file
     */
    private Map<String, Object> getFileProperties(String filePath, FileObject file, Config config, long size,
                                                  long modified, NamedLog readLog) throws FileSystemException {

        Map<String, Object> fileProperties = new HashMap<>();
        
        // Choose date format based on metadata output format
        SimpleDateFormat sdf = null;
        switch (config.metadataOutputFormat.toLowerCase()) {
            case "iso8601":
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
                break;
            case "unix":
                // For Unix timestamp, we'll store the raw long value
                fileProperties.put(Const.FILE_LAST_MODIFIED_TIME, String.valueOf(modified));
                break;
            case "detailed":
                sdf = new SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss z");
                break;
            case "simple":
                sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                break;
            default: // "default" or unrecognized format
                sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
                break;
        }
        
        // Format last modified time if not using Unix timestamp
        if (!config.metadataOutputFormat.equalsIgnoreCase("unix") && sdf != null) {
            String lastModifiedTime = sdf.format(modified);
            fileProperties.put(Const.FILE_LAST_MODIFIED_TIME, lastModifiedTime);
        }
        
        // Add basic properties
        fileProperties.put(Const.FILE_IS_DIR, String.valueOf(file.isFolder()));
        fileProperties.put(Const.FILE_PATH, filePath);
        fileProperties.put(Const.FILE_URL, file.getName().getFriendlyURI());
        fileProperties.put(Const.FILE_NAME, file.getName().getBaseName());
        fileProperties.put(Const.FILE_NAME_WITHOUT_EXTENSION, file.getName().
                getBaseName().split("\\.")[0]);
        //The size of the file, in bytes
        fileProperties.put(Const.FILE_SIZE, String.valueOf(size));
        
        // Add extended properties for "detailed" format
        if (config.metadataOutputFormat.equalsIgnoreCase("detailed")) {
            // Add file extension
            String fileName = file.getName().getBaseName();
            int lastDotIndex = fileName.lastIndexOf('.');
            if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
                fileProperties.put("fileExtension", fileName.substring(lastDotIndex + 1));
            } else {
                fileProperties.put("fileExtension", "");
            }
            
            // Add readable file size (e.g., "1.5 MB", "256 KB")
            long sizeInBytes = size;
            String readableSize = getReadableFileSize(sizeInBytes);
            fileProperties.put("fileSizeReadable", readableSize);
            
            // Add file permissions if available
            try {
                if (file.isReadable()) {
                    fileProperties.put("isReadable", "true");
                }
                if (file.isWriteable()) {
                    fileProperties.put("isWritable", "true");
                }
                if (file.isExecutable()) {
                    fileProperties.put("isExecutable", "true");
                }
                if (file.isHidden()) {
                    fileProperties.put("isHidden", "true");
                }
            } catch (Exception e) {
                // Permissions might not be available for all file systems
                readLog.debug("Could not retrieve all file permissions: " + e.getMessage());
            }
            
            // Add content type
            try {
                String contentType = file.getContent().getContentInfo().getContentType();
                if (contentType != null) {
                    fileProperties.put("contentType", contentType);
                }
            } catch (Exception e) {
                readLog.debug("Could not retrieve content type: " + e.getMessage());
            }
            
            // Add parent directory
            try {
                FileObject parent = file.getParent();
                if (parent != null) {
                    fileProperties.put("parentPath", parent.getName().getPath());
                }
            } catch (Exception e) {
                readLog.debug("Could not retrieve parent path: " + e.getMessage());
            }
        }
        
        return fileProperties;
    }
    
    /**
     * Convert file size in bytes to human-readable format.
     *
     * @param sizeInBytes File size in bytes
     * @return Human-readable file size string
     */
    private String getReadableFileSize(long sizeInBytes) {
        if (sizeInBytes < 1024) {
            return sizeInBytes + " B";
        } else if (sizeInBytes < 1024 * 1024) {
            return String.format("%.2f KB", sizeInBytes / 1024.0);
        } else if (sizeInBytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", sizeInBytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", sizeInBytes / (1024.0 * 1024 * 1024));
        }
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
    private void setCharsetEncoding(String charSetEnc, String contentType, MessageContext msgContext, NamedLog readLog) {
        if (StringUtils.isEmpty(charSetEnc)) {
            try {
                charSetEnc = new ContentType(contentType).getParameter(CHARSET_PARAM);
            } catch (ParseException ex) {
                readLog.warn("FileConnector:read - Invalid encoding type. " + ex.getMessage());
            }
        }
        ((Axis2MessageContext) msgContext).getAxis2MessageContext().
                setProperty(Const.SET_CHARACTER_ENCODING, "true");
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
     * @param in       Stream owned by the caller
     * @param config   Input config
     * @return InputStream to the file
     * @throws FileOperationException In case of I/O error
     */
    private InputStream readFile(InputStream in, Config config) throws FileOperationException {
        switch (config.readMode) {
            case STARTING_FROM_LINE:
                return processStream(in, config.startLineNum, 0);
            case UP_TO_LINE:
                return processStream(in, 1, config.endLineNum);
            case BETWEEN_LINES:
                return processStream(in, config.startLineNum, config.endLineNum);
            case SPECIFIC_LINE:
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String line = reader.lines().skip(config.lineNum - 1L).findFirst().orElse("");
                return new ByteArrayInputStream(line.getBytes());
            case COMPLETE_FILE:
                return in;
            default:
                throw new IllegalStateException("Unexpected value: " + config.readMode);
        }
    }

    /**
     * Build synapse message using inputStream. This will read the stream
     * completely and build the complete message.
     *
     * @param inputStream         Stream to file content
     * @param msgCtx              Message context
     * @param contentType         MIME type of the message
     * @throws FileOperationException In case of building the message
     */
    private OMElement buildSynapseMessage(InputStream inputStream, MessageContext msgCtx,
                                     String contentType, NamedLog readLog) throws FileOperationException {

        try {
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            Builder builder = selectSynapseMessageBuilder(msgCtx, contentType, readLog);
            OMElement documentElement = builder.processDocument(inputStream, contentType, axis2MsgCtx);
            //We need this to build the complete message before closing the stream
            documentElement.toString();
            return documentElement;
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
    private Builder selectSynapseMessageBuilder(MessageContext msgCtx, String contentType, NamedLog readLog) throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                Axis2MessageContext) msgCtx).getAxis2MessageContext();

        Builder builder;
        if (StringUtils.isEmpty(contentType)) {
            readLog.debug("No content type specified. Using RELAY builder.");
            builder = new BinaryRelayBuilder();
        } else {
            int index = contentType.indexOf(';');
            String type = index > 0 ? contentType.substring(0, index) : contentType;
            builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
            if (builder == null) {
                if (readLog.isDebugEnabled()) {
                    readLog.debug("No message builder found for type '" + type + "'. Falling back "
                            + "to RELAY builder.");
                }
                builder = new BinaryRelayBuilder();
            }
        }
        return builder;
    }
}
