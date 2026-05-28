package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.model.NodeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class UdpFailureDetectionTransport implements FailureDetectionTransport {

    /**
     * Number of UDP sockets kept alive in the pool.
     * Each socket is bound once to an OS-assigned ephemeral port and reused for all
     * subsequent PINGs, eliminating per-call socket creation/destruction and the
     * associated file-descriptor churn and TIME_WAIT accumulation.
     *
     * <p>Sized at 8 — well above the maximum concurrent usage
     * (1 direct probe + indirectProbeFanout indirect probes per cycle).
     * If the pool is momentarily exhausted a fresh ephemeral socket is created as a
     * fallback and closed immediately after the call.
     */
    static final int SOCKET_POOL_SIZE = 8;

    private final ObjectMapper objectMapper;
    private final int failureDetectionPort;
    private final LinkedBlockingQueue<DatagramSocket> socketPool;

    private static final String PING_MESSAGE_TYPE = "PING";
    private static final String PING_ACK_MESSAGE_TYPE = "PING_ACK";
    private static final String PING_REQ_MESSAGE_TYPE = "PING_REQ";
    private static final String PING_REQ_ACK_MESSAGE_TYPE = "PING_REQ_ACK";

    public UdpFailureDetectionTransport(ObjectMapper objectMapper,
                                        FailureDetectorProperties properties) {
        this.objectMapper = objectMapper;
        this.failureDetectionPort = properties.getGossipPort();
        this.socketPool = new LinkedBlockingQueue<>(SOCKET_POOL_SIZE);
        for (int i = 0; i < SOCKET_POOL_SIZE; i++) {
            try {
                socketPool.add(new DatagramSocket(0)); // OS-assigned ephemeral port, reused
            } catch (SocketException e) {
                throw new IllegalStateException(
                        "Failed to initialise UDP failure-detection socket pool at slot " + i, e);
            }
        }
        log.info("UDP failure-detection transport initialised with socket pool of size {}",
                SOCKET_POOL_SIZE);
    }

    // ── Public API ───────────────────────────────────────────────────

    @Override
    public boolean sendPing(NodeInfo targetNode, Duration timeout) {
        try {
            UdpMessage pingMessage = new UdpMessage(PING_MESSAGE_TYPE, null);
            UdpMessage response = sendAndWaitForResponse(
                    targetNode.getHost(),
                    failureDetectionPort,
                    pingMessage,
                    timeout);

            return response != null && PING_ACK_MESSAGE_TYPE.equals(response.getMessageType());
        } catch (TimeoutException | SocketException e) {
            log.debug("PING timeout or socket error for node {}: {}", targetNode.getCacheNodeId(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Error sending PING to {}: {}", targetNode.getCacheNodeId(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendPingReq(NodeInfo helperNode, NodeInfo targetNode, Duration timeout) {
        try {
            PingReqMessage pingReqMessage = new PingReqMessage(targetNode, timeout.toMillis());
            UdpMessage message = new UdpMessage(PING_REQ_MESSAGE_TYPE, pingReqMessage);

            UdpMessage response = sendAndWaitForResponse(
                    helperNode.getHost(),
                    failureDetectionPort,
                    message,
                    timeout);

            return response != null && PING_REQ_ACK_MESSAGE_TYPE.equals(response.getMessageType())
                    && Boolean.TRUE.equals(response.getPayload());
        } catch (TimeoutException | SocketException e) {
            log.debug("PING_REQ timeout or socket error for helper node {}, target {}: {}",
                    helperNode.getCacheNodeId(), targetNode.getCacheNodeId(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Error sending PING_REQ to {} for target {}: {}",
                    helperNode.getCacheNodeId(), targetNode.getCacheNodeId(), e.getMessage());
            return false;
        }
    }

    // ── Socket pool lifecycle ────────────────────────────────────────

    /**
     * Drains and closes all pooled sockets on application shutdown.
     */
    @PreDestroy
    public void close() {
        DatagramSocket socket;
        int closed = 0;
        while ((socket = socketPool.poll()) != null) {
            socket.close();
            closed++;
        }
        log.info("UDP failure-detection socket pool closed ({} sockets released)", closed);
    }

    // ── Internal send/receive ────────────────────────────────────────

    /**
     * Borrows a socket from the pool (or creates an ephemeral fallback if the pool is
     * momentarily exhausted), sends {@code message} and waits for a response.
     *
     * <p>The socket is always returned to the pool (with its timeout reset to 0) in the
     * {@code finally} block so it is ready for the next caller. A fallback socket is
     * closed instead of returned.
     */
    private UdpMessage sendAndWaitForResponse(String host, int port, UdpMessage message, Duration timeout)
            throws IOException, TimeoutException {
        DatagramSocket socket = socketPool.poll(); // non-blocking; null if pool is empty
        boolean fromPool = socket != null;
        if (!fromPool) {
            log.debug("Socket pool exhausted; creating one-shot ephemeral socket");
            socket = new DatagramSocket(0);
        }

        try {
            socket.setSoTimeout((int) timeout.toMillis());

            byte[] messageBytes = objectMapper.writeValueAsBytes(message);
            InetAddress address = InetAddress.getByName(host);
            DatagramPacket packet = new DatagramPacket(messageBytes, messageBytes.length, address, port);

            socket.send(packet);

            byte[] responseBuffer = new byte[4096];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);

            socket.receive(responsePacket);

            byte[] receivedData = new byte[responsePacket.getLength()];
            System.arraycopy(responsePacket.getData(), 0, receivedData, 0, responsePacket.getLength());

            return objectMapper.readValue(receivedData, UdpMessage.class);
        } catch (SocketTimeoutException e) {
            throw new TimeoutException("UDP request timeout after " + timeout.toMillis() + "ms");
        } finally {
            if (fromPool) {
                socket.setSoTimeout(0); // reset before returning so next caller sets its own timeout
                if (!socketPool.offer(socket)) {
                    socket.close(); // pool unexpectedly full — close to avoid fd leak
                }
            } else {
                socket.close();
            }
        }
    }

    // ── Message types ────────────────────────────────────────────────

    /**
     * Wrapper class for UDP messages.
     * The custom deserializer resolves the correct payload type based on messageType,
     * fixing the PING_REQ deserialization bug (EPMICMPHE-217).
     */
    @Getter
    @Setter
    @JsonDeserialize(using = UdpMessage.UdpMessageDeserializer.class)
    public static class UdpMessage {
        private String messageType;
        private Object payload;

        /** Required by Jackson for deserialization. */
        public UdpMessage() {
        }

        public UdpMessage(String messageType, Object payload) {
            this.messageType = messageType;
            this.payload = payload;
        }

        /**
         * Custom deserializer that reads messageType first, then deserializes payload to
         * the correct Java type — PingReqMessage for PING_REQ, Boolean for PING_REQ_ACK,
         * and null for PING, PING_ACK, and all unknown types.
         */
        public static class UdpMessageDeserializer extends JsonDeserializer<UdpMessage> {

            @Override
            public UdpMessage deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                ObjectCodec codec = p.getCodec();
                JsonNode root = codec.readTree(p);

                String messageType = root.path("messageType").asText(null);
                JsonNode payloadNode = root.path("payload");

                Object payload = resolvePayload(codec, messageType, payloadNode);
                return new UdpMessage(messageType, payload);
            }

            private Object resolvePayload(ObjectCodec codec, String messageType, JsonNode payloadNode)
                    throws IOException {
                if (payloadNode == null || payloadNode.isNull() || payloadNode.isMissingNode()) {
                    return null;
                }
                return switch (messageType != null ? messageType : "") {
                    case PING_REQ_MESSAGE_TYPE     -> codec.treeToValue(payloadNode, PingReqMessage.class);
                    case PING_REQ_ACK_MESSAGE_TYPE -> payloadNode.asBoolean(false);
                    default             -> null;  // PING, PING_ACK, and unknown types have no payload
                };
            }
        }
    }

    /**
     * Message payload for PING_REQ operations
     */
    @Getter
    @Setter
    public static class PingReqMessage {
        private NodeInfo targetNode;
        private long timeoutMs;

        /** Required by Jackson for deserialization. */
        public PingReqMessage() {
        }

        public PingReqMessage(NodeInfo targetNode, long timeoutMs) {
            this.targetNode = targetNode;
            this.timeoutMs = timeoutMs;
        }
    }
}
