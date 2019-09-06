/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.connector.util;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axiom.soap.SOAPBody;
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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.transport.passthru.util.BinaryRelayBuilder;
import org.codehaus.jettison.json.JSONException;
import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;
import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("ALL")
public class ResultPayloadCreate {
    private static final Log log = LogFactory.getLog(ResultPayloadCreate.class);
    private static final OMFactory fac = OMAbstractFactory.getOMFactory();

    /**
     * Prepare pay load
     *
     * @param messageContext The message context that is processed by a handler in the handle method
     * @param element        OMElement
     */
    public void preparePayload(MessageContext messageContext, OMElement element) {
        SOAPBody soapBody = messageContext.getEnvelope().getBody();
        for (Iterator itr = soapBody.getChildElements(); itr.hasNext(); ) {
            OMElement child = (OMElement) itr.next();
            child.detach();
        }
        for (Iterator itr = element.getChildElements(); itr.hasNext(); ) {
            OMElement child = (OMElement) itr.next();
            soapBody.addChild(child);
        }
    }

    public OMElement addElement(OMElement omElement, String strValue) {
        OMNamespace omNs = fac.createOMNamespace(FileConstants.FILECON,
                FileConstants.NAMESPACE);
        OMElement subValue = fac.createOMElement(FileConstants.RESULT, omNs);
        subValue.addChild(fac.createOMText(strValue));
        omElement.addChild(subValue);
        return omElement;
    }

    /**
     * Create a OMElement
     *
     * @param output output
     * @return return resultElement
     * @throws XMLStreamException
     * @throws IOException
     * @throws JSONException
     */
    public OMElement performSearchMessages(String output) throws XMLStreamException, IOException,
            JSONException {
        OMElement resultElement;
        if (StringUtils.isNotEmpty(output)) {
            resultElement = AXIOMUtil.stringToOM(output);
        } else {
            resultElement = AXIOMUtil.stringToOM("<result></></result>");
        }
        return resultElement;
    }

