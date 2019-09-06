/*
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.connector;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPHTTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.synapse.MessageContext;
import org.codehaus.jettison.json.JSONException;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.Connector;
import org.wso2.carbon.connector.core.util.ConnectorUtils;
import org.wso2.carbon.connector.util.FileConstants;
import org.wso2.carbon.connector.util.ResultPayloadCreate;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * FTP Over Proxy
 */
public class FileFtpOverProxy extends AbstractConnector implements Connector {
    private static final Log log = LogFactory.getLog(FileFtpOverProxy.class);

    public void connect(MessageContext messageContext) {
        String proxyHost = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.PROXY_HOST);
        String proxyPort = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.PROXY_PORT);
        String proxyUsername = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.PROXY_USERNAME);
        String proxyPassword = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.PROXY_PASSWORD);
        String ftpServer = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.FTP_SERVER);
        String ftpPort = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.FTP_PORT);
        String ftpUsername = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.FTP_USERNAME);
        String ftpPassword = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.FTP_PASSWORD);

        boolean resultStatus =
                ftpOverHttp(proxyHost, proxyPort, proxyUsername, proxyPassword, ftpServer, ftpPort,
                        ftpUsername, ftpPassword, messageContext);
        generateResult(messageContext, resultStatus);
    }

    /**
     * Generate the result.
     *
     * @param messageContext The message context that is generated for processing the file
     * @param resultStatus   true/false
     */
    private void generateResult(MessageContext messageContext, boolean resultStatus) {
        ResultPayloadCreate resultPayload = new ResultPayloadCreate();
        String response = FileConstants.START_TAG + resultStatus + FileConstants.END_TAG;
        OMElement element;
        try {
            element = resultPayload.performSearchMessages(response);
            resultPayload.preparePayload(messageContext, element);
        } catch (XMLStreamException e) {
            handleException(e.getMessage(), e, messageContext);
        } catch (IOException e) {
            handleException(e.getMessage(), e, messageContext);
        } catch (JSONException e) {
            handleException(e.getMessage(), e, messageContext);
        }
    }

    /**
     * Send FTP over Proxy.
     *
     * @param proxyHost      Name of the proxy host
     * @param proxyPort      Proxy port number
     * @param proxyUsername  User name of the proxy
     * @param proxyPassword  Password of the proxy
     * @param ftpServer      FTP server
     * @param ftpPort        Port number of FTP
     * @param ftpUsername    User name of the FTP
     * @param ftpPassword    Password of the FTP
     * @param messageContext he message context that is generated for processing the ftpOverHttp method
     * @return true/false
     */
    public boolean ftpOverHttp(String proxyHost, String proxyPort, String proxyUsername,
                               String proxyPassword, String ftpServer, String ftpPort,
                               String ftpUsername, String ftpPassword,
                               MessageContext messageContext) {
        String keepAliveTimeout = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.KEEP_ALIVE_TIMEOUT);
        String controlKeepAliveReplyTimeout = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext,
                        FileConstants.CONTROL_KEEP_ALIVE_REPLY_TIMEOUT);
        String targetPath = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.TARGET_PATH);
        String targetFile = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.TARGET_FILE);
        String binaryTransfer = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.BINARY_TRANSFER);
        String localActive = (String) ConnectorUtils
                .lookupTemplateParamater(messageContext, FileConstants.LOCAL_ACTIVE);
        boolean resultStatus = false;
        InputStream inputStream = null;

        final FTPClient ftp;
        if (StringUtils.isNotEmpty(proxyHost) && StringUtils.isNotEmpty(proxyPort) &&
                StringUtils.isNotEmpty(proxyUsername) && StringUtils.isNotEmpty(proxyPassword)) {
            proxyHost = proxyHost.trim();
            proxyPort = proxyPort.trim();
            proxyUsername = proxyUsername.trim();
            proxyPassword = proxyPassword.trim();
            ftp = new FTPHTTPClient(proxyHost, Integer.parseInt(proxyPort), proxyUsername,
                    proxyPassword);
        } else {
            ftp = new FTPClient();
        }
        //Set the time to wait between sending control connection keep alive messages when
        // processing file upload or download (Zero (or less) disables).
        keepAliveTimeout = keepAliveTimeout.trim();
        if (StringUtils.isNotEmpty(keepAliveTimeout)) {
            ftp.setControlKeepAliveTimeout(Long.parseLong(keepAliveTimeout.trim()));
        }
        //Set how long to wait for control keep-alive message replies.(defaults to 1000 milliseconds.)
        if (StringUtils.isNotEmpty(controlKeepAliveReplyTimeout)) {
            ftp.setControlKeepAliveReplyTimeout(Integer.parseInt(controlKeepAliveReplyTimeout.trim()));
        }
        try {
            int reply;
            ftpPort = ftpPort.trim();
            int IntFtpPort = Integer.parseInt(ftpPort);
            if (IntFtpPort > 0) {
                ftp.connect(ftpServer, IntFtpPort);
            } else {
                ftpServer = ftpServer.trim();
                ftp.connect(ftpServer);
            }
            if (log.isDebugEnabled()) {
                log.debug(" Connected to " + ftpServer + " on " +
                        (IntFtpPort > 0 ? ftpPort : ftp.getDefaultPort()));
            }
            // After connection attempt, should check the reply code to verify success.
            reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                log.error("FTP ftpServer refused connection.");
            }
            if (ftp.login(ftpUsername, ftpPassword)) {
                if (StringUtils.isNotEmpty(binaryTransfer)) {
                    if (Boolean.valueOf(binaryTransfer.trim())) {
                        ftp.setFileType(FTP.BINARY_FILE_TYPE);
                    } else {
                        // in theory this should not be necessary as servers should default to ASCII
                        // but they don't all do so - see NET-500
                        ftp.setFileType(FTP.ASCII_FILE_TYPE);
                    }
                } else {
                    ftp.setFileType(FTP.ASCII_FILE_TYPE);
                }
                // Use passive mode as default because most of us are behind firewalls these days.
                if (StringUtils.isNotEmpty(localActive)) {
                    if (Boolean.valueOf(localActive.trim())) {
                        ftp.enterLocalActiveMode();
                    } else {
                        ftp.enterLocalPassiveMode();
                    }
                } else {
                    ftp.enterLocalPassiveMode();
                }
                inputStream = new ByteArrayInputStream(messageContext.getEnvelope().getBody().getFirstElement().
                        toString().getBytes());
                if (StringUtils.isNotEmpty(targetPath)) {
                    ftp.changeWorkingDirectory(targetPath);
                    ftp.storeFile(targetFile, inputStream);
                    if (log.isDebugEnabled()) {
                        log.debug("Successfully FTP transfered the File");
                    }
                }
                // check that control connection is working
                if (log.isDebugEnabled()) {
                    log.debug("The code received from the server." + ftp.noop());
                }
                resultStatus = true;
            } else {
                ftp.logout();
                handleException("Error while login ftp server.", messageContext);
            }
        } catch (FTPConnectionClosedException e) {
            // log.error("Server closed connection " + e.getMessage(), e);
            handleException("Server closed connection: " + e.getMessage(), e, messageContext);
        } catch (IOException e) {
            //log.error("Error occurred while uploading:" + e.getMessage(), e);
            handleException("Could not connect to FTP ftpServer: " + e.getMessage(), e, messageContext);
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                    ftp.logout();
                } catch (IOException f) {
                    // do nothing
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException f) {
                    // do nothing
                }
            }
        }
        return resultStatus;
    }
}
