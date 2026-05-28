package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.model.NodeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UdpFailureDetectionListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UdpFailureDetectionListener listener;

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
        }
    }

    @Test
    void respondsToPingWithPingAck() throws Exception {
        int port = findFreeUdpPort();
        FailureDetectionTransport transport = Mockito.mock(FailureDetectionTransport.class);

        listener = createListener(port, transport);
        listener.startListening();
        awaitListenerReady(port);

        UdpFailureDetectionTransport.UdpMessage request =
                new UdpFailureDetectionTransport.UdpMessage("PING", null);

        UdpFailureDetectionTransport.UdpMessage response = sendAndReceive(port, request);

        assertEquals("PING_ACK", response.getMessageType());
    }

    @Test
    void respondsToPingReqWithTransportResult() throws Exception {
        int port = findFreeUdpPort();
        FailureDetectionTransport transport = Mockito.mock(FailureDetectionTransport.class);
        NodeInfo targetNode = new NodeInfo("target-1", "127.0.0.1", 8082, 7946);

        when(transport.sendPing(targetNode, Duration.ofMillis(300))).thenReturn(true);

        listener = createListener(port, transport);
        listener.startListening();
        awaitListenerReady(port);

        UdpFailureDetectionTransport.PingReqMessage pingReqPayload =
                new UdpFailureDetectionTransport.PingReqMessage(targetNode, 300);
        UdpFailureDetectionTransport.UdpMessage request =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ", pingReqPayload);

        UdpFailureDetectionTransport.UdpMessage response = sendAndReceive(port, request);

        assertEquals("PING_REQ_ACK", response.getMessageType());
        assertEquals(Boolean.TRUE, response.getPayload());
        verify(transport).sendPing(targetNode, Duration.ofMillis(300));
    }

    @Test
    void respondsWithNegativeAckWhenPingReqPayloadIsInvalid() throws Exception {
        int port = findFreeUdpPort();
        FailureDetectionTransport transport = Mockito.mock(FailureDetectionTransport.class);

        listener = createListener(port, transport);
        listener.startListening();
        awaitListenerReady(port);

        UdpFailureDetectionTransport.UdpMessage request =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ", Map.of("timeoutMs", 300));

        UdpFailureDetectionTransport.UdpMessage response = sendAndReceive(port, request);

        assertEquals("PING_REQ_ACK", response.getMessageType());
        assertEquals(Boolean.FALSE, response.getPayload());
    }

    @Test
    void respondsToPingReqWithTransportFailure() throws Exception {
        int port = findFreeUdpPort();
        FailureDetectionTransport transport = Mockito.mock(FailureDetectionTransport.class);
        NodeInfo targetNode = new NodeInfo("target-1", "127.0.0.1", 8082, 7946);

        when(transport.sendPing(targetNode, Duration.ofMillis(300))).thenThrow(
                new RuntimeException("Transport failed"));

        listener = createListener(port, transport);
        listener.startListening();
        awaitListenerReady(port);

        UdpFailureDetectionTransport.PingReqMessage pingReqPayload =
                new UdpFailureDetectionTransport.PingReqMessage(targetNode, 300);
        UdpFailureDetectionTransport.UdpMessage request =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ", pingReqPayload);

        UdpFailureDetectionTransport.UdpMessage response = sendAndReceive(port, request);

        assertEquals("PING_REQ_ACK", response.getMessageType());
        assertEquals(Boolean.FALSE, response.getPayload());
    }

    @Test
    void ignoredUnknownMessageType() throws Exception {
        int port = findFreeUdpPort();
        FailureDetectionTransport transport = Mockito.mock(FailureDetectionTransport.class);

        listener = createListener(port, transport);
        listener.startListening();
        awaitListenerReady(port);

        UdpFailureDetectionTransport.UdpMessage request =
                new UdpFailureDetectionTransport.UdpMessage("UNKNOWN_TYPE", null);

        try {
            sendAndReceive(port, request, 500);
            fail("Expected timeout for unknown message type");
        } catch (IOException e) {
            assertEquals("Timed out waiting for UDP response", e.getMessage());
        }
    }

    @Test
    void handlesPingReqWithNullPayload() throws Exception {
        int port = findFreeUdpPort();
        FailureDetectionTransport transport = Mockito.mock(FailureDetectionTransport.class);

        listener = createListener(port, transport);
        listener.startListening();
        awaitListenerReady(port);

        UdpFailureDetectionTransport.UdpMessage request =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ", null);

        // After the fix: invalid payload (null) causes a NACK response instead of silent drop
        UdpFailureDetectionTransport.UdpMessage response = sendAndReceive(port, request, 1000);
        assertEquals("PING_REQ_ACK", response.getMessageType());
        assertEquals(Boolean.FALSE, response.getPayload());
    }

    private UdpFailureDetectionListener createListener(int port, FailureDetectionTransport transport) {
        FailureDetectorProperties props = new FailureDetectorProperties();
        props.setGossipPort(port);
        return new UdpFailureDetectionListener(props, objectMapper, transport);
    }

    private void awaitListenerReady(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            try {
                UdpFailureDetectionTransport.UdpMessage response = sendAndReceive(
                        port,
                        new UdpFailureDetectionTransport.UdpMessage("PING", null),
                        250);
                if ("PING_ACK".equals(response.getMessageType())) {
                    return;
                }
            } catch (IOException ignored) {
                // Listener may still be binding; retry until deadline.
            }
        }
        fail("UDP listener did not become ready in time");
    }

    private UdpFailureDetectionTransport.UdpMessage sendAndReceive(int port,
                                                                   UdpFailureDetectionTransport.UdpMessage message)
            throws Exception {
        return sendAndReceive(port, message, 3000);
    }

    private UdpFailureDetectionTransport.UdpMessage sendAndReceive(int port,
                                                                   UdpFailureDetectionTransport.UdpMessage message,
                                                                   int timeoutMs)
            throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(timeoutMs);

            byte[] requestBytes = objectMapper.writeValueAsBytes(message);
            DatagramPacket requestPacket = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    InetAddress.getByName("127.0.0.1"),
                    port);
            client.send(requestPacket);

            byte[] responseBuffer = new byte[4096];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            client.receive(responsePacket);

            byte[] responseData = new byte[responsePacket.getLength()];
            System.arraycopy(responsePacket.getData(), 0, responseData, 0, responsePacket.getLength());
            return objectMapper.readValue(responseData, UdpFailureDetectionTransport.UdpMessage.class);
        } catch (SocketTimeoutException e) {
            throw new IOException("Timed out waiting for UDP response", e);
        }
    }

    private static int findFreeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Could not allocate free UDP port", e);
        }
    }
}
