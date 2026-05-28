package com.edgefabric.caching.service;

import com.edgefabric.caching.model.NodeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the custom UdpMessageDeserializer — Strategy 3 fix for EPMICMPHE-217.
 * These are pure Jackson/JUnit 5 tests with no Spring context or Mockito needed.
 */
class UdpMessageDeserializerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ---- TC1: PING_REQ payload deserializes to PingReqMessage ----

    @Test
    void pingReqPayloadDeserializesToPingReqMessage() throws Exception {
        String json = """
                {
                  "messageType": "PING_REQ",
                  "payload": {
                    "targetNode": {
                      "cacheNodeId": "node-1",
                      "host": "10.0.0.1",
                      "servicePort": 8082,
                      "gossipPort": 7946
                    },
                    "timeoutMs": 300
                  }
                }
                """;

        UdpFailureDetectionTransport.UdpMessage msg =
                objectMapper.readValue(json, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING_REQ", msg.getMessageType());
        assertInstanceOf(UdpFailureDetectionTransport.PingReqMessage.class, msg.getPayload());

        UdpFailureDetectionTransport.PingReqMessage pingReqMessage =
                (UdpFailureDetectionTransport.PingReqMessage) msg.getPayload();
        assertEquals("node-1", pingReqMessage.getTargetNode().getCacheNodeId());
        assertEquals("10.0.0.1", pingReqMessage.getTargetNode().getHost());
        assertEquals(8082, pingReqMessage.getTargetNode().getServicePort());
        assertEquals(7946, pingReqMessage.getTargetNode().getGossipPort());
        assertEquals(300L, pingReqMessage.getTimeoutMs());
    }

    // ---- TC2: PING_REQ_ACK with true payload deserializes to Boolean.TRUE ----

    @Test
    void pingReqAckTrueDeserializesToBooleanTrue() throws Exception {
        String json = """
                {
                  "messageType": "PING_REQ_ACK",
                  "payload": true
                }
                """;

        UdpFailureDetectionTransport.UdpMessage msg =
                objectMapper.readValue(json, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING_REQ_ACK", msg.getMessageType());
        assertEquals(Boolean.TRUE, msg.getPayload());
    }

    // ---- TC3: PING_REQ_ACK with false payload deserializes to Boolean.FALSE ----

    @Test
    void pingReqAckFalseDeserializesToBooleanFalse() throws Exception {
        String json = """
                {
                  "messageType": "PING_REQ_ACK",
                  "payload": false
                }
                """;

        UdpFailureDetectionTransport.UdpMessage msg =
                objectMapper.readValue(json, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING_REQ_ACK", msg.getMessageType());
        assertEquals(Boolean.FALSE, msg.getPayload());
    }

    // ---- TC4: PING message deserializes with null payload ----

    @Test
    void pingMessageDeserializesWithNullPayload() throws Exception {
        String json = """
                {
                  "messageType": "PING",
                  "payload": null
                }
                """;

        UdpFailureDetectionTransport.UdpMessage msg =
                objectMapper.readValue(json, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING", msg.getMessageType());
        assertNull(msg.getPayload());
    }

    // ---- TC5: PING_ACK message deserializes with null payload ----

    @Test
    void pingAckMessageDeserializesWithNullPayload() throws Exception {
        String json = """
                {
                  "messageType": "PING_ACK",
                  "payload": null
                }
                """;

        UdpFailureDetectionTransport.UdpMessage msg =
                objectMapper.readValue(json, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING_ACK", msg.getMessageType());
        assertNull(msg.getPayload());
    }

    // ---- TC6: Unknown messageType deserializes with null payload, no exception ----

    @Test
    void unknownMessageTypeDeserializesWithNullPayloadNoException() throws Exception {
        String json = """
                {
                  "messageType": "UNKNOWN_FUTURE_TYPE",
                  "payload": {"someField": "someValue"}
                }
                """;

        UdpFailureDetectionTransport.UdpMessage msg =
                assertDoesNotThrow(() ->
                        objectMapper.readValue(json, UdpFailureDetectionTransport.UdpMessage.class));

        assertEquals("UNKNOWN_FUTURE_TYPE", msg.getMessageType());
        assertNull(msg.getPayload());
    }

    // ---- TC7: Malformed JSON throws JsonProcessingException ----

    @Test
    void malformedJsonThrowsJsonProcessingException() {
        String badJson = "{ this is not valid json }";

        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> objectMapper.readValue(badJson, UdpFailureDetectionTransport.UdpMessage.class));
    }

    // ---- TC8: Round-trip PING_REQ preserves all PingReqMessage fields ----

    @Test
    void roundTripPingReqMessagePreservesFields() throws Exception {
        NodeInfo targetNode = new NodeInfo("node-rt", "192.168.1.5", 8082, 7946);
        UdpFailureDetectionTransport.PingReqMessage pingReqPayload =
                new UdpFailureDetectionTransport.PingReqMessage(targetNode, 300L);
        UdpFailureDetectionTransport.UdpMessage original =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ", pingReqPayload);

        byte[] serialized = objectMapper.writeValueAsBytes(original);
        UdpFailureDetectionTransport.UdpMessage deserialized =
                objectMapper.readValue(serialized, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING_REQ", deserialized.getMessageType());
        assertInstanceOf(UdpFailureDetectionTransport.PingReqMessage.class, deserialized.getPayload());

        UdpFailureDetectionTransport.PingReqMessage roundTripped =
                (UdpFailureDetectionTransport.PingReqMessage) deserialized.getPayload();
        assertEquals("node-rt", roundTripped.getTargetNode().getCacheNodeId());
        assertEquals("192.168.1.5", roundTripped.getTargetNode().getHost());
        assertEquals(8082, roundTripped.getTargetNode().getServicePort());
        assertEquals(7946, roundTripped.getTargetNode().getGossipPort());
        assertEquals(300L, roundTripped.getTimeoutMs());
    }

    // ---- TC9: Round-trip PING_REQ_ACK true preserves Boolean.TRUE ----

    @Test
    void roundTripPingReqAckTruePreservesBoolean() throws Exception {
        UdpFailureDetectionTransport.UdpMessage original =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ_ACK", true);

        byte[] serialized = objectMapper.writeValueAsBytes(original);
        UdpFailureDetectionTransport.UdpMessage deserialized =
                objectMapper.readValue(serialized, UdpFailureDetectionTransport.UdpMessage.class);

        assertEquals("PING_REQ_ACK", deserialized.getMessageType());
        assertEquals(Boolean.TRUE, deserialized.getPayload());
    }
}
