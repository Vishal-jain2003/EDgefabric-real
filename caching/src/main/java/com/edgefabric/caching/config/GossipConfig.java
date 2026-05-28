package com.edgefabric.caching.config;

import com.edgefabric.caching.membership.InMemoryMembershipList;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

@Slf4j
@Configuration
@Profile("!test")
public class GossipConfig {

    /**
     * Constructs the self {@link NodeInfo} using the <em>actual</em> port that the
     * gossip UDP socket is bound to. This matters when the configured port was already
     * in use and the socket fell back to an OS-assigned ephemeral port — using the
     * configured port in that case would cause other nodes to gossip to the wrong port.
     *
     * <p>Also validates (Issue 2) that the gossip port and the failure-detection port
     * are not the same. A collision would cause the two UDP listeners to fight over
     * the same port and silently drop each other's packets.
     */
    @Bean
    public NodeInfo selfNodeInfo(
            @Value("${node.ip:#{null}}") String nodeIp,
            @Value("${server.port:8082}") int servicePort,
            FailureDetectorProperties failureDetectorProperties,
            DatagramSocket gossipSocket) {

        // Issue 9 fix: read the port the OS actually assigned, not the configured value.
        int actualGossipPort = gossipSocket.getLocalPort();

        // Issue 2 fix: fail fast if both listeners would compete for the same port.
        int fdPort = failureDetectorProperties.getGossipPort();
        if (actualGossipPort == fdPort) {
            throw new IllegalStateException(
                    "Port conflict detected at startup: gossip port and failure-detection port " +
                    "are both " + actualGossipPort + ". " +
                    "Set 'gossip.port' and 'failure-detector.gossip-port' to different values.");
        }

        String host = resolveHost(nodeIp);
        String nodeId = "cache-node-" + host + "-" + servicePort;
        NodeInfo self = new NodeInfo(nodeId, host, servicePort, actualGossipPort);
        log.info("Self node identity: {} | gossip port: {} (fd port: {})",
                nodeId, actualGossipPort, fdPort);
        return self;
    }

    @Bean
    public MembershipList membershipList(NodeInfo selfNodeInfo,
                                         ApplicationEventPublisher eventPublisher) {
        InMemoryMembershipList list = new InMemoryMembershipList(selfNodeInfo);
        list.setEventPublisher(eventPublisher);
        return list;
    }

    /**
     * Dedicated UDP socket for gossip protocol traffic.
     * Tries the configured port first; falls back to any free port if busy.
     */
    @Bean(destroyMethod = "close")
    public DatagramSocket gossipSocket(GossipProperties gossipProperties) throws SocketException {
        int configuredPort = gossipProperties.getPort();
        try {
            DatagramSocket socket = new DatagramSocket(configuredPort);
            log.info("UDP gossip socket bound to configured port {}", configuredPort);
            return socket;
        } catch (SocketException e) {
            log.warn("Configured gossip port {} unavailable, binding to random free port", configuredPort);
            DatagramSocket socket = new DatagramSocket(0);
            log.info("UDP gossip socket bound to fallback port {}", socket.getLocalPort());
            return socket;
        }
    }

    @Bean("gossipWebClient")
    public WebClient gossipWebClient() {
        return WebClient.builder().build();
    }

    private String resolveHost(String nodeIp) {
        if (nodeIp != null && !nodeIp.isBlank() && !"localhost".equals(nodeIp)) {
            return nodeIp;
        }
        try {
            String hostname = System.getenv("HOSTNAME");
            if (hostname != null && !hostname.isBlank()) {
                return InetAddress.getByName(hostname).getHostAddress();
            }
        } catch (Exception e) {
            log.warn("Could not resolve hostname to IP address, falling back to 127.0.0.1", e);
        }
        return "127.0.0.1";
    }
}
