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

import org.apache.commons.logging.Log;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileType;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;

import java.time.Duration;

public class ConnectionParametersTest {
    @Test
    public void optionWarningsKeepTheirConnectionAndMaskTheValue() {
        List<String> warnings = new ArrayList<>();
        Log sink = (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] { Log.class },
                (proxy, method, arguments) -> {
                    if ("warn".equals(method.getName())) {
                        warnings.add((String) arguments[0]);
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
        NamedLog first = NamedLog.connection(sink, "first", new RepeatLimiter());
        NamedLog peer = NamedLog.connection(sink, "peer", new RepeatLimiter());
        String bad = "sftp://user:sample@host/path";
        String safe = "sftp://user:***@host/path";
        new FileSorter(bad, "Ascending", first);
        Assert.assertEquals(Utils.validateArchiveEncoding(bad, peer::warn), "UTF-8");
        Assert.assertEquals(warnings, Arrays.asList(
                "[connection:first] Unknown sort attribute " + safe + ". Sorting by Name",
                "[connection:peer] Invalid encoding '" + safe + "', falling back to default: UTF-8"));
    }

    private FileSystemOptions options(FilePoolFixture files) throws Exception {
        FileSystemHandler handler = files.factory.makeObject();
        try {
            return handler.getFsOptions();
        } finally {
            files.factory.destroyObject(handler);
        }
    }

    @DataProvider(name = "ftpProtocols")
    public Object[][] ftpProtocols() {
        return new Object[][]{{"FTP", FtpFileSystemConfigBuilder.getInstance()},
                {"FTPS", FtpsFileSystemConfigBuilder.getInstance()}};
    }

    @Test(dataProvider = "ftpProtocols")
    public void ftpOptionsReachTheProvider(String protocol, FtpFileSystemConfigBuilder builder) throws Exception {
        try (FilePoolFixture files = new FilePoolFixture(protocol)) {
            FileSystemOptions options = options(files);
            Assert.assertEquals(builder.getFileType(options), FtpFileType.BINARY);
            Assert.assertNull(builder.getDataTimeoutDuration(options));
            Assert.assertNull(builder.getConnectTimeoutDuration(options));
            Assert.assertNull(builder.getSoTimeoutDuration(options));
            Assert.assertNull(builder.getWriteStallTimeout(options));
            Assert.assertFalse(builder.getUserDirIsRoot(options));
        }
        try (FilePoolFixture files = new FilePoolFixture(protocol, "ftpTransferType", "aScIi",
                "ftpDataTimeout", "110", "ftpConnectionTimeout", "220", "ftpSocketTimeout", "330",
                "writeStallTimeout", "0")) {
            FileSystemOptions options = options(files);
            Assert.assertEquals(builder.getFileType(options), FtpFileType.ASCII);
            Assert.assertEquals(builder.getDataTimeoutDuration(options), Duration.ofMillis(110));
            Assert.assertEquals(builder.getConnectTimeoutDuration(options), Duration.ofMillis(220));
            Assert.assertEquals(builder.getSoTimeoutDuration(options), Duration.ofMillis(330));
            Assert.assertEquals(builder.getWriteStallTimeout(options), Duration.ZERO);
        }
    }

    @Test
    public void sshAlgorithmsAndTimeoutsAreOptional() throws Exception {
        SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        try (FilePoolFixture files = new FilePoolFixture("SFTP")) {
            FileSystemOptions input = options(files);
            Assert.assertNull(builder.getKeyExchangeAlgorithm(input));
            Assert.assertNull(builder.getHostKeyAlgorithms(input));
            Assert.assertNull(builder.getCiphers(input));
            Assert.assertNull(builder.getMacs(input));
            Assert.assertNull(builder.getPubkeyAcceptedAlgorithms(input));
            Assert.assertFalse(builder.isSessionTimeoutSet(input));
            Assert.assertFalse(builder.isConnectTimeoutSet(input));
            Assert.assertNull(builder.getKeepAliveCountMax(input));
            Assert.assertNull(builder.getWriteStallTimeout(input));
            Assert.assertEquals(builder.getAvoidPermissionCheck(input), "true");
        }
        try (FilePoolFixture files = new FilePoolFixture("SFTP", "sftpKeyExchangeAlgorithms", "curve25519-sha256",
                "sftpHostKeyAlgorithms", "ssh-ed25519", "sftpCiphers", "aes128-ctr",
                "sftpMacs", "hmac-sha2-256", "sftpPubkeyAcceptedAlgorithms", "rsa-sha2-512",
                "sftpSessionTimeout", "800", "sftpConnectionTimeout", "500", "sftpKeepAliveCount", "2",
                "writeStallTimeout", "400", "setAvoidPermission", "false")) {
            FileSystemOptions input = options(files);
            Assert.assertEquals(builder.getKeyExchangeAlgorithm(input), "curve25519-sha256");
            Assert.assertEquals(builder.getHostKeyAlgorithms(input), "ssh-ed25519");
            Assert.assertEquals(builder.getCiphers(input), "aes128-ctr");
            Assert.assertEquals(builder.getMacs(input), "hmac-sha2-256");
            Assert.assertEquals(builder.getPubkeyAcceptedAlgorithms(input), "rsa-sha2-512");
            Assert.assertEquals(builder.getSessionTimeout(input), Duration.ofMillis(800));
            Assert.assertEquals(builder.getConnectTimeout(input), Duration.ofMillis(500));
            Assert.assertEquals(builder.getKeepAliveCountMax(input), Integer.valueOf(2));
            Assert.assertEquals(builder.getWriteStallTimeout(input), Duration.ofMillis(400));
            Assert.assertEquals(builder.getAvoidPermissionCheck(input), "false");
        }
    }

    @Test
    public void smbTimeoutsReachOnlyTheConfiguredConnection() throws Exception {
        Smb2FileSystemConfigBuilder builder = Smb2FileSystemConfigBuilder.getInstance();
        try (FilePoolFixture configured = new FilePoolFixture("SMB2", "smb2ReadTimeout", "123",
                "smb2WriteTimeout", "234", "smb2TransactTimeout", "345", "writeStallTimeout", "456");
             FilePoolFixture defaults = new FilePoolFixture("SMB2")) {
            FileSystemOptions input = options(configured);
            Assert.assertEquals(builder.getReadTimeout(input), Duration.ofMillis(123));
            Assert.assertEquals(builder.getWriteTimeout(input), Duration.ofMillis(234));
            Assert.assertEquals(builder.getTransactTimeout(input), Duration.ofMillis(345));
            Assert.assertEquals(builder.getWriteStallTimeout(input), Duration.ofMillis(456));
            FileSystemOptions unset = options(defaults);
            Assert.assertNull(builder.configuredReadTimeout(unset));
            Assert.assertEquals(builder.getReadTimeout(unset), Duration.ofSeconds(60));
            Assert.assertNull(builder.configuredWriteTimeout(unset));
            Assert.assertEquals(builder.getWriteTimeout(unset), Duration.ofSeconds(60));
            Assert.assertNull(builder.configuredTransactTimeout(unset));
            Assert.assertEquals(builder.getTransactTimeout(unset), Duration.ofSeconds(60));
            Assert.assertNull(builder.configuredWriteStallTimeout(unset));
            Assert.assertEquals(builder.getWriteStallTimeout(unset), Duration.ofSeconds(60));
        }
    }

    @Test
    public void smbConnectionStartsThoughNoBoundIsKnown() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("SMB")) {
            Assert.assertFalse(files.factory.getContext().isClosed());
        }
    }

