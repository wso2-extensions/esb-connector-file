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

package org.wso2.carbon.connector.utils;

import com.google.gson.JsonObject;
import com.hierynomus.msdtyp.AccessMask;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;
import org.wso2.integration.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;

import static org.apache.synapse.SynapseConstants.PASSWORD_PATTERN;
import static org.apache.synapse.SynapseConstants.URL_PATTERN;

/**
 * Util methods related to file connector operations
 */
public class Utils {

    private static final Log log = LogFactory.getLog(Utils.class);

    /**
     * Sets the error code and error detail in message
     *
     * @param messageContext Message Context
     * @param error          Error to be set
     */
    public static void setErrorPropertiesToMessage(MessageContext messageContext, Error error) {

        messageContext.setProperty(Const.PROPERTY_ERROR_CODE, error.getErrorCode());
        messageContext.setProperty(Const.PROPERTY_ERROR_MESSAGE, error.getErrorDetail());
        Axis2MessageContext axis2smc = (Axis2MessageContext) messageContext;
        org.apache.axis2.context.MessageContext axis2MessageCtx = axis2smc.getAxis2MessageContext();
        axis2MessageCtx.setProperty(Const.STATUS_CODE, Const.HTTP_STATUS_500);
    }

    /**
     * Retrieves connection name from message context if configured as configKey attribute
     * or from the template parameter
     *
     * @param messageContext Message Context from which the parameters should be extracted from
     * @return connection name
     */
    public static String getConnectionName(MessageContext messageContext) throws InvalidConfigurationException {

        String connectionName = (String) messageContext.getProperty(Const.CONNECTION_NAME);
        if (connectionName == null) {
            throw new InvalidConfigurationException("Connection name is not set.");
        }
        return getTenantSpecificConnectionName(connectionName, messageContext);
    }

    /**
     * Create a tenant specific unique key to maintain connections per tenant.
     *
     * @param connectionName connection name as specified as configKey attribute or from the template parameter
     * @param messageContext Message Context from which the tenant.info.domain should be extracted
     * @return
     */
    public static String getTenantSpecificConnectionName(String connectionName, MessageContext messageContext) {
        Object tenantDomain = messageContext.getProperty(Const.TENANT_INFO_DOMAIN);
        if (tenantDomain != null) {
            return String.format("%s@%s", connectionName, tenantDomain);
        }
        return connectionName;
    }

    /**
     * Validate and get the disk share access mask.
     *
     * @param diskShareAccessMask Disk share access mask
     * @return Validated disk share access mask
     */
    public static ArrayList<String> validateAndGetDiskShareAccessMask(String diskShareAccessMask) {
        // Prepare the set of allowed access mask values
        Set<String> allowedValues = new HashSet<String>();
        for (AccessMask mask : AccessMask.values()) {
            allowedValues.add(mask.name());
        }

        ArrayList<String> outDiskShareAccessMasks = new ArrayList<String>();

        // Validate and collect allowed values
        if (diskShareAccessMask != null) {
            String[] masks = diskShareAccessMask.split(",");
            for (String mask : masks) {
                String accessMask = mask.trim().toUpperCase();
                if (allowedValues.contains(accessMask)) {
                    outDiskShareAccessMasks.add(accessMask);
                } else {
                    log.warn("Access mask is not valid and was ignored: " + mask);
                }
            }
        }

        // Fallback to default if nothing is valid or input was null
        if (outDiskShareAccessMasks.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("Set the access mask to default MAXIMUM_ALLOWED since the access mask is not defined or the defined values are not valid.");
            }
            outDiskShareAccessMasks.add(Const.DISK_SHARE_ACCESS_MASK_MAX_ALLOWED);
        }

