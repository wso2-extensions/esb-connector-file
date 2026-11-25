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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.ApplicationXMLBuilder;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.ApplicationXMLFormatter;
import org.apache.axis2.transport.http.SOAPMessageFormatter;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.connection.FileSystemHandler;
import org.wso2.integration.connector.core.connection.ConnectionLease;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;

import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * A write that replaces the body and fails after it started to write a body that was not built must report its own
 * error, in the result that takes the place of the body and in the fault, and not a failure of the spent parser.
 */
public class WriteReplaceFailureTest {
    private static final String XML = "<order><item>1</item><item>2</item><item>3</item></order>";
    private static final String SOAP = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<s:Header><h:t xmlns:h=\"urn:h\">1</h:t></s:Header><s:Body>" + XML + "</s:Body></s:Envelope>";

    @DataProvider(name = "failures")
    public Object[][] failures() {
        return new Object[][]{
                {"body that breaks late", false, "<order><item>1</item><item></order>", "application/xml", "Overwrite",
                        "700108", "The message body could not be read"},
                {"body that breaks late", true, SOAP.replace("<item>3</item>", "<item>3</order>"), "text/xml",
                        "Overwrite", "700108", "The message body could not be read"},
                {"target lost in the middle", false, XML, "application/xml", "Overwrite", "700101",
                        "The connection was lost in the middle of the write"},
                {"target lost in the middle", true, SOAP, "text/xml", "Overwrite", "700101",
                        "The connection was lost in the middle of the write"},
                {"commit refused after the body", false, XML, "application/x-racing", "Create New", "700103", null},
                {"commit refused after the body", true, SOAP, "application/x-racing", "Create New", "700103", null},
        };
    }

    @Test(dataProvider = "failures")
    public void failedReplacingWriteReportsItsOwnError(String failure, boolean soap, String body, String type,
                                                      String mode, String code, String detail) throws Exception {
        Path directory = WriteFileTest.directory();
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "workingDir", directory.toString(),
                "maxActiveConnections", "1")) {
            if (failure.startsWith("target lost")) {
                breakTargetWrites(files);
            }
            Axis2MessageContext message = WriteFileTest.message(files);
            MessageContext axis = message.getAxis2MessageContext();
            AxisConfiguration configuration = axis.getConfigurationContext().getAxisConfiguration();
            configuration.addMessageFormatter("application/xml", new ApplicationXMLFormatter());
            configuration.addMessageFormatter("text/xml", new SOAPMessageFormatter());
            // Writes the body as application/xml does, then puts a file at the target before the commit.
            configuration.addMessageFormatter("application/x-racing", new ApplicationXMLFormatter() {
                @Override
                public void writeTo(MessageContext context, OMOutputFormat format, OutputStream out,
                                    boolean preserve) throws AxisFault {
                    super.writeTo(context, format, out, preserve);
                    try {
                        Files.write(directory.resolve("target"), "first".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException error) {
                        throw AxisFault.makeFault(error);
                    }
                }
            });
            ByteArrayInputStream content = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            OMElement element = soap ? new SOAPBuilder().processDocument(content, "text/xml", axis)
                    : new ApplicationXMLBuilder().processDocument(content, "application/xml", axis);
            axis.setEnvelope(TransportUtils.createSOAPEnvelope(element));
            Map<String, String> values = new HashMap<>();
            values.put("filePath", "/target");
            values.put("writeMode", mode);
            values.put("mimeType", type);
            TemplateContext template = new TemplateContext("file.write", Collections.emptyList());
            template.setMappedValues(values);
            Stack<TemplateContext> stack = new Stack<>();
            stack.push(template);
            message.setProperty("_SYNAPSE_FUNCTION_STACK", stack);
            Throwable thrown = null;
            try {
                new WriteFile().execute(message, "result", true);
            } catch (Throwable error) {
                thrown = error;
            }
            Assert.assertTrue(thrown instanceof SynapseException, String.valueOf(thrown));
            JsonObject result = JsonParser.parseString(JsonUtil.jsonPayloadToString(axis)).getAsJsonObject();
            Assert.assertFalse(result.get("success").getAsBoolean(), result.toString());
            Assert.assertEquals(result.getAsJsonObject("error").get("code").getAsString(), code, result.toString());
            if (detail != null) {
                Assert.assertTrue(result.getAsJsonObject("error").get("detail").getAsString().contains(detail),
                        result.toString());
                Assert.assertTrue(thrown.getMessage().contains(detail), thrown.getMessage());
            }
            // The whole envelope reads after the write, with the result in place of the body.
            Assert.assertTrue(axis.getEnvelope().toString().contains("jsonObject"), axis.getEnvelope().toString());
        }
    }

    // The output of a temp file of the write fails after its first bytes, as a lost connection does.
    private static void breakTargetWrites(FilePoolFixture files) throws Exception {
        ConnectionLease lease = files.borrow();
        DefaultFileSystemManager manager =
                (DefaultFileSystemManager) ((FileSystemHandler) lease.getConnection()).getFsManager();
        manager.removeProvider("file");
        manager.addProvider("file", new DefaultLocalFileProvider() {
            @Override
            public FileObject findFile(FileObject base, String uri, FileSystemOptions options)
                    throws FileSystemException {
                FileObject real = super.findFile(base, uri, options);
                if (!uri.contains("/.part/")) {
                    return real;
                }
                FileContent content = real.getContent();
                FileContent breaking = (FileContent) Proxy.newProxyInstance(
                        WriteReplaceFailureTest.class.getClassLoader(), new Class<?>[]{FileContent.class},
                        (proxy, method, args) -> {
                            Object value;
                            try {
                                value = method.invoke(content, args);
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            }
                            if (!"getOutputStream".equals(method.getName())) {
                                return value;
                            }
                            return new FilterOutputStream((OutputStream) value) {
                                private int written;

                                @Override
                                public void write(byte[] bytes, int offset, int length) throws IOException {
                                    for (int i = 0; i < length; i++) {
                                        write(bytes[offset + i]);
                                    }
                                }

                                @Override
                                public void write(int value) throws IOException {
                                    if (++written > 10) {
                                        throw new IOException("The connection was lost in the middle of the write");
                                    }
                                    super.write(value);
                                }
                            };
                        });
                return new DecoratedFileObject(real) {
                    @Override
                    public FileContent getContent() {
                        return breaking;
                    }
                };
            }
        });
        lease.returnToPool();
    }
}
