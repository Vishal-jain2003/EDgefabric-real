package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.model.NodeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UdpFailureDetectionTransportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendPingReturnsTrueOnPingAck() throws Exception {
        try (UdpResponder responder = UdpResponder.json(objectMapper,
                new UdpFailureDetectionTransport.UdpMessage("PING_ACK", null))) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo target = new NodeInfo("node-a", "127.0.0.1", 8082, responder.port());

            boolean acknowledged = transport.sendPing(target, Duration.ofMillis(500));

            assertTrue(acknowledged);
        }
    }

    @Test
    void sendPingReturnsFalseOnTimeout() {
        int freePort = findFreeUdpPort();
        UdpFailureDetectionTransport transport = createTransport(freePort);
        NodeInfo target = new NodeInfo("node-timeout", "127.0.0.1", 8082, freePort);

        boolean acknowledged = transport.sendPing(target, Duration.ofMillis(150));

        assertFalse(acknowledged);
    }

    @Test
    void sendPingReturnsFalseOnMalformedResponse() throws Exception {
        byte[] invalidJson = "not-json".getBytes(StandardCharsets.UTF_8);
        try (UdpResponder responder = UdpResponder.raw(invalidJson)) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo target = new NodeInfo("node-bad", "127.0.0.1", 8082, responder.port());

            boolean acknowledged = transport.sendPing(target, Duration.ofMillis(500));

            assertFalse(acknowledged);
        }
    }

    @Test
    void sendPingReqReturnsTrueWhenHelperAcknowledges() throws Exception {
        try (UdpResponder responder = UdpResponder.json(objectMapper,
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ_ACK", true))) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo helper = new NodeInfo("helper", "127.0.0.1", 8082, responder.port());
            NodeInfo target = new NodeInfo("target", "127.0.0.1", 8083, 7946);

            boolean acknowledged = transport.sendPingReq(helper, target, Duration.ofMillis(500));

            assertTrue(acknowledged);
        }
    }

    @Test
    void sendPingReqReturnsFalseWhenHelperRejects() throws Exception {
        try (UdpResponder responder = UdpResponder.json(objectMapper,
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ_ACK", false))) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo helper = new NodeInfo("helper", "127.0.0.1", 8082, responder.port());
            NodeInfo target = new NodeInfo("target", "127.0.0.1", 8083, 7946);

            boolean acknowledged = transport.sendPingReq(helper, target, Duration.ofMillis(500));

            assertFalse(acknowledged);
        }
    }

    @Test
    void sendPingReturnsFalseOnWrongResponseType() throws Exception {
        // Responder sends PING_REQ_ACK instead of PING_ACK
        try (UdpResponder responder = UdpResponder.json(objectMapper,
                new UdpFailureDetectionTransport.UdpMessage("PING_REQ_ACK", null))) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo target = new NodeInfo("node-wrong", "127.0.0.1", 8082, responder.port());

            boolean acknowledged = transport.sendPing(target, Duration.ofMillis(500));

            assertFalse(acknowledged);
        }
    }

    @Test
    void sendPingReqReturnsFalseOnWrongResponseType() throws Exception {
        // Responder sends PING_ACK instead of PING_REQ_ACK
        try (UdpResponder responder = UdpResponder.json(objectMapper,
                new UdpFailureDetectionTransport.UdpMessage("PING_ACK", null))) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo helper = new NodeInfo("helper", "127.0.0.1", 8082, responder.port());
            NodeInfo target = new NodeInfo("target", "127.0.0.1", 8083, 7946);

            boolean acknowledged = transport.sendPingReq(helper, target, Duration.ofMillis(500));

            assertFalse(acknowledged);
        }
    }

    private UdpFailureDetectionTransport createTransport(int port) {
        FailureDetectorProperties props = new FailureDetectorProperties();
        props.setGossipPort(port);
        return new UdpFailureDetectionTransport(objectMapper, props);
    }

    /**
     * Issue 3: the transport must reuse sockets from its pool across consecutive PINGs,
     * not create a new socket per call. We verify this by recording which source ports
     * were seen by the responder over multiple PING rounds — with a pool of reused sockets
     * the set of source ports stays small (≤ SOCKET_POOL_SIZE), not growing unboundedly.
     */
    @Test
    void socketPoolIsReusedAcrossMultiplePings() throws Exception {
        int rounds = 20; // well above pool size (8), so reuse is required
        Set<Integer> observedSourcePorts = new HashSet<>();

        try (PortCapturingResponder responder = new PortCapturingResponder(objectMapper, rounds)) {
            UdpFailureDetectionTransport transport = createTransport(responder.port());
            NodeInfo target = new NodeInfo("node-pool-test", "127.0.0.1", 8082, responder.port());

            for (int i = 0; i < rounds; i++) {
                transport.sendPing(target, Duration.ofMillis(500));
            }

            responder.awaitCompletion(5, TimeUnit.SECONDS);
            observedSourcePorts.addAll(responder.sourcePorts());
        }

        // With a pool of SOCKET_POOL_SIZE sockets, source ports must not grow unboundedly.
        // We allow a small margin for the fallback path, but definitely not one port per call.
        assertTrue(observedSourcePorts.size() <= UdpFailureDetectionTransport.SOCKET_POOL_SIZE,
                "Expected at most " + UdpFailureDetectionTransport.SOCKET_POOL_SIZE +
                " unique source ports (pool size) across " + rounds + " pings, but saw: " +
                observedSourcePorts.size());
    }

    private static int findFreeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Could not allocate free UDP port", e);
        }
    }

    private static final class UdpResponder implements AutoCloseable {
        private final DatagramSocket socket;
        private final CountDownLatch ready;
        private final byte[] response;
        private volatile boolean running = true;

        private UdpResponder(byte[] responseBytes) throws Exception {
            this.socket = new DatagramSocket(0);
            this.response = responseBytes;
            this.ready = new CountDownLatch(1);
            Thread thread = new Thread(this::run, "udp-test-responder");
            thread.setDaemon(true);
            thread.start();
            if (!ready.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("UDP responder did not start");
            }
        }

        static UdpResponder json(ObjectMapper mapper, UdpFailureDetectionTransport.UdpMessage message) throws Exception {
            return new UdpResponder(mapper.writeValueAsBytes(message));
        }

        static UdpResponder raw(byte[] bytes) throws Exception {
            return new UdpResponder(bytes);
        }

        int port() {
            return socket.getLocalPort();
        }

        private void run() {
            ready.countDown();
            if (!running) {
                return;
            }
            byte[] buf = new byte[4096];
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                DatagramPacket responsePacket = new DatagramPacket(
                        response,
                        response.length,
                        InetAddress.getByName(packet.getAddress().getHostAddress()),
                        packet.getPort());
                socket.send(responsePacket);
            } catch (Exception e) {
                // Single-shot responder — socket closed or error terminates it
            }
        }

        @Override
        public void close() {
            running = false;
            socket.close();
        }
    }

    /**
     * A multi-shot UDP responder that records the source port of every incoming packet.
     * Used to verify that the transport's socket pool is reused across multiple calls.
     */
    private static final class PortCapturingResponder implements AutoCloseable {
        private final DatagramSocket socket;
        private final CountDownLatch latch;
        private final Set<Integer> sourcePorts = new java.util.concurrent.ConcurrentSkipListSet<>();
        private final ObjectMapper objectMapper;
        private volatile boolean running = true;

        PortCapturingResponder(ObjectMapper objectMapper, int expectedRequests) throws Exception {
            this.objectMapper = objectMapper;
            this.socket = new DatagramSocket(0);
            this.latch = new CountDownLatch(expectedRequests);
            Thread thread = new Thread(this::run, "port-capturing-responder");
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return socket.getLocalPort(); }

        Set<Integer> sourcePorts() { return sourcePorts; }

        void awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            latch.await(timeout, unit);
        }

        private void run() {
            byte[] ack = new byte[0];
            try {
                ack = objectMapper.writeValueAsBytes(
                        new UdpFailureDetectionTransport.UdpMessage("PING_ACK", null));
            } catch (Exception ignored) {}

            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    sourcePorts.add(packet.getPort()); // record the sender's ephemeral port
                    DatagramPacket resp = new DatagramPacket(ack, ack.length,
                            packet.getAddress(), packet.getPort());
                    socket.send(resp);
                    latch.countDown();
                } catch (Exception ignored) {
                    if (!running) break;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            socket.close();
        }
    }
}