        return outDiskShareAccessMasks;
    }

    /**
     * Add disk share access mask to file system options.
     *
     * @param fso                FileSystemOptions to add disk share access mask
     * @param diskShareAccessMask Disk share access mask
     */
    public static void addDiskShareAccessMaskToFSO(FileSystemOptions fso, String diskShareAccessMask) {
        if (StringUtils.isEmpty(diskShareAccessMask)) {
            try {
                //set disk share access mask to max allowed to keep default behaviour
                Smb2FileSystemConfigBuilder smb2ConfigBuilder = Smb2FileSystemConfigBuilder.getInstance();
                ArrayList<String> accessMaskList = new ArrayList<>();
                accessMaskList.add(Const.DISK_SHARE_ACCESS_MASK_MAX_ALLOWED);
                smb2ConfigBuilder.setDiskShareAccessMask(fso, accessMaskList);
            } catch (NoClassDefFoundError | NoSuchMethodError e) {
                //ignore since using an older server version
            }
        } else {
            try {
                Smb2FileSystemConfigBuilder smb2ConfigBuilder = Smb2FileSystemConfigBuilder.getInstance();
                smb2ConfigBuilder.setDiskShareAccessMask(fso,
                        Utils.validateAndGetDiskShareAccessMask(diskShareAccessMask));
            } catch (NoClassDefFoundError | NoSuchMethodError e) {
                //ignore since using an older server version
            }
        }
    }

    public static void addMaxAccessMaskToFSO(FileSystemOptions fso) {
        try {
            Smb2FileSystemConfigBuilder smb2ConfigBuilder = Smb2FileSystemConfigBuilder.getInstance();
            ArrayList<String> accessMaskList = new ArrayList<>();
            accessMaskList.add(Const.DISK_SHARE_ACCESS_MASK_MAX_ALLOWED);
            smb2ConfigBuilder.setDiskShareAccessMask(fso, accessMaskList);
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            //ignore since using an older server version
        }
    }

    /**
     * Generates a JSON object representing the operation result
     *
     * @param msgContext The message context
     * @param result     The file operation result
     * @return JsonObject containing the operation results
     */
    public static JsonObject generateOperationResult(MessageContext msgContext, FileOperationResult result) {
        // Create a new JSON payload
        JsonObject resultJson = new JsonObject();

        // Add the basic success information
        resultJson.addProperty("success", result.isSuccessful());

        // Add written bytes information if available
        if (result.getWrittenBytes() != 0) {
            resultJson.addProperty("writtenBytes", result.getWrittenBytes());
        }

        if (result.getResultEle() != null) {
            resultJson.add("result", result.getResultEle());
        }

        // Add error information if present
        if (result.getError() != null) {
            setErrorPropertiesToMessage(msgContext, result.getError());

            JsonObject errorJson = new JsonObject();
            errorJson.addProperty("code", result.getError().getErrorCode());
            errorJson.addProperty("message", result.getError().getErrorDetail());
            // Add error detail if available
            if (StringUtils.isNotEmpty(result.getErrorMessage())) {
                errorJson.addProperty("detail", result.getErrorMessage());
            }
            resultJson.add("error", errorJson);
        }

        return resultJson;
    }

    /**
     * Looks up mandatory parameter. Value should be a String.
     *
     * @param msgCtx    Message context
     * @param paramName Name of the parameter to lookup
     * @return Value of the parameter
     * @throws InvalidConfigurationException In case mandatory parameter is not provided
     */
    public static String lookUpStringParam(MessageContext msgCtx, String paramName)
            throws InvalidConfigurationException {
        String value = (String) ConnectorUtils.lookupTemplateParamater(msgCtx, paramName);
        if (StringUtils.isEmpty(value)) {
            throw new InvalidConfigurationException("Parameter '" + paramName + "' is not provided ");
        } else {
            return value;
        }
    }

    /**
     * Looks up optional parameter. Value should be a String.
     *
     * @param msgCtx     Message Context
     * @param paramName  Name of the parameter to lookup
     * @param defaultVal Default value of the parameter
     * @return Value of the parameter if provided, else default value above
     */
    public static String lookUpStringParam(MessageContext msgCtx, String paramName, String defaultVal) {
        String value;
        try {
            value = (String) ConnectorUtils.lookupTemplateParamater(msgCtx, paramName);
        } catch (ClassCastException e) {
            value = ConnectorUtils.lookupTemplateParamater(msgCtx, paramName).toString();
        }
        if (StringUtils.isEmpty(value)) {
            return defaultVal;
        } else {
            return value;
        }
    }

    public static String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, len);
            }
            byte[] fileInBytes = byteArrayOutputStream.toByteArray();
            return Base64.getEncoder().encodeToString(fileInBytes);
        } finally {
            byteArrayOutputStream.close();
        }
    }

    /**
     * Looks up optional boolean parameter. Value should be a Boolean.
     *
     * @param msgCtx     Message Context
     * @param paramName  Name of the parameter to lookup
     * @param defaultVal Default boolean value
     * @return Value of the parameter if provided, else default value above
     */
    public static boolean lookUpBooleanParam(MessageContext msgCtx, String paramName, boolean defaultVal) {
        String value = (String) ConnectorUtils.lookupTemplateParamater(msgCtx, paramName);
        if (StringUtils.isEmpty(value)) {
            return defaultVal;
        } else {
            return Boolean.parseBoolean(value);
        }
    }

    /**
     * Gets FileSystemHandler associated with connection name.
     *
     * @param connectionName Name of the connection
     * @return FileSystemHandler object
     * @throws ConnectException Issue when retrieving cached FileSystemHandler
     */
    public static FileSystemHandler getFileSystemHandler(String connectionName) throws ConnectException {
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        FileSystemHandler fileSystemHandler = (FileSystemHandler) handler
                .getConnection(Const.CONNECTOR_NAME, connectionName);

        // Initialize VFS wrapper for suspension support if not already done
        if (fileSystemHandler.getVfsWrapper() == null) {
            fileSystemHandler.initializeVFSWrapper(connectionName);
        }

        return fileSystemHandler;
    }

    /**
     * Mask the password of the connection url with ***
     *
     * @param url the actual url
     * @return the masked url
     */
    public static String maskURLPassword(String url) {

        final Matcher urlMatcher = URL_PATTERN.matcher(url);
        String maskUrl;
        if (urlMatcher.find()) {
            final Matcher pwdMatcher = PASSWORD_PATTERN.matcher(url);
            maskUrl = pwdMatcher.replaceFirst(":***@");
            return maskUrl;
        }
        return url;
    }

    public static void closeFileSystem(FileObject fileObject) {
        try {
            //Close the File system if it is not already closed
            if (fileObject != null) {
                if (fileObject.getParent() != null && fileObject.getParent().getFileSystem() != null) {
                    fileObject.getParent().getFileSystem().getFileSystemManager()
                            .closeFileSystem(fileObject.getFileSystem());
                }
                fileObject.close();
            }
        } catch (FileSystemException warn) {
            String message = "Error on closing the file: " + fileObject.getName().getPath();
            log.warn(message, warn);
        }
    }

    /**
     * Set file permissions on the given file object (mainly for local file systems).
     * This is a best-effort implementation as VFS doesn't have universal permission support.
     * 
     * @param fileObject The file object to set permissions on
     * @param permissions Octal permission value (e.g., 0755)
     * @throws FileSystemException if setting permissions fails
     */
    public static void setFilePermissions(FileObject fileObject, int permissions) throws FileSystemException {
        try {
            // This is primarily supported for local file systems
            // For remote file systems like SFTP, permission setting may not be supported
            String scheme = fileObject.getName().getScheme();
            
            if ("file".equals(scheme)) {
                // For local files, try to use Java NIO if available
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(fileObject.getName().getPath());
                    if (java.nio.file.Files.exists(path)) {
                        // Convert octal permission to PosixFilePermissions
                        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = 
                            convertOctalToPermissions(permissions);
                        java.nio.file.Files.setPosixFilePermissions(path, perms);
                        log.debug("Successfully set permissions " + Integer.toOctalString(permissions) + 
                                 " on " + fileObject.getName().getPath());
                    }
                } catch (Exception e) {
                    log.debug("Could not set POSIX permissions, trying alternative method: " + e.getMessage());
                    // Fallback: could implement other permission setting methods here
                }
            } else {
                log.debug("Permission setting not implemented for scheme: " + scheme);
            }
        } catch (Exception e) {
            throw new FileSystemException("Failed to set permissions on " + fileObject.getName().getPath(), e);
        }
    }
    
    /**
     * Convert octal permission value to Java NIO PosixFilePermission set
     * 
     * @param octalPermission Octal permission value (e.g., 0755)
     * @return Set of PosixFilePermission
     */
    private static java.util.Set<java.nio.file.attribute.PosixFilePermission> convertOctalToPermissions(int octalPermission) {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = new java.util.HashSet<>();
        
        // Owner permissions (first digit)
        int owner = (octalPermission >> 6) & 7;
        if ((owner & 4) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
        if ((owner & 2) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        if ((owner & 1) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        
        // Group permissions (second digit)
        int group = (octalPermission >> 3) & 7;
        if ((group & 4) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
        if ((group & 2) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
        if ((group & 1) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
        
        // Others permissions (third digit)
        int others = octalPermission & 7;
        if ((others & 4) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
        if ((others & 2) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
        if ((others & 1) != 0) permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
        
        return permissions;
    }
}
