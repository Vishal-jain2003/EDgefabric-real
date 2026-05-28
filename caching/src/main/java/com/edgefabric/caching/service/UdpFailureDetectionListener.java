package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.model.NodeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Listens on the dedicated failure-detection UDP port and responds
 * to PING / PING_REQ messages from other nodes.
 */
@Component
@Profile("!test")
@Slf4j
public class UdpFailureDetectionListener {

    private final int fdPort;
    private final ObjectMapper objectMapper;
    private final FailureDetectionTransport failureDetectionTransport;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "UDP-FailureDetection-Listener");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicReference<DatagramSocket> socket = new AtomicReference<>();

    public UdpFailureDetectionListener(
            FailureDetectorProperties properties,
            ObjectMapper objectMapper,
            FailureDetectionTransport failureDetectionTransport) {
        this.fdPort = properties.getGossipPort();
        this.objectMapper = objectMapper;
        this.failureDetectionTransport = failureDetectionTransport;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void startListening() {
        executorService.submit(this::listen);
    }

    private void listen() {
        running.set(true);
        try (DatagramSocket datagramSocket = new DatagramSocket(fdPort)) {
            socket.set(datagramSocket);
            log.info("UDP Failure Detection Listener started on port {}", fdPort);

            byte[] buffer = new byte[4096];
            while (running.get()) {
                receiveAndProcessPacket(buffer, datagramSocket);
            }
        } catch (SocketException e) {
            if (running.get()) {
                log.error("Socket error in UDP listener", e);
            }
        } finally {
            socket.set(null);
            log.info("UDP Failure Detection Listener stopped");
        }
    }

    private void receiveAndProcessPacket(byte[] buffer, DatagramSocket datagramSocket) {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            datagramSocket.receive(packet);
            processIncomingPacket(packet, datagramSocket);
        } catch (IOException e) {
            if (running.get()) {
                log.error("Error processing UDP packet", e);
            }
        }
    }

    private void processIncomingPacket(DatagramPacket packet, DatagramSocket datagramSocket) throws IOException {
        byte[] messageData = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, messageData, 0, packet.getLength());

        UdpFailureDetectionTransport.UdpMessage message =
                objectMapper.readValue(messageData, UdpFailureDetectionTransport.UdpMessage.class);

        String messageType = message.getMessageType();
        if ("PING".equals(messageType)) {
            handlePing(packet, datagramSocket);
        } else if ("PING_REQ".equals(messageType)) {
            handlePingReq(message, packet, datagramSocket);
        }
    }

    private void handlePing(DatagramPacket packet, DatagramSocket datagramSocket) throws IOException {
        UdpFailureDetectionTransport.UdpMessage ackMessage =
                new UdpFailureDetectionTransport.UdpMessage("PING_ACK", null);
        sendResponse(ackMessage, packet, datagramSocket);
        log.debug("Responded to PING from {}:{}", packet.getAddress().getHostAddress(), packet.getPort());
    }

    private void handlePingReq(UdpFailureDetectionTransport.UdpMessage message,
                               DatagramPacket packet,
                               DatagramSocket datagramSocket) throws IOException {
        Object payload = message.getPayload();
        if (!(payload instanceof UdpFailureDetectionTransport.PingReqMessage pingReqMessage)) {
            log.warn("PING_REQ received with invalid payload type: {}",
                    payload == null ? "null" : payload.getClass().getSimpleName());
            UdpFailureDetectionTransport.UdpMessage nack =
                    new UdpFailureDetectionTransport.UdpMessage("PING_REQ_ACK", false);
            sendResponse(nack, packet, datagramSocket);
            return;
        }

        boolean acknowledged = resolvePingReqAcknowledgement(pingReqMessage);
        UdpFailureDetectionTransport.UdpMessage ackMessage =
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ_ACK", acknowledged);
        sendResponse(ackMessage, packet, datagramSocket);
    }

    private boolean resolvePingReqAcknowledgement(
            UdpFailureDetectionTransport.PingReqMessage pingReqMessage) {
        try {
            NodeInfo targetNode = pingReqMessage.getTargetNode();
            Duration timeout = Duration.ofMillis(pingReqMessage.getTimeoutMs());
            return failureDetectionTransport.sendPing(targetNode, timeout);
        } catch (Exception e) {
            log.warn("Error handling PING_REQ message", e);
            return false;
        }
    }

    private void sendResponse(UdpFailureDetectionTransport.UdpMessage message,
                              DatagramPacket requestPacket,
                              DatagramSocket datagramSocket) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsBytes(message);
        DatagramPacket responsePacket = new DatagramPacket(
                responseBytes,
                responseBytes.length,
                requestPacket.getAddress(),
                requestPacket.getPort());
        datagramSocket.send(responsePacket);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        DatagramSocket current = socket.get();
        if (current != null && !current.isClosed()) {
            current.close();
        }
        executorService.shutdown();
    }
}
