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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.UriParser;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsDataChannelProtectionLevel;
import org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsMode;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.apache.commons.vfs2.util.DelegatingFileSystemOptionsBuilder;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.vfs.VFSConstants;
import org.apache.synapse.task.SynapseTaskException;
import org.wso2.carbon.connector.core.util.ConnectorUtils;

import java.util.HashMap;
import java.util.Map;

public class FileConnectorUtils {

    /**
     * SSL Keystore.
     */
    private static final String KEY_STORE = "vfs.ssl.keystore";

    /**
     * SSL Truststore.
     */
    private static final String TRUST_STORE = "vfs.ssl.truststore";

    /**
     * SSL Keystore password.
     */
    private static final String KS_PASSWD = "vfs.ssl.kspassword";

    /**
     * SSL Truststore password.
     */
    private static final String TS_PASSWD = "vfs.ssl.tspassword";

    /**
     * SSL Key password.
     */
    private static final String KEY_PASSWD = "vfs.ssl.keypassword";

    /**
     * Passive mode
     */
    public static final String PASSIVE_MODE = "vfs.passive";

    /**
     * FTPS implicit mode
     */
    public static final String IMPLICIT_MODE = "vfs.implicit";

    /**
     * FTPS protection mode
     */
    public static final String PROTECTION_MODE = "vfs.protection";

    private static final Log log = LogFactory.getLog(FileUnzipUtil.class);

    /**
     * @param remoteFile Location of the remote file
     * @return true/false
     */
    public static boolean isFolder(FileObject remoteFile) {
        boolean isFolder = false;
        if (StringUtils.isEmpty(remoteFile.getName().getExtension())) {
            isFolder = true;
        }
        return isFolder;
    }

    public static StandardFileSystemManager getManager() {
        StandardFileSystemManager fsm = null;
        try {
            fsm = new StandardFileSystemManager();
            fsm.init();
        } catch (FileSystemException e) {
            log.error("Unable to get FileSystemManager: " + e.getMessage(), e);
        }
        return fsm;
    }

