package com.edgefabric.caching.config;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.function.client.WebClient;

import static org.mockito.Mockito.mock;

import java.net.DatagramSocket;
import java.net.SocketException;

import static org.junit.jupiter.api.Assertions.*;

class GossipConfigTest {

    private GossipConfig gossipConfig;
    private GossipProperties gossipProperties;
    private FailureDetectorProperties fdProperties;

    /**
     * Shared gossip socket opened once per test. Using port 0 so the OS assigns a
     * free ephemeral port — avoids flakiness from "port already in use".
     * Closed in @AfterEach to avoid fd leaks across tests.
     */
    private DatagramSocket testGossipSocket;

    @BeforeEach
    void setUp() throws SocketException {
        gossipConfig = new GossipConfig();
        gossipProperties = new GossipProperties();
        gossipProperties.setPort(0); // use any free port in tests

        // Open the shared socket; getLocalPort() gives us the actual OS-assigned port.
        testGossipSocket = gossipConfig.gossipSocket(gossipProperties);

        // FailureDetector default port is 7000 — far from any ephemeral port, so no collision.
        fdProperties = new FailureDetectorProperties();
    }

    @AfterEach
    void tearDown() {
        if (testGossipSocket != null && !testGossipSocket.isClosed()) {
            testGossipSocket.close();
        }
    }

    @Test
    void shouldCreateSelfNodeInfoWithExplicitIp() {
        NodeInfo node = gossipConfig.selfNodeInfo("192.168.1.10", 8082, fdProperties, testGossipSocket);

        assertEquals("cache-node-192.168.1.10-8082", node.getCacheNodeId());
        assertEquals("192.168.1.10", node.getHost());
        assertEquals(8082, node.getServicePort());
        // Issue 9: gossip port must reflect the socket's ACTUAL bound port, not the configured value.
        assertEquals(testGossipSocket.getLocalPort(), node.getGossipPort());
    }

    @Test
    void shouldFallbackToLocalhostWhenNodeIpIsNull() {
        NodeInfo node = gossipConfig.selfNodeInfo(null, 8082, fdProperties, testGossipSocket);

        assertNotNull(node.getHost());
        assertFalse(node.getHost().isBlank());
        assertTrue(node.getCacheNodeId().startsWith("cache-node-"));
        assertEquals(testGossipSocket.getLocalPort(), node.getGossipPort());
    }

    @Test
    void shouldFallbackWhenNodeIpIsBlank() {
        NodeInfo node = gossipConfig.selfNodeInfo("  ", 8082, fdProperties, testGossipSocket);

        assertNotNull(node.getHost());
        assertFalse(node.getHost().isBlank());
        assertEquals(testGossipSocket.getLocalPort(), node.getGossipPort());
    }

    @Test
    void shouldFallbackWhenNodeIpIsLocalhost() {
        NodeInfo node = gossipConfig.selfNodeInfo("localhost", 8082, fdProperties, testGossipSocket);

        assertNotNull(node.getHost());
        // resolveHost skips "localhost" and falls back to HOSTNAME or 127.0.0.1
        assertEquals(testGossipSocket.getLocalPort(), node.getGossipPort());
    }

    /**
     * Issue 2: when gossip port == failure-detection port, startup must throw
     * immediately rather than letting two listeners silently fight over the same socket.
     */
    @Test
    void shouldThrowWhenGossipPortEqualsFailureDetectionPort() {
        int actualGossipPort = testGossipSocket.getLocalPort();

        FailureDetectorProperties collidingFdProps = new FailureDetectorProperties();
        collidingFdProps.setGossipPort(actualGossipPort); // deliberately same port

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                gossipConfig.selfNodeInfo("192.168.1.10", 8082, collidingFdProps, testGossipSocket));

        assertTrue(ex.getMessage().contains(String.valueOf(actualGossipPort)),
                "Exception message should mention the conflicting port");
        assertTrue(ex.getMessage().toLowerCase().contains("conflict") ||
                   ex.getMessage().toLowerCase().contains("same"),
                "Exception message should clearly describe the problem");
    }

    @Test
    void shouldCreateMembershipList() {
        NodeInfo node = new NodeInfo("test-node", "10.0.0.1", 8082, 7946);
        MembershipList list = gossipConfig.membershipList(node, mock(ApplicationEventPublisher.class));

        assertNotNull(list);
        assertEquals(node, list.getSelf());
    }

    @Test
    void shouldCreateGossipSocket() throws SocketException {
        DatagramSocket socket = gossipConfig.gossipSocket(gossipProperties);
        assertNotNull(socket);
        assertFalse(socket.isClosed());
        socket.close();
    }

    @Test
    void shouldFallbackToRandomPortWhenConfiguredPortUnavailable() throws SocketException {
        // Bind first socket to an ephemeral port and then try to reuse that port
        DatagramSocket first = new DatagramSocket(0);
        int busyPort = first.getLocalPort();

        GossipProperties propsWithBusyPort = new GossipProperties();
        propsWithBusyPort.setPort(busyPort);

        DatagramSocket second = gossipConfig.gossipSocket(propsWithBusyPort);
        assertNotNull(second);
        assertFalse(second.isClosed());
        // Should have fallen back to a different port
        assertNotEquals(busyPort, second.getLocalPort());

        first.close();
        second.close();
    }

    @Test
    void shouldCreateGossipWebClient() {
        WebClient webClient = gossipConfig.gossipWebClient();
        assertNotNull(webClient);
    }
}