    /**
     * @param file        Read file
     * @param msgCtx      Message Context
     * @param contentType content type
     * @param streaming   streaming mode (true/false)
     * @return return the status
     * @throws SynapseException
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static boolean buildFile(FileObject file, MessageContext msgCtx, String contentType, String streaming) {
        ManagedDataSource dataSource = null;
        // set the message payload to the message context
        InputStream in = null;
        try {
            if (StringUtils.isEmpty(contentType) || StringUtils.isEmpty(contentType.trim())) {
                if (file.getName().getExtension().toLowerCase().endsWith("xml")) {
                    contentType = "application/xml";
                } else if (file.getName().getExtension().toLowerCase().endsWith("txt")) {
                    contentType = "text/plain";
                }
            } else {
                // Extract the charset encoding from the configured content type and
                // set the CHARACTER_SET_ENCODING property as e.g. SOAPBuilder relies on this.
                String charSetEnc = null;
                try {
                    charSetEnc = new ContentType(contentType).getParameter("charset");
                } catch (ParseException ex) {
                    log.warn("Invalid encoding type.", ex);
                }
                msgCtx.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEnc);
            }
            if (log.isDebugEnabled()) {
                log.debug("Processed file : " + file + " of Content-type : " + contentType);
            }
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            // Determine the message builder to use
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
            if (builder instanceof DataSourceMessageBuilder && "true".equals(streaming)) {
                in = null;
                dataSource = ManagedDataSourceFactory.create(new FileObjectDataSource(file, contentType));
            } else {
                in = new AutoCloseInputStream(file.getContent().getInputStream());
                dataSource = null;
            }
            // Inject the message to the sequence.
            OMElement documentElement;
            if (in != null) {
                documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            } else {
                documentElement = ((DataSourceMessageBuilder) builder).processDocument(dataSource,
                        contentType, axis2MsgCtx);
            }
            //We need this to build the complete message before closing the stream
            if ("false".equals(streaming) || StringUtils.isEmpty(streaming)) {
                documentElement.toString();
            }
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
        } catch (Exception e) {
            log.error("Error while processing the file/folder", e);
            throw new SynapseException("Error while processing the file/folder", e);
        } finally {
            if (dataSource != null) {
                dataSource.destroy();
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                log.error("Error while closing the input stream", e);
                throw new SynapseException("Error while closing the input stream", e);
            }
        }
        return true;
    }


    /**
     * @param file        File to read
     * @param msgCtx      Message Context
     * @param contentType Content type of the file
     * @param start       Read from this line number
     * @param end         Read up to this line number
     * @return return the status
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static boolean readSpecificLines(FileObject fileObj, MessageContext msgCtx, String contentType, String from, String to) {
        try {
            long startFrom = 0;
            Stream<String> stream = null;
            Builder builder;
            BufferedReader reader;

            if (StringUtils.isEmpty(contentType) || StringUtils.isEmpty(contentType.trim())) {
                if (fileObj.getName().getExtension().toLowerCase().endsWith("csv")) {
                    contentType = "application/csv";
                } else if (fileObj.getName().getExtension().toLowerCase().endsWith("txt")) {
                    contentType = "text/plain";
                }
            } else {
                // Extract the charset encoding from the configured content type and
                // set the CHARACTER_SET_ENCODING property as e.g. SOAPBuilder relies on this.
                String charSetEnc = null;
                try {
                    charSetEnc = new ContentType(contentType).getParameter("charset");
                } catch (ParseException ex) {
                    log.warn("Invalid encoding type.", ex);
                }
                msgCtx.setProperty(Constants.Configuration.CHARACTER_SET_ENCODING, charSetEnc);
            }
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();

            // Determine the message builder to use
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

            //Set startFrom value if start value is given. Otherwise use the default value 1.
            if (StringUtils.isNotEmpty(from)) {
                if (Integer.parseInt(from) < 1) {
                    log.warn("Illegal argument value for \"from\". Start to read from line number 1.");
                } else {
                    startFrom = Long.parseLong(from) - 1;
                }
            }
            reader = new BufferedReader(new InputStreamReader(fileObj.getContent().getInputStream()));
            if (StringUtils.isNotEmpty(to) && Long.parseLong(to) >= startFrom) {
                stream = reader.lines().skip(startFrom).limit(Long.parseLong(to) - startFrom);
            } else {
                stream = reader.lines().skip(startFrom);
            }
            OMElement documentElement = null;
            try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(stream.collect(
                    Collectors.joining(FileConstants.NEW_LINE)).toString().getBytes())) {
                documentElement = builder.processDocument(byteArrayInputStream, contentType, axis2MsgCtx);

            }
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
        } catch (AxisFault e) {
            throw new SynapseException("Error while getting the message builder.", e);
        } catch (FileSystemException e) {
            throw new SynapseException("Error while processing the file/folder", e);
        } catch (IOException e) {
            throw new SynapseException("Error while processing the file/folder", e);
        }
        return true;
    }

    /**
     * @param file        File to read
     * @param msgCtx      Message Context
     * @param contentType Content type of the file
     * @param lineNumber  Line number to read
     * @return return the status
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static boolean readALine(FileObject fileObj, MessageContext msgCtx, String lineNumber) {
        try {
            long startFrom = 0;
            Stream<String> stream = null;
            Builder builder;
            BufferedReader reader;
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.
                    Axis2MessageContext) msgCtx).getAxis2MessageContext();
            builder = BuilderUtil.getBuilderFromSelector("text/plain", axis2MsgCtx);
            if (builder == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No message builder found for type '" + "'. Falling back "
                            + "to" + " RELAY builder.");
                }
                builder = new BinaryRelayBuilder();
            }

            if (StringUtils.isEmpty(lineNumber)) {
                throw new SynapseException("Line number is not provided to read.");
            } else {
                reader = new BufferedReader(new InputStreamReader(fileObj.getContent().getInputStream()));
                String line = reader.lines().skip(Long.parseLong(lineNumber) - 1).findFirst().get();
                OMElement documentElement = null;
                try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(line.getBytes())) {
                    documentElement = builder.processDocument(byteArrayInputStream, "text/plain", axis2MsgCtx);
                }
                msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            }
        } catch (AxisFault e) {
            throw new SynapseException("Error while getting the message builder.", e);
        } catch (FileSystemException e) {
            throw new SynapseException("Error while processing the file", e);
        } catch (IOException e) {
            throw new SynapseException("Error while processing the file", e);
        }
        return true;
    }
}