    public static FileSystemOptions getFso(MessageContext messageContext, String fileUrl, FileSystemManager fsManager) {

        String setTimeout = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.SET_TIME_OUT);
        String setPassiveMode = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.SET_PASSIVE_MODE);
        String setSoTimeout = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.SET_SO_TIMEOUT);
        String setStrictHostKeyChecking = (String) ConnectorUtils.lookupTemplateParamater
                (messageContext, FileConstants.SET_STRICT_HOST_KEY_CHECKING);
        String setUserDirIsRoot = (String) ConnectorUtils.lookupTemplateParamater(messageContext,
                FileConstants.SET_USER_DIRISROOT);

        if (log.isDebugEnabled()) {
            log.debug("File init starts with " + setTimeout + "," + setPassiveMode + "," +
                    "" + setSoTimeout + "," + setStrictHostKeyChecking + "," + setUserDirIsRoot);
        }

        FileSystemOptions opts;
        if (StringUtils.isEmpty(fileUrl)) {
           opts = new FileSystemOptions();
        } else {
            try {
                opts = generateFileSystemOptions(fileUrl, fsManager);
            } catch (FileSystemException e) {
                log.error("Unable to set options for processed file location ", e);
                opts = new FileSystemOptions();
            }
        }

        // SSH Key checking
        try {
            if (StringUtils.isEmpty(setStrictHostKeyChecking)) {
                SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(opts, "no");
            } else {
                setStrictHostKeyChecking = setStrictHostKeyChecking.trim();
                SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(opts,
                        setStrictHostKeyChecking);
            }
        } catch (FileSystemException e) {
            throw new SynapseTaskException("Error while configuring a " +
                    "setStrictHostKeyChecking", e);
        }
        // Root directory set to user home
        if (StringUtils.isEmpty(setUserDirIsRoot)) {
            SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(opts, false);
        } else {
            setUserDirIsRoot = setUserDirIsRoot.trim();
            try {
                SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(opts, Boolean.valueOf
                        (setUserDirIsRoot));
            } catch (Exception e) {
                throw new SynapseTaskException("Error while configuring a " +
                        "setUserDirIsRoot", e);
            }
        }
        // Timeout is count by Milliseconds
        if (StringUtils.isEmpty(setTimeout)) {
            SftpFileSystemConfigBuilder.getInstance().setTimeout(opts, FileConstants.TIME_OUT);
        } else {
            setTimeout = setTimeout.trim();
            try {
                SftpFileSystemConfigBuilder.getInstance().setTimeout(opts,
                        Integer.parseInt(setTimeout));
            } catch (NumberFormatException e) {
                throw new SynapseTaskException("Error while configuring a " +
                        "setTimeout", e);
            }
        }
        if (StringUtils.isEmpty(setPassiveMode)) {
            FtpFileSystemConfigBuilder.getInstance().setPassiveMode(opts, true);
            FtpsFileSystemConfigBuilder.getInstance().setPassiveMode(opts, true);
        } else {
            setPassiveMode = setPassiveMode.trim();
            try {
                FtpFileSystemConfigBuilder.getInstance().setPassiveMode(opts,
                        Boolean.valueOf(setPassiveMode));
                FtpsFileSystemConfigBuilder.getInstance().setPassiveMode(opts,
                        Boolean.valueOf(setPassiveMode));
            } catch (Exception e) {
                throw new SynapseTaskException("Error while configuring a " +
                        "setPassiveMode", e);
            }
        }
        if (StringUtils.isEmpty(setSoTimeout)) {
            FtpFileSystemConfigBuilder.getInstance().setSoTimeout(opts, FileConstants.TIME_OUT);
        } else {
            setSoTimeout = setSoTimeout.trim();
            try {
                FtpFileSystemConfigBuilder.getInstance().setSoTimeout(opts,
                        Integer.parseInt(setSoTimeout));
            } catch (NumberFormatException e) {
                throw new SynapseTaskException("Error while configuring a " +
                        "setSoTimeout", e);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("FileConnector configuration is completed.");
        }
        return opts;
    }

    public static FileSystemOptions generateFileSystemOptions(String fileUrl, FileSystemManager fsManager)
            throws FileSystemException {

        Map<String, String> options = FileConnectorUtils.extractQueryParams(fileUrl);

        FileSystemOptions fso = new FileSystemOptions();
        DelegatingFileSystemOptionsBuilder delegate = new DelegatingFileSystemOptionsBuilder(fsManager);

        FtpsFileSystemConfigBuilder configBuilder = FtpsFileSystemConfigBuilder.getInstance();

        // ftp and ftps configs
        String passiveMode = options.get(PASSIVE_MODE);
        if (passiveMode != null) {
            configBuilder.setPassiveMode(fso, Boolean.parseBoolean(passiveMode));
        }

        // ftps configs
        String implicitMode = options.get(IMPLICIT_MODE);
        if (implicitMode != null) {
            if (Boolean.parseBoolean(implicitMode)) {
                configBuilder.setFtpsMode(fso, FtpsMode.IMPLICIT);
            } else {
                configBuilder.setFtpsMode(fso, FtpsMode.EXPLICIT);
            }
        }
        String protectionMode = options.get(PROTECTION_MODE);
        if ("P".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(fso, FtpsDataChannelProtectionLevel.P);
        } else if ("C".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(fso, FtpsDataChannelProtectionLevel.C);
        } else if ("S".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(fso, FtpsDataChannelProtectionLevel.S);
        } else if ("E".equalsIgnoreCase(protectionMode)) {
            configBuilder.setDataChannelProtectionLevel(fso, FtpsDataChannelProtectionLevel.E);
        }
        String keyStore = options.get(KEY_STORE);
        if (keyStore != null) {
            configBuilder.setKeyStore(fso, keyStore);
        }
        String trustStore = options.get(TRUST_STORE);
        if (trustStore != null) {
            configBuilder.setTrustStore(fso, trustStore);
        }
        String keyStorePassword = options.get(KS_PASSWD);
        if (keyStorePassword != null) {
            configBuilder.setKeyStorePW(fso, keyStorePassword);
        }
        String trustStorePassword = options.get(TS_PASSWD);
        if (trustStorePassword != null) {
            configBuilder.setTrustStorePW(fso, trustStorePassword);
        }
        String keyPassword = options.get(KEY_PASSWD);
        if (keyPassword != null) {
            configBuilder.setKeyPW(fso, keyPassword);
        }

        if (options.get(VFSConstants.FILE_TYPE) != null) {
            delegate.setConfigString(fso, options.get(VFSConstants.SCHEME), VFSConstants.FILE_TYPE,
                                     options.get(VFSConstants.FILE_TYPE));
        }

        return fso;
    }

    /**
     * Extract the query String from the URI.
     *
     * @param uri String containing the URI.
     * @return The query string, if any. null otherwise.
     */
    public static Map<String, String> extractQueryParams(final String uri) throws FileSystemException {
        Map<String, String> sQueryParams = new HashMap<String, String>();
        if (uri != null) {
            String[] urlParts = uri.split("\\?");
            if (urlParts.length > 1) {
                String query = urlParts[1];
                query = UriParser.decode(query);
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) {
                        sQueryParams.put(pair[0], pair[1]);
                    }
                }
            }
        }
        return sQueryParams;
    }
}