    @Test
    public void poolAgeUsesSecondsAndStreamIdleUsesMilliseconds() throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "sftpPoolConnectionAgedTimeout", "3600",
                "streamIdleTimeout", "17", "lockReleaseSameNode", "false")) {
            ConnectionConfiguration config = files.factory.getContext().getConfiguration();
            Assert.assertEquals(config.getConfiguration().getPoolConnectionAgedTimeout(), 3600000L);
            Assert.assertEquals(files.factory.getContext().idleMs(), 17L);
            Assert.assertFalse(config.isLockReleaseSameNode());
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionConfiguration config = files.factory.getContext().getConfiguration();
            Assert.assertEquals(config.getConfiguration().getPoolConnectionAgedTimeout(), 0L);
            Assert.assertEquals(files.factory.getContext().idleMs(), 60000L);
            Assert.assertTrue(config.isLockReleaseSameNode());
            Assert.assertTrue(config.isAutoLockRelease());
            Assert.assertEquals(files.factory.getContext().getShareLock().staleAgeMillis(), 900000L);
            Assert.assertTrue(config.isUseTempFolder());
            Assert.assertEquals(config.getTempFolderName(), ".part");
            Assert.assertEquals(config.getTempFileSuffix(), ".part");
            Assert.assertFalse(config.isFileCacheEnabled());
            Assert.assertTrue(config.isSuspendOnConnectionFailure());
            Assert.assertEquals(config.getRetriesBeforeSuspension(), 0);
            Assert.assertEquals(config.getSuspendInitialDuration(), 1000L);
            Assert.assertEquals(config.getSuspendProgressionFactor(), 1.0);
            Assert.assertEquals(config.getSuspendMaximumDuration(), 300000L);
        }
    }
}
