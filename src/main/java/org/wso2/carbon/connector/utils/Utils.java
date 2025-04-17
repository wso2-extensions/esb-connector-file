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

import com.hierynomus.msdtyp.AccessMask;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axiom.soap.SOAPBody;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.connection.SMBConnectionFactory;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.connection.ConnectionHandler;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.exception.InvalidConfigurationException;
import org.wso2.carbon.connector.pojo.FileOperationResult;
import scala.util.parsing.combinator.testing.Str;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;

import javax.xml.stream.XMLStreamException;

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
            for (int i = 0; i < masks.length; i++) {
                String accessMask = masks[i].trim();
                if (allowedValues.contains(accessMask)) {
                    outDiskShareAccessMasks.add(accessMask);
                }
            }
        }

        // Fallback to default if nothing is valid or input was null
        if (outDiskShareAccessMasks.isEmpty()) {
            outDiskShareAccessMasks.add("MAXIMUM_ALLOWED");
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
                smb2ConfigBuilder.setDiskShareAccessMask(fso, (ArrayList<String>) Collections.singletonList(Const.DISK_SHARE_ACCESS_MASK_MAX_ALLOWED));

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
            smb2ConfigBuilder.setDiskShareAccessMask(fso, (ArrayList<String>) Collections.singletonList(Const.DISK_SHARE_ACCESS_MASK_MAX_ALLOWED));
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            //ignore since using an older server version
        }
    }

    /**
     * Generate OMElement out of result config.
     *
     * @param msgContext MessageContext to set relevant properties
     * @param result     FileOperationResult config
     * @return OMElement generated
     */
    public static OMElement generateOperationResult(MessageContext msgContext, FileOperationResult result) {
        //Create a new payload body and add to context

        String resultElementName = result.getOperation() + "Result";
        OMElement resultElement = createOMElement(resultElementName, null);

        OMElement statusCodeElement = createOMElement("success",
                String.valueOf(result.isSuccessful()));
        resultElement.addChild(statusCodeElement);

        if (result.getWrittenBytes() != 0) {
            OMElement writtenBytesEle = createOMElement("writtenBytes",
                    String.valueOf(result.getWrittenBytes()));
            resultElement.addChild(writtenBytesEle);
        }

        if (result.getError() != null) {
            setErrorPropertiesToMessage(msgContext, result.getError());
            //set error code and detail to the message
            OMElement errorEle = createOMElement("error", result.getError().getErrorCode());
            OMElement errorCodeEle = createOMElement("code", result.getError().getErrorCode());
            OMElement errorMessageEle = createOMElement("message", result.getError().getErrorDetail());
            errorEle.addChild(errorCodeEle);
            errorEle.addChild(errorMessageEle);
            resultElement.addChild(errorCodeEle);
            //set error detail
            if (StringUtils.isNotEmpty(result.getErrorMessage())) {
                OMElement errorDetailEle = createOMElement("detail", result.getErrorMessage());
                resultElement.addChild(errorDetailEle);
            }
        }

        return resultElement;
    }

    /**
     * Create an OMElement.
     *
     * @param elementName Name of the element
     * @param value       Value to be added
     * @return OMElement or null if error
     */
    public static OMElement createOMElement(String elementName, String value) {
        OMElement resultElement = null;
        try {
            if (StringUtils.isNotEmpty(value)) {
                resultElement = AXIOMUtil.
                        stringToOM("<" + elementName + ">" + value
                                + "</" + elementName + ">");
            } else {
                resultElement = AXIOMUtil.
                        stringToOM("<" + elementName
                                + "></" + elementName + ">");
            }
        } catch (XMLStreamException | OMException e) {
            log.error("FileConnector:unzip: Error while generating OMElement from element name" + elementName, e);
        }
        return resultElement;
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
        return (FileSystemHandler) handler
                .getConnection(Const.CONNECTOR_NAME, connectionName);
    }

    /**
     * Sets error to the message context
     *
     * @param operationName Name of connector operation
     * @param msgCtx        Message Context to set info
     * @param e             Exception associated
     * @param error         Error code
     * @param errorDetail   Error detail
     */
    public static void setError(String operationName, MessageContext msgCtx, Exception e,
                                Error error, String errorDetail) {
        FileOperationResult result = new FileOperationResult(operationName, false, error, e.getMessage());
        Utils.setResultAsPayload(msgCtx, result);
    }

    /**
     * Set Payload to message context as specified in provided result.
     *
     * @param msgContext MessageContext to set payload
     * @param result     Operation result
     */
    public static void setResultAsPayload(MessageContext msgContext, FileOperationResult result) {

        OMElement resultElement = generateOperationResult(msgContext, result);
        if (result.getResultEle() != null) {
            resultElement.addChild(result.getResultEle());
        }
        SOAPBody soapBody = msgContext.getEnvelope().getBody();
        //Detaching first element (soapBody.getFirstElement().detach()) will be done by following method anyway.
        JsonUtil.removeJsonPayload(((Axis2MessageContext) msgContext).getAxis2MessageContext());
        ((Axis2MessageContext) msgContext).getAxis2MessageContext().
                removeProperty(PassThroughConstants.NO_ENTITY_BODY);
        soapBody.addChild(resultElement);
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
}
