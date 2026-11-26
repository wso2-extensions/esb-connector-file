/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.connector.operations;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMSourcedElement;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.format.PlainTextBuilder;
import org.apache.commons.logging.Log;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.Constants;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.wso2.carbon.connector.pojo.FileSorter;
import org.wso2.carbon.connector.utils.Utils;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.carbon.connector.pojo.ConnectionConfiguration;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileType;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileProvider;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

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
            Assert.assertNull(builder.getFileNameEncoding(input));
            Assert.assertFalse(builder.isSessionTimeoutSet(input));
            Assert.assertFalse(builder.isConnectTimeoutSet(input));
            Assert.assertNull(builder.getKeepAliveCountMax(input));
            Assert.assertNull(builder.getWriteStallTimeout(input));
            Assert.assertEquals(builder.getAvoidPermissionCheck(input), "true");
        }
        try (FilePoolFixture files = new FilePoolFixture("SFTP", "sftpKeyExchangeAlgorithms", "curve25519-sha256",
                "sftpHostKeyAlgorithms", "ssh-ed25519", "sftpCiphers", "aes128-ctr",
                "sftpMacs", "hmac-sha2-256", "sftpPubkeyAcceptedAlgorithms", "rsa-sha2-512",
                "sftpFileNameEncoding", "ISO-8859-1",
                "sftpSessionTimeout", "800", "sftpConnectionTimeout", "500", "sftpKeepAliveCount", "2",
                "writeStallTimeout", "400", "setAvoidPermission", "false")) {
            FileSystemOptions input = options(files);
            Assert.assertEquals(builder.getKeyExchangeAlgorithm(input), "curve25519-sha256");
            Assert.assertEquals(builder.getHostKeyAlgorithms(input), "ssh-ed25519");
            Assert.assertEquals(builder.getCiphers(input), "aes128-ctr");
            Assert.assertEquals(builder.getMacs(input), "hmac-sha2-256");
            Assert.assertEquals(builder.getPubkeyAcceptedAlgorithms(input), "rsa-sha2-512");
            Assert.assertEquals(builder.getFileNameEncoding(input), "ISO-8859-1");
            Assert.assertEquals(builder.getSessionTimeout(input), Duration.ofMillis(800));
            Assert.assertEquals(builder.getConnectTimeout(input), Duration.ofMillis(500));
            Assert.assertEquals(builder.getKeepAliveCountMax(input), Integer.valueOf(2));
            Assert.assertEquals(builder.getWriteStallTimeout(input), Duration.ofMillis(400));
            Assert.assertEquals(builder.getAvoidPermissionCheck(input), "false");
        }
    }

    @DataProvider(name = "wrongCharsets")
    public Object[][] wrongCharsets() {
        return new Object[][]{{"x-no-such-charset"}, {"not a charset"}};
    }

    @Test(dataProvider = "wrongCharsets")
    public void wrongFileNameEncodingWarnsAndKeepsTheDefault(String charset) throws Exception {
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        AppenderSkeleton appender = new AppenderSkeleton() {
            protected void append(LoggingEvent event) {
                String line = event.getRenderedMessage();
                if (line.contains("sftpFileNameEncoding")) {
                    warnings.add(line);
                }
            }

            public void close() { }

            public boolean requiresLayout() { return false; }
        };
        Logger.getRootLogger().addAppender(appender);
        try (FilePoolFixture files = new FilePoolFixture("SFTP", "sftpFileNameEncoding", charset)) {
            Assert.assertNull(SftpFileSystemConfigBuilder.getInstance().getFileNameEncoding(options(files)));
            Assert.assertEquals(warnings.size(), 1, warnings.toString());
            Assert.assertTrue(warnings.get(0).contains("'" + charset + "'. Using UTF-8."), warnings.get(0));
        } finally {
            Logger.getRootLogger().removeAppender(appender);
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
    public void smbDfsReachesOnlyTheConfiguredConnectionAndIsOnByDefault() throws Exception {
        Smb2FileSystemConfigBuilder builder = Smb2FileSystemConfigBuilder.getInstance();
        try (FilePoolFixture off = new FilePoolFixture("SMB2", "smb2DfsEnabled", "FALSE");
             FilePoolFixture on = new FilePoolFixture("SMB2", "smb2DfsEnabled", "true");
             FilePoolFixture defaults = new FilePoolFixture("SMB2");
             FilePoolFixture empty = new FilePoolFixture("SMB2", "smb2DfsEnabled", "")) {
            Assert.assertFalse(builder.getDfsEnabled(options(off)));
            Assert.assertTrue(builder.getDfsEnabled(options(on)));
            Assert.assertTrue(builder.getDfsEnabled(options(defaults)));
            Assert.assertTrue(builder.getDfsEnabled(options(empty)));
        }
    }

    @Test
    public void smbDfsRefusesAValueThatIsNotTrueOrFalse() throws Exception {
        String refused = refusal("connectionType", "SMB2", "smb2DfsEnabled", "yes");
        Assert.assertNotNull(refused, "smb2DfsEnabled=yes was accepted");
        Assert.assertTrue(refused.contains("'smb2DfsEnabled'"), refused);
    }

    private static String refusal(String... settings) throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            Map<String, String> values = new HashMap<>();
            values.put("connectionType", "LOCAL");
            values.put("name", files.name);
            values.put("fileLockScheme", "Local");
            for (int i = 0; i < settings.length; i += 2) {
                values.put(settings[i], settings[i + 1]);
            }
            TemplateContext template = new TemplateContext("file.init", Collections.emptyList());
            template.setMappedValues(values);
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            Axis2MessageContext message = WriteFileTest.message(files);
            message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            try {
                new FileConfig().connect(message);
                return null;
            } catch (SynapseException refused) {
                return refused.getCause().getMessage();
            }
        }
    }

    @DataProvider(name = "wrongValues")
    public Object[][] wrongValues() {
        return new Object[][]{{"tempFileSuffix", "part"}, {"tempFileSuffix", "."}, {"tempFileSuffix", ".a/b"},
                {"tempFolderName", "a/b"}, {"tempFolderName", "a\\b"}, {"tempFolderName", ".."},
                {"suspendInitialDuration", "-1"}, {"suspendProgressionFactor", "0"},
                {"suspendProgressionFactor", "0.5"}, {"suspendProgressionFactor", "NaN"},
                {"suspendMaximumDuration", "999"}, {"streamIdleTimeout", "0"}};
    }

    @Test(dataProvider = "wrongValues")
    public void wrongValueIsRefusedWithItsName(String parameter, String value) throws Exception {
        String refused = refusal(parameter, value);
        Assert.assertNotNull(refused, parameter + "=" + value + " was accepted");
        Assert.assertTrue(refused.contains("'" + parameter + "'"), refused);
    }

    @Test
    public void rightValuesAreAccepted() throws Exception {
        Assert.assertNull(refusal("tempFileSuffix", ".tmp", "tempFolderName", "work", "suspendInitialDuration", "0",
                "suspendProgressionFactor", "1", "suspendMaximumDuration", "1000", "streamIdleTimeout", "1"));
        Assert.assertNull(refusal("suspendOnConnectionFailure", "false", "suspendProgressionFactor", "0"));
    }

    @Test
    public void initWarningsComeOncePerConnection() throws Exception {
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        AppenderSkeleton appender = new AppenderSkeleton() {
            protected void append(LoggingEvent event) {
                String line = event.getRenderedMessage();
                if (line.contains("maxFailureRetryCount") || line.contains("maxWaitTime")) {
                    warnings.add(line);
                }
            }

            public void close() { }

            public boolean requiresLayout() { return false; }
        };
        Logger.getRootLogger().addAppender(appender);
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "maxFailureRetryCount", "3", "maxWaitTime", "0")) {
            for (int message = 0; message < 3; message++) {
                new FileConfig().connect(files.message);
            }
            Assert.assertEquals(warnings.size(), 2, warnings.toString());
        } finally {
            Logger.getRootLogger().removeAppender(appender);
        }
    }

    @Test
    public void encodedPasswordDecodesToItselfAndMayBeEmpty() throws Exception {
        Assert.assertNull(FileConfig.encodePassword(null));
        Assert.assertEquals(FileConfig.encodePassword(""), "");
        String password = "a b+c#d/e@f&g%h:i";
        Assert.assertEquals(UriParser.decode(FileConfig.encodePassword(password)), password);
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
                "streamIdleTimeout", "17")) {
            ConnectionConfiguration config = files.factory.getContext().getConfiguration();
            Assert.assertEquals(config.getConfiguration().getPoolConnectionAgedTimeout(), 3600000L);
            Assert.assertEquals(files.factory.getContext().idleMs(), 17L);
        }
        try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
            ConnectionConfiguration config = files.factory.getContext().getConfiguration();
            Assert.assertEquals(config.getConfiguration().getPoolConnectionAgedTimeout(), 0L);
            Assert.assertEquals(files.factory.getContext().idleMs(), 60000L);
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

    // The passwords that break the server address when they stand in it as they are: a raw slash, a raw question mark
    // and a percent sign that does not start an escape of two hex digits.
    private static final String[] BREAKING = {"2024/Summer", "22?pa55", "pa%ss", "pa%", "a%2g"};
    // Passwords that work in the address as they are today and must go on working with encodePassword off.
    private static final String[] WORKING = {"plain", "pa+ss", "pa ss", "a#b", "pa%40ss"};
    private static final String[] PROTOCOLS = {"SFTP", "FTP", "FTPS", "SMB2"};

    private static String connection(String protocol, String password, String encode, StringBuilder lines)
            throws Exception {
        List<String> settings = new ArrayList<>(Arrays.asList("connectionType", protocol, "host", "host.example",
                "port", "2222", "username", "someone7", "workingDir", protocol.equals("SMB2") ? "share/in" : "in"));
        if (password != null) {
            settings.addAll(Arrays.asList("password", password));
        }
        if (encode != null) {
            settings.addAll(Arrays.asList("encodePassword", encode));
        }
        AppenderSkeleton appender = new AppenderSkeleton() {
            protected void append(LoggingEvent event) {
                lines.append(event.getRenderedMessage()).append('\n');
                if (event.getThrowableInformation() != null) {
                    lines.append(event.getThrowableInformation().getThrowable()).append('\n');
                }
            }

            public void close() { }

            public boolean requiresLayout() {
                return false;
            }
        };
        Logger.getRootLogger().addAppender(appender);
        try {
            return refusal(settings.toArray(new String[0]));
        } finally {
            Logger.getRootLogger().removeAppender(appender);
        }
    }

    @Test
    public void aPasswordThatBreaksTheAddressIsRefusedWithNoPartOfIt() throws Exception {
        for (String protocol : PROTOCOLS) {
            for (String password : BREAKING) {
                for (String encode : new String[]{null, "false"}) {
                    StringBuilder lines = new StringBuilder();
                    String refused = connection(protocol, password, encode, lines);
                    String row = protocol + " " + encode;
                    Assert.assertEquals(refused, "Set the parameter 'encodePassword' to true. The password holds a "
                            + "character that can not stand in the server address as it is.", row);
                    String logged = lines.toString();
                    Assert.assertTrue(logged.contains("encodePassword"), row + " " + logged);
                    Assert.assertFalse(logged.contains(password) || logged.contains("someone7")
                            || logged.contains("host.example"), row + " " + logged);
                    for (String part : password.split("[/?%]")) {
                        Assert.assertFalse(part.length() > 3 && logged.contains(part), row + " " + logged);
                    }
                }
            }
        }
    }

    @Test
    public void aPasswordThatWorksTodayIsNotRefused() throws Exception {
        for (String protocol : PROTOCOLS) {
            for (String password : BREAKING) {
                Assert.assertNull(connection(protocol, password, "true", new StringBuilder()), protocol);
            }
            for (String password : WORKING) {
                Assert.assertNull(connection(protocol, password, null, new StringBuilder()), protocol);
                Assert.assertNull(connection(protocol, password, "false", new StringBuilder()), protocol);
            }
            Assert.assertNull(connection(protocol, "", null, new StringBuilder()), protocol);
            Assert.assertNull(connection(protocol, null, null, new StringBuilder()), protocol);
        }
        Assert.assertNull(refusal("connectionType", "SFTP", "host", "host.example", "port", "2222", "username",
                "someone7", "privateKeyFilePath", "/keys/id_rsa", "workingDir", "in"));
    }

    // The rule of the check: a breaking password never reads as the configured server today, on any protocol and with
    // a domain in the SMB2 user, so the refusal breaks no flow. A working one reads with every field as configured.
    @Test
    public void aBreakingPasswordNeverReadsAsTheConfiguredServer() throws Exception {
        DefaultFileSystemManager manager = new DefaultFileSystemManager();
        manager.addProvider("sftp", new SftpFileProvider());
        manager.addProvider("ftp", new FtpFileProvider());
        manager.addProvider("ftps", new FtpsFileProvider());
        manager.addProvider("smb2", new Smb2FileProvider());
        manager.init();
        try {
            for (String scheme : new String[]{"sftp", "ftp", "ftps", "smb2"}) {
                String[] users = scheme.equals("smb2") ? new String[]{"someone7", "CORP;someone7", "CORP\\someone7"}
                        : new String[]{"someone7"};
                for (String user : users) {
                    for (String password : BREAKING) {
                        Assert.assertFalse(readsAsConfigured(manager, scheme, user, password), scheme + " " + user);
                    }
                    for (String password : WORKING) {
                        Assert.assertTrue(readsAsConfigured(manager, scheme, user, password),
                                scheme + " " + user + " " + password);
                    }
                }
            }
        } finally {
            manager.close();
        }
    }

    private static boolean readsAsConfigured(DefaultFileSystemManager manager, String scheme, String user,
                                             String password) {
        String path = scheme.equals("smb2") ? "/share/in" : "/in";
        try {
            GenericFileName name = (GenericFileName) manager.resolveURI(
                    scheme + "://" + user + ":" + password + "@host.example:2222" + path);
            return user.equals(name.getUserName()) && "host.example".equals(name.getHostName())
                    && name.getPort() == 2222 && name.getPath().endsWith("/in");
        } catch (FileSystemException broken) {
            return false;
        }
    }

    // The refusal leaves the incoming body as it came, as the failure inside the operation did before, so a fault
    // sequence still has the message. A body that was not read yet stays unread.
    @Test
    public void aRefusedPasswordLeavesTheIncomingBodyAsItCame() throws Exception {
        String text = "line one\nline two";
        for (boolean read : new boolean[]{true, false}) {
            try (FilePoolFixture files = new FilePoolFixture("LOCAL")) {
                Map<String, String> values = new HashMap<>();
                values.put("connectionType", "SFTP");
                values.put("name", files.name);
                values.put("fileLockScheme", "Local");
                values.put("host", "host.example");
                values.put("port", "2222");
                values.put("username", "someone7");
                values.put("password", "2024/Summer");
                values.put("workingDir", "in");
                TemplateContext template = new TemplateContext("file.init", Collections.emptyList());
                template.setMappedValues(values);
                Stack<TemplateContext> stack = new Stack<>();
                stack.push(template);
                Axis2MessageContext message = WriteFileTest.message(files);
                message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
                OMElement body = read ? AXIOMUtil.stringToOM("<order><id>7</id></order>")
                        : new PlainTextBuilder().processDocument(new ByteArrayInputStream(
                                text.getBytes(StandardCharsets.UTF_8)), "text/plain", message.getAxis2MessageContext());
                String before = read ? body.toString() : null;
                message.getEnvelope().getBody().addChild(body);
                try {
                    new FileConfig().connect(message);
                    Assert.fail("The password was not refused.");
                } catch (SynapseException refused) {
                    Assert.assertTrue(refused.getCause().getMessage().contains("'encodePassword'"));
                }
                Assert.assertEquals(String.valueOf(message.getProperty("ERROR_CODE")), "700107");
                // No result of the operation is put on the message, not even as a JSON stream beside the body.
                Assert.assertFalse(JsonUtil.hasAJsonPayload(message.getAxis2MessageContext()));
                Assert.assertNull(message.getAxis2MessageContext()
                        .getProperty(Constants.ORG_APACHE_SYNAPSE_COMMONS_JSON_JSON_INPUT_STREAM));
                OMElement after = message.getEnvelope().getBody().getFirstElement();
                Assert.assertSame(after, body);
                if (read) {
                    Assert.assertEquals(after.toString(), before);
                } else {
                    Assert.assertFalse(((OMSourcedElement) after).isExpanded(), "The body was read.");
                    Assert.assertEquals(after.getText(), text);
                }
            }
        }
    }
}
