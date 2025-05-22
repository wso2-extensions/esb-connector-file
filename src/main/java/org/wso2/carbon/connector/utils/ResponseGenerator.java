// package org.wso2.carbon.connector.utils;

// import org.apache.axiom.om.OMElement;
// import org.apache.axiom.soap.SOAPBody;
// import org.apache.commons.logging.Log;
// import org.apache.commons.logging.LogFactory;
// import org.apache.synapse.MessageContext;
// import org.apache.synapse.commons.json.JsonUtil;
// import org.apache.synapse.core.axis2.Axis2MessageContext;
// import org.apache.synapse.transport.passthru.PassThroughConstants;

// import com.google.gson.JsonObject;
// import org.wso2.integration.connector.core.AbstractConnector;

// import java.util.HashMap;
// import java.util.Map;

// // Generated on $timestamp

// /**
//  * The Utils class contains all the utils methods related to the connector.
//  */
// public class ResponseGenerator extends AbstractConnector {

//     private static final Log log = LogFactory.getLog(Utils.class);

//     @Override
//     public void connect(MessageContext messageContext) {
//         try {
//             org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
//             SOAPBody soapBody = axis2MessageContext.getEnvelope().getBody();
//             OMElement responseElement = soapBody.getFirstElement();
//             JsonObject responseJson = JsonUtil.toJSONObject(responseElement.toString());
//             Map<String, Object> responseMap = new HashMap<>();
//             responseMap.put("payload", responseJson);
//             messageContext.setProperty(PassThroughConstants.NO_ENTITY_BODY, false);
//             messageContext.setProperty("RESPONSE", responseMap);
//         } catch (Exception e) {
//             log.error("Error occurred while generating the response", e);
//         }
//     }
// }

