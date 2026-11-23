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
package org.wso2.carbon.connector.connection;

import com.google.gson.JsonObject;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.connector.frame.Attempt;
import org.wso2.carbon.connector.frame.OperationFrame;
import org.wso2.carbon.connector.frame.StreamLease;
import org.wso2.carbon.connector.frame.StreamLeases;
import org.wso2.carbon.connector.operations.FileFrameLeaseTest;
import org.wso2.carbon.connector.operations.FilePoolFixture;
import org.wso2.carbon.connector.utils.Error;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.pool.CircuitBreaker;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.vfs.shared.lock.HeldLock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RetryRefusalTest {
    @DataProvider
    public Object[][] refusals() {
        return new Object[][] {
                {true, null, Error.FILE_LOCKING_ERROR},
                {false, null, Error.CONNECTION_ERROR},
                {false, MovePhase.RENAME, Error.OUTCOME_UNCLEAR},
                {false, MovePhase.DELETE_SOURCE, Error.OUTCOME_UNCLEAR}
        };
    }

    @Test(dataProvider = "refusals")
    public void busyLeaseBeforeBorrow(boolean initiallyBusy, MovePhase phase, Error expected) throws Exception {
        try (FilePoolFixture files = new FilePoolFixture("LOCAL", "retriesBeforeSuspension", "1")) {
            AtomicLong clock = new AtomicLong();
            ConnectionContext context = StreamLeaseTest.virtualContext(files,
                    () -> clock.getAndAdd(10000), HeldLock::releaseChecked);
            FileFrameLeaseTest.CountingPool pool = FileFrameLeaseTest.install(files);
            String path = "file:///retry-refusal";
            HeldLock held = context.getShareLock().enterLocalOnly(path, 1, 0).handle;
            StreamLease other = StreamLease.replay(context, path, 1, 1, held);
            StreamLease.Lending token = other.lend();
            Assert.assertNotNull(token);
            org.apache.axis2.context.MessageContext axis = new org.apache.axis2.context.MessageContext();
            axis.setEnvelope(OMAbstractFactory.getSOAP11Factory().getDefaultEnvelope());
            Axis2MessageContext message = new Axis2MessageContext(axis, new SynapseConfiguration(), null);
            StreamLeases holder = StreamLeases.forRead(message);
            if (initiallyBusy) {
                holder.put(other);
            }
            AtomicInteger calls = new AtomicInteger();
            OperationFrame.Operation<Void> op = new OperationFrame.Operation<Void>() {
                public LinkedHashMap<String, OperationFrame.Lock> paths() {
                    LinkedHashMap<String, OperationFrame.Lock> paths = new LinkedHashMap<>();
                    paths.put(path, OperationFrame.Lock.NONE);
                    return paths;
                }
                public OperationFrame.Use use(String key) { return OperationFrame.Use.WRITE; }
                public OperationFrame.Retry retry() { return OperationFrame.Retry.MOVE; }
                public Void removed(String key) { throw new AssertionError(key); }
                public Void steps(Attempt attempt) throws Exception {
                    Assert.assertEquals(calls.incrementAndGet(), 1);
                    holder.put(other);
                    if (phase != null) {
                        attempt.moving(path, path + ".target");
                        attempt.member("member");
                        throw new UnclearResultException("Lost move reply", null, phase, path + ".tmp", false);
                    }
                    FileSystemException lost = new FileSystemException("Lost connection before change");
                    lost.setFault(Fault.TRANSPORT);
                    throw lost;
                }
            };
            OperationFrame.Mediator mediator = new OperationFrame.Mediator() {
                public void execute(MessageContext mc, String variable, Boolean body) throws ConnectException {
                    runFrame(mc, files.name, 1, op, "move", variable, body);
                }
            };
            try {
                mediator.execute(message, "result", false);
                Assert.fail("The busy lease admitted the operation");
            } catch (SynapseException expectedFault) {
                JsonObject payload = (JsonObject) ((ConnectorResponse) message.getVariable("result")).getPayload();
                Map<String, Object> health = context.getHealth().snapshot();
                JsonObject inventory = new JsonObject();
                pool.inventory(inventory);
                int failures = initiallyBusy ? 0 : 1;
                Assert.assertEquals(pool.getBreaker().getMode(), CircuitBreaker.Mode.CLOSED);
                // With threshold one, this additional report opens the breaker only after one operation failure.
                pool.getBreaker().admit().reportFailure();
                String detail = "payload=" + payload + " health=" + health + " loans=" + inventory
                        + " breaker after probe=" + pool.getBreaker().getMode();
                Assert.assertEquals(message.getProperty("ERROR_CODE"), expected.getErrorCode(), detail);
                Assert.assertEquals(message.getProperty("ERROR_MESSAGE"), expected.getErrorDetail(), detail);
                Assert.assertEquals(payload.getAsJsonObject("error").get("code").getAsString(), expected.getErrorCode());
                Assert.assertEquals(payload.has("targetComplete"), phase == MovePhase.DELETE_SOURCE);
                if (phase == MovePhase.DELETE_SOURCE) {
                    Assert.assertTrue(payload.get("targetComplete").getAsBoolean());
                }
                Assert.assertEquals(payload.has("filesLeft"), phase != null);
                if (phase != null) {
                    JsonObject member = payload.getAsJsonArray("filesLeft").get(0).getAsJsonObject();
                    Assert.assertEquals(member.get("name").getAsString(), "member");
                    Assert.assertEquals(member.get("reason").getAsString(), "UNCLEAR");
                }
                Assert.assertEquals(health.get("transportFaults"), (long) failures, detail);
                Assert.assertEquals(health.get("filesFailed"), (long) failures, detail);
                Assert.assertEquals(pool.getBreaker().getMode(), initiallyBusy
                        ? CircuitBreaker.Mode.CLOSED : CircuitBreaker.Mode.OPEN, detail);
                Assert.assertEquals(calls.get(), failures);
                Assert.assertEquals(inventory.getAsJsonArray("borrowedLoans").size(), failures);
                if (!initiallyBusy) {
                    String loan = inventory.getAsJsonArray("borrowedLoans").get(0).getAsString();
                    Assert.assertEquals(inventory.getAsJsonObject("loanFates").get(loan).toString(),
                            "[\"invalidated\"]");
                }
                Assert.assertEquals(pool.getNumActive(), 0);
                Assert.assertEquals(other.state(), StreamLease.State.LENT);
            } finally {
                token.giveBack(null);
                other.retire();
            }
        }
    }
}
