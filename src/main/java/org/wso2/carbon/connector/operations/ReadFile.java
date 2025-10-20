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
import com.google.gson.JsonParser;
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
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSelectInfo;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.data.connector.DefaultConnectorResponse;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.BinaryRelayBuilder;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.AbstractConnectorOperation;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.FileLockException;
import org.wso2.carbon.connector.exception.FileOperationException;
import org.wso2.carbon.connector.exception.IllegalPathException;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.exception.ConnectionSuspendedException;
import org.wso2.carbon.connector.filelock.FileLockManager;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import org.wso2.carbon.connector.pojo.FileReadMode;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.carbon.connector.utils.Const;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.carbon.connector.utils.AdvancedFileFilter;
import org.wso2.carbon.connector.utils.FileObjectDataSource;

import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.wso2.carbon.connector.utils.Utils.generateOperationResult;

/**
 * Implements Read File operation
 */
public class ReadFile extends AbstractConnectorOperation {

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
    private static final String MAX_FILE_AGE = "maxFileAge";
    private static final String TIME_BETWEEN_SIZE_CHECK = "timeBetweenSizeCheck";
    private static final String METADATA_OUTPUT_FORMAT = "metadataOutputFormat";
    private static final String OPERATION_NAME = "read";
    private static final String ERROR_MESSAGE = "Error while performing file:read for file/directory ";

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody)
            throws ConnectException {

        String sourcePath = null;
        FileObject fileObject = null;
        FileLockManager fileLockManager = null;
        boolean lockAcquired = false;
        FileSystemHandler fileSystemHandlerConnection = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        String connectionName = Utils.getConnectionName(messageContext);
        String connectorName = Const.CONNECTOR_NAME;
        int maxRetries;
        int retryDelay;
        int attempt = 0;
        boolean successOperation = false;
        String diskShareAccessMask = (String) ConnectorUtils.lookupTemplateParamater
                (messageContext, Const.DISK_SHARE_ACCESS_MASK);
        //read max retries and retry delay
        try {
            maxRetries = Integer.parseInt((String) ConnectorUtils.lookupTemplateParamater(messageContext,
                    Const.MAX_RETRY_PARAM));
            retryDelay = Integer.parseInt((String) ConnectorUtils.lookupTemplateParamater(messageContext,
                    Const.RETRY_DELAY_PARAM));
            if (log.isDebugEnabled()) {
                log.debug("Max retries: " + maxRetries + " Retry delay: " + retryDelay);
            }
        } catch (Exception e) {
            maxRetries = 0;
            retryDelay = 0;
        }
        while (attempt <= maxRetries && !successOperation) {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("FileConnector:read  - reading file from " + connectionName);
                }
                Config config = readAndValidateInputs(messageContext);

                fileSystemHandlerConnection = Utils.getFileSystemHandler(connectionName);
                String workingDirRelativePAth = config.path;
                sourcePath = fileSystemHandlerConnection.getBaseDirectoryPath() + config.path;

                FileSystemOptions fso = fileSystemHandlerConnection.getFsOptions();
                Utils.addDiskShareAccessMaskToFSO(fso, diskShareAccessMask);

                // Use suspension-enabled file resolution for FTP/FTPS
                fileObject = fileSystemHandlerConnection.resolveFileWithSuspension(sourcePath);

                fileLockManager = fileSystemHandlerConnection.getFileLockManager();

                if (!fileObject.exists()) {
                    throw new IllegalPathException("File or folder not found: " + sourcePath);
                }

                if (fileObject.isFolder()) {
                    //select file to read with advanced filtering if provided
                    if (StringUtils.isNotEmpty(config.fileFilterType) || StringUtils.isNotEmpty(config.includeFiles) ||
                        StringUtils.isNotEmpty(config.excludeFiles) || StringUtils.isNotEmpty(config.maxFileAge)) {
                        fileObject = selectFileToReadWithAdvancedFilter(fileObject, config);
                    } else {
                        fileObject = selectFileToRead(fileObject, config.filePattern);
                    }
                    workingDirRelativePAth = workingDirRelativePAth + Const.FILE_SEPARATOR
                            + fileObject.getName().getBaseName();
                    sourcePath = fileSystemHandlerConnection.getBaseDirectoryPath() + workingDirRelativePAth;
                }

                // Check file stability if timeBetweenSizeCheck is provided
                if (StringUtils.isNotEmpty(config.timeBetweenSizeCheck) && !isFileStable(fileObject, config.timeBetweenSizeCheck)) {
                    throw new FileOperationException("File is not stable (still being written). Cannot read at this time: " + sourcePath);
                }

                //lock the file if enabled
                if (config.enableLock) {
                    lockAcquired = fileLockManager.tryAndAcquireLock(sourcePath, Const.DEFAULT_LOCK_TIMEOUT);
                    if (!lockAcquired) {
                        throw new FileLockException("Failed to acquire lock for file "
                                + sourcePath + ". Another process maybe processing it. ");
                    }
                }

                Map<String, Object> fileAttributes = getFileProperties(workingDirRelativePAth, fileObject, config);

                //if we need to read metadata only, no need to touch content
                if (Objects.equals(config.readMode, FileReadMode.METADATA_ONLY)) {
                    JsonObject resultJSON = generateOperationResult(messageContext,
                            new FileOperationResult(OPERATION_NAME, true));
                    handleConnectorResponse(messageContext, responseVariable, overwriteBody, resultJSON, null, fileAttributes);
                    return;
                }

                if (log.isDebugEnabled()) {
                    log.debug("FileConnector:read  - preparing to read file content " + sourcePath
                            + " of Content-type : " + config.contentType);
                }

                readFileContent(fileObject, messageContext, config, fileAttributes, responseVariable, overwriteBody);
                successOperation = true;
                //TODO:MTOM Support?


            } catch (InvalidConfigurationException e) {

                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.INVALID_CONFIGURATION, errorDetail, responseVariable, overwriteBody);

            } catch (IllegalPathException e) {

                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.ILLEGAL_PATH, errorDetail, responseVariable, overwriteBody);

            } catch (ConnectionSuspendedException e) {
                // Clean logging for suspended connections - no stack trace needed
                log.warn("Connection suspended: " + e.getMessage());
                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.CONNECTION_ERROR, errorDetail, responseVariable, overwriteBody);

            } catch (FileOperationException | IOException e) { //FileSystemException also handled here
                log.error(e);
                Utils.closeFileSystem(fileObject);
                if (attempt >= maxRetries - 1) {
                    String errorDetail = ERROR_MESSAGE + sourcePath;
                    handleError(messageContext, e, Error.RETRY_EXHAUSTED, errorDetail, responseVariable, overwriteBody);
                }
                // Log the retry attempt
                log.warn(Const.CONNECTOR_NAME + ":Error while read "
                        + sourcePath + ". Retrying after " + retryDelay + " milliseconds retry attempt " + (attempt + 1)
                        + " out of " + maxRetries);
                attempt++;
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    handleError(messageContext, ie, Error.OPERATION_ERROR, ERROR_MESSAGE + sourcePath,
                            responseVariable, overwriteBody);
                }

            } catch (FileLockException e) {
                String errorDetail = ERROR_MESSAGE + sourcePath;
                handleError(messageContext, e, Error.FILE_LOCKING_ERROR, errorDetail, responseVariable, overwriteBody);

            } catch (Exception e) {
                String errorDetail = ERROR_MESSAGE + sourcePath;

                // Clean logging for connection pool errors
                if (e.getMessage() != null && e.getMessage().contains("Could not create a validated object")) {
                    log.warn("Connection pool validation failed: " + e.getMessage());
                } else if (e.getCause() instanceof java.util.NoSuchElementException) {
                    log.warn("Unable to obtain connection from pool (server may be down)");
                } else {
                    log.error(errorDetail, e);
                }

                Utils.closeFileSystem(fileObject);
                if (attempt >= maxRetries - 1) {
                    handleError(messageContext, e, Error.RETRY_EXHAUSTED, errorDetail, responseVariable, overwriteBody);
                }
                // Log the retry attempt
                log.warn(Const.CONNECTOR_NAME + ":Error while read "
                        + sourcePath + ". Retrying after " + retryDelay + " milliseconds retry attempt " + (attempt + 1)
                        + " out of " + maxRetries);
                attempt++;
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted status
                    handleError(messageContext, ie, Error.OPERATION_ERROR, ERROR_MESSAGE + sourcePath,
                            responseVariable, overwriteBody);
                }
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
                if (handler.getStatusOfConnection(Const.CONNECTOR_NAME, connectionName)) {
                    if (fileSystemHandlerConnection != null) {
                        Utils.addMaxAccessMaskToFSO(fileSystemHandlerConnection.getFsOptions());
                        handler.returnConnection(connectorName, connectionName, fileSystemHandlerConnection);
                    }
                }
                if (fileLockManager != null && lockAcquired) {
                    fileLockManager.releaseLock(sourcePath);
                }
            }
        }
    }

    private class Config {
        String path;
        String filePattern;
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
        config.filePattern = Utils.
                lookUpStringParam(msgCtx, FILE_PATTERN_PARAM, Const.MATCH_ALL_REGEX);
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

    private void readFileContent(FileObject file, MessageContext msgCtx, Config config, Map<String, Object> attributes,
                                 String responseVariable, Boolean overwriteBody)
            throws IOException, FileOperationException {

        if (StringUtils.isEmpty(config.contentType)) {
            config.contentType = getContentType(file);
        }
        setCharsetEncoding(config.encoding, config.contentType, msgCtx);
        //read and build file content
        if (config.enableStreaming) {
            if (overwriteBody != null && overwriteBody) {
                //here underlying stream to the file content is not closed. We keep it open
                setStreamToSynapse(file, msgCtx, config.contentType);
            } else {
                handleError(msgCtx, new AxisFault("The content cannot be stored in a variable while streaming is enabled"),
                        Error.OPERATION_ERROR, "The content cannot be stored in a variable while streaming is enabled",
                        responseVariable, false);
            }
        } else {
            //this will close input stream automatically after building message
            try (InputStream inputStream = readFile(file, config)) {
                OMElement documentElement =
                        buildSynapseMessage(inputStream, msgCtx, config.contentType);
                ConnectorResponse response = new DefaultConnectorResponse();
                if (overwriteBody != null && overwriteBody) {
                    msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
                    ((Axis2MessageContext) msgCtx).getAxis2MessageContext().
                            removeProperty(PassThroughConstants.NO_ENTITY_BODY);
                } else {
                    if (Const.CONTENT_TYPE_JSON.equalsIgnoreCase(config.contentType)) {
                        org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                                Axis2MessageContext) msgCtx).getAxis2MessageContext();
                        response.setPayload(JsonParser.parseString(
                                JsonUtil.jsonPayloadToString(axis2MsgCtx)));
                    } else {
                        response.setPayload(documentElement.toString());
                    }
                }
                response.setAttributes(attributes);
                msgCtx.setVariable(responseVariable, response);
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
     * Select file to read from the directory provided using advanced filtering.
     *
     * @param directory directory to scan
     * @param config    configuration containing filter parameters
     * @return File selected
     * @throws FileSystemException    in case of file related issue
     * @throws FileOperationException if no file can be selected
     */
    private FileObject selectFileToReadWithAdvancedFilter(FileObject directory, Config config)
            throws FileSystemException, FileOperationException {

        FileObject[] children = directory.getChildren();

        if (children == null || children.length == 0) {
            throw new FileOperationException("There is no immediate files to read in the folder " + directory.getURL());
        }

        // Create advanced file filter
        AdvancedFileFilter filter = new AdvancedFileFilter(config.fileFilterType, config.includeFiles, 
                                                          config.excludeFiles, config.maxFileAge);

        // Find first file that matches advanced filtering criteria
        for (FileObject child : children) {
            if (child.isFile()) {
                // Check file stability if required
                if (StringUtils.isNotEmpty(config.timeBetweenSizeCheck) && !isFileStable(child, config.timeBetweenSizeCheck)) {
                    log.warn("File is not stable (still being written), skipping: " + child.getName().getBaseName());
                    continue;
                }
                
                // Apply advanced filtering
                if (acceptFile(filter, child)) {
                    return child;
                }
            }
        }

        throw new FileOperationException("No files found that match the advanced filtering criteria in folder " + directory.getURL());
    }

    /**
     * Checks if a file is stable by comparing its size over time.
     *
     * @param file The file to check for stability
     * @param sizeCheckInterval Time to wait between size checks in milliseconds
     * @return true if file size is stable, false otherwise
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
            
            // Refresh and check size again
            file.refresh();
            long finalSize = file.getContent().getSize();
            
            return initialSize == finalSize;
        } catch (Exception e) {
            log.warn("Error checking file stability for " + file + ": " + e.getMessage());
            return true; // Assume stable if we can't check
        }
    }

    /**
     * Helper method to check if a file object matches the advanced filter criteria.
     *
     * @param filter The advanced file filter
     * @param file   The file object to check
     * @return true if the file matches the filter criteria, false otherwise
     */
    private boolean acceptFile(AdvancedFileFilter filter, FileObject file) {
        try {
            // Create a simple FileSelectInfo wrapper for the FileObject
            FileSelectInfo fileSelectInfo = new FileSelectInfo() {
                @Override
                public FileObject getFile() {
                    return file;
                }

                @Override
                public int getDepth() {
                    return 0; // Not used in our filtering logic
                }

                @Override
                public FileObject getBaseFolder() {
                    try {
                        return file.getParent();
                    } catch (Exception e) {
                        return null;
                    }
                }
            };
            
            return filter.accept(fileSelectInfo);
        } catch (Exception e) {
            log.warn("Error checking file filter for " + file + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Get properties of file being read into the messageContext.
     *
     * @param filePath   Path of the file being read
     * @param file       File object being read
     * @param config     Configuration with metadata format settings
     * @throws FileSystemException If relevant information cannot be read from file
     */
    private Map<String, Object> getFileProperties(String filePath, FileObject file, Config config) throws FileSystemException {

        Map<String, Object> fileProperties = new HashMap<>();
        
        // Choose date format based on metadata output format
        SimpleDateFormat sdf = null;
        switch (config.metadataOutputFormat.toLowerCase()) {
            case "iso8601":
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
                break;
            case "unix":
                // For Unix timestamp, we'll store the raw long value
                fileProperties.put(Const.FILE_LAST_MODIFIED_TIME, String.valueOf(file.getContent().getLastModifiedTime()));
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
            String lastModifiedTime = sdf.format(file.getContent().getLastModifiedTime());
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
        fileProperties.put(Const.FILE_SIZE, String.valueOf(file.getContent().getSize()));
        
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
            long sizeInBytes = file.getContent().getSize();
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
                log.debug("Could not retrieve all file permissions: " + e.getMessage());
            }
            
            // Add content type
            try {
                String contentType = file.getContent().getContentInfo().getContentType();
                if (contentType != null) {
                    fileProperties.put("contentType", contentType);
                }
            } catch (Exception e) {
                log.debug("Could not retrieve content type: " + e.getMessage());
            }
            
            // Add parent directory
            try {
                FileObject parent = file.getParent();
                if (parent != null) {
                    fileProperties.put("parentPath", parent.getName().getPath());
                }
            } catch (Exception e) {
                log.debug("Could not retrieve parent path: " + e.getMessage());
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
    private void setCharsetEncoding(String charSetEnc, String contentType, MessageContext msgContext) {
        if (StringUtils.isEmpty(charSetEnc)) {
            try {
                charSetEnc = new ContentType(contentType).getParameter(CHARSET_PARAM);
            } catch (ParseException ex) {
                log.warn("FileConnector:read - Invalid encoding type.", ex);
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
     * @param msgCtx              MessageContext
     * @param contentType         MIME type of the message to build
     * @throws FileOperationException In case of synapse related or runtime issue
     */
    private void setStreamToSynapse(FileObject file, MessageContext msgCtx,
                                    String contentType) throws FileOperationException {

        try {
            ManagedDataSource dataSource = ManagedDataSourceFactory.create(new FileObjectDataSource(file, contentType));
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            Builder builder = selectSynapseMessageBuilder(msgCtx, contentType);
            if (builder instanceof DataSourceMessageBuilder) {
                OMElement documentElement = ((DataSourceMessageBuilder) builder).
                        processDocument(dataSource, contentType, axis2MsgCtx);
                msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            } else {
                log.error("FileConnector:read - Failed to process document. There is no message builder class " +
                        "available of type DataSourceMessageBuilder that supports the contentType: " + contentType);
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
     * @param msgCtx              Message context
     * @param contentType         MIME type of the message
     * @throws FileOperationException In case of building the message
     */
    private OMElement buildSynapseMessage(InputStream inputStream, MessageContext msgCtx,
                                     String contentType) throws FileOperationException {

        try {
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            Builder builder = selectSynapseMessageBuilder(msgCtx, contentType);
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
