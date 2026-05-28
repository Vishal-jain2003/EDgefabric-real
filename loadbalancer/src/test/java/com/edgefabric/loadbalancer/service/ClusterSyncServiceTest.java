package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.config.ClusterSyncProperties;
import com.edgefabric.loadbalancer.exception.ClusterBootstrapException;
import com.edgefabric.loadbalancer.metrics.ClusterSyncMetricsService;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.resolver.DnsResolver;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterSyncServiceTest {

    @Mock
    private CacheRouter cacheRouter;

    @Mock
    private DnsResolver dnsResolver;

    @Mock
    private WebClient webClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Mock
    private ClusterSyncMetricsService metricsService;

    private ClusterSyncProperties props;
    private ClusterSyncService service;

    @BeforeEach
    void setUp() {
        props = new ClusterSyncProperties();
        props.setDnsName("cache-nodes.cache-cluster.internal");
        props.setNodePort(8080);
        props.setMembershipPath("/internal/cluster/members");
        props.setSyncIntervalMs(5000);
        props.setSyncTimeoutMs(3000);
        props.setBootstrapMaxRetries(3);
        props.setBootstrapRetryDelayMs(10);
    }

    // ─────────────────────── Bootstrap ────────────────────────────────────

    @Nested
    @DisplayName("bootstrap")
    class BootstrapTests {

        @Test
        @DisplayName("succeeds on first attempt when DNS returns nodes")
        void successOnFirstAttempt() throws UnknownHostException {
            InetAddress addr = InetAddress.getByName("10.0.1.1");
            when(dnsResolver.resolve(props.getDnsName())).thenReturn(new InetAddress[]{addr});

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);
            service.bootstrap();

            assertEquals(1, service.getActiveNodeCount());
            verify(cacheRouter).addNode(argThat(n -> n.getHost().equals("10.0.1.1")));
        }

        @Test
        @DisplayName("retries and succeeds on later attempt")
        void succeedsAfterRetry() throws UnknownHostException {
            InetAddress addr = InetAddress.getByName("10.0.1.2");
            when(dnsResolver.resolve(props.getDnsName()))
                    .thenThrow(new UnknownHostException("DNS not ready"))
                    .thenReturn(new InetAddress[]{addr});

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);
            service.bootstrap();

            assertEquals(1, service.getActiveNodeCount());
            verify(dnsResolver, times(2)).resolve(props.getDnsName());
        }

        @Test
        @DisplayName("logs warning and records sync error after all retries exhausted")
        void throwsAfterAllRetriesExhausted() throws UnknownHostException {
            when(dnsResolver.resolve(props.getDnsName()))
                    .thenThrow(new UnknownHostException("DNS unavailable"));

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            assertDoesNotThrow(() -> service.bootstrap());

            assertEquals(0, service.getActiveNodeCount());
            verify(dnsResolver, times(3)).resolve(props.getDnsName());
            verify(metricsService).recordSyncError();
        }

        @Test
        @DisplayName("logs warning and records sync error when DNS returns empty every time")
        void throwsWhenDnsReturnsEmptyEveryTime() throws UnknownHostException {
            when(dnsResolver.resolve(props.getDnsName()))
                    .thenReturn(new InetAddress[]{});

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            assertDoesNotThrow(() -> service.bootstrap());

            assertEquals(0, service.getActiveNodeCount());
            verify(metricsService).recordSyncError();
        }

        @Test
        @DisplayName("discovers multiple seed nodes from DNS")
        void discoversMultipleNodes() throws UnknownHostException {
            InetAddress[] addrs = {
                    InetAddress.getByName("10.0.1.1"),
                    InetAddress.getByName("10.0.1.2"),
                    InetAddress.getByName("10.0.1.3")
            };
            when(dnsResolver.resolve(props.getDnsName())).thenReturn(addrs);

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);
            service.bootstrap();

            assertEquals(3, service.getActiveNodeCount());
            verify(cacheRouter, times(3)).addNode(any(CacheNode.class));
        }
    }

    // ─────────────────────── buildMembershipUrl ──────────────────────────

    @Test
    @DisplayName("buildMembershipUrl constructs correct URL from peer")
    void buildMembershipUrl_correctFormat() {
        service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);
        CacheNode peer = new CacheNode("10.0.1.5", "10.0.1.5", 8080);

        String url = service.buildMembershipUrl(peer);

        assertEquals("http://10.0.1.5:8080/internal/cluster/members", url);
    }

    // ────────────────── fetchMembershipFromRandomPeer ───────────────────

    @Nested
    @DisplayName("fetchMembershipFromRandomPeer")
    class FetchMembershipFromRandomPeerTests {

        private MockWebServer mockWebServer;

        @BeforeEach
        void startServer() throws IOException {
            mockWebServer = new MockWebServer();
            mockWebServer.start();
            props.setNodePort(mockWebServer.getPort());

            WebClient realWebClient = WebClient.builder().build();
            service = new ClusterSyncService(cacheRouter, realWebClient, props, dnsResolver, metricsService);
            service.updateHashRing(Set.of(
                    new CacheNode("test-node", "localhost", mockWebServer.getPort())));
        }

        @AfterEach
        void stopServer() throws IOException {
            mockWebServer.shutdown();
        }

        @Test
        @DisplayName("returns empty Optional when no known peers exist")
        void noPeers_returnsEmpty() {
            // fresh service with no peers in the ring
            service = new ClusterSyncService(cacheRouter, WebClient.builder().build(), props, dnsResolver, metricsService);

            Optional<Set<CacheNode>> result = service.fetchMembershipFromRandomPeer();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns members when peer membership call succeeds")
        void successfulPeerCall() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("[{\"nodeId\":\"10.0.1.1\",\"host\":\"10.0.1.1\",\"port\":8080}," +
                             "{\"nodeId\":\"10.0.1.2\",\"host\":\"10.0.1.2\",\"port\":8080}]"));

            Optional<Set<CacheNode>> result = service.fetchMembershipFromRandomPeer();

            assertTrue(result.isPresent());
            assertEquals(2, result.get().size());
        }

        @Test
        @DisplayName("returns empty when peer is unreachable")
        void peerUnreachable_returnsEmpty() throws IOException {
            // Stop the server so connection is refused
            mockWebServer.shutdown();

            Optional<Set<CacheNode>> result = service.fetchMembershipFromRandomPeer();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty when peer call gets HTTP error")
        void httpError_returnsEmpty() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            Optional<Set<CacheNode>> result = service.fetchMembershipFromRandomPeer();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty when peer response body is empty")
        void emptyResponseBody_returnsEmpty() {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("[]"));

            Optional<Set<CacheNode>> result = service.fetchMembershipFromRandomPeer();

            assertTrue(result.isEmpty());
        }
    }

    // ──────────────────────── tryDnsFallback ─────────────────────────────

    @Nested
    @DisplayName("tryDnsFallback")
    class TryDnsFallbackTests {

        @Test
        @DisplayName("returns nodes when DNS resolution succeeds")
        void dnsSucceeds() throws UnknownHostException {
            InetAddress addr = InetAddress.getByName("10.0.1.1");
            when(dnsResolver.resolve(props.getDnsName())).thenReturn(new InetAddress[]{addr});

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            Optional<Set<CacheNode>> result = service.tryDnsFallback();

            assertTrue(result.isPresent());
            assertEquals(1, result.get().size());
        }

        @Test
        @DisplayName("returns empty when DNS throws UnknownHostException")
        void dnsThrowsUnknownHost() throws UnknownHostException {
            when(dnsResolver.resolve(props.getDnsName()))
                    .thenThrow(new UnknownHostException("NXDOMAIN"));

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            Optional<Set<CacheNode>> result = service.tryDnsFallback();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty when DNS returns empty array")
        void dnsReturnsEmpty() throws UnknownHostException {
            when(dnsResolver.resolve(props.getDnsName())).thenReturn(new InetAddress[]{});

            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            Optional<Set<CacheNode>> result = service.tryDnsFallback();

            assertTrue(result.isEmpty());
        }
    }

    // ──────────────────────── updateHashRing ─────────────────────────────

    @Nested
    @DisplayName("updateHashRing")
    class UpdateHashRingTests {

        @Test
        @DisplayName("adds new nodes to hash ring")
        void addsNewNodes() {
            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            Set<CacheNode> nodes = Set.of(
                    new CacheNode("10.0.1.1", "10.0.1.1", 8080),
                    new CacheNode("10.0.1.2", "10.0.1.2", 8080)
            );

            service.updateHashRing(nodes);

            verify(cacheRouter, times(2)).addNode(any(CacheNode.class));
            verify(cacheRouter, never()).removeNode(any(CacheNode.class));
            assertEquals(2, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("removes departed nodes from hash ring")
        void removesDepartedNodes() {
            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            service.updateHashRing(Set.of(
                    new CacheNode("A", "10.0.1.1", 8080),
                    new CacheNode("B", "10.0.1.2", 8080)
            ));

            service.updateHashRing(Set.of(
                    new CacheNode("B", "10.0.1.2", 8080)
            ));

            verify(cacheRouter).removeNode(argThat(n -> n.getNodeId().equals("A")));
            assertEquals(1, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("handles simultaneous add and remove")
        void addAndRemoveSimultaneously() {
            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            service.updateHashRing(Set.of(
                    new CacheNode("A", "10.0.1.1", 8080),
                    new CacheNode("B", "10.0.1.2", 8080)
            ));

            service.updateHashRing(Set.of(
                    new CacheNode("B", "10.0.1.2", 8080),
                    new CacheNode("C", "10.0.1.3", 8080)
            ));

            verify(cacheRouter).addNode(argThat(n -> n.getNodeId().equals("C")));
            verify(cacheRouter).removeNode(argThat(n -> n.getNodeId().equals("A")));
            assertEquals(2, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("no-op when node set is unchanged")
        void noopWhenUnchanged() {
            service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);

            service.updateHashRing(Set.of(new CacheNode("A", "10.0.1.1", 8080)));
            reset(cacheRouter);

            service.updateHashRing(Set.of(new CacheNode("A", "10.0.1.1", 8080)));

            verify(cacheRouter, never()).addNode(any());
            verify(cacheRouter, never()).removeNode(any());
        }
    }

    // ───────────────────── syncWithCluster (integration) ─────────────────

    @Nested
    @DisplayName("syncWithCluster")
    class SyncWithClusterTests {

        @Test
        @DisplayName("uses peer result when peer returns non-empty membership")
        void usesPeerSyncWhenAvailable() {
            service = spy(new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService));

            Set<CacheNode> peerResult = Set.of(
                    new CacheNode("10.0.1.1", "10.0.1.1", 8080),
                    new CacheNode("10.0.1.2", "10.0.1.2", 8080)
            );
            doReturn(Optional.of(peerResult)).when(service).fetchMembershipFromRandomPeer();

            service.syncWithCluster();

            verify(service, never()).tryDnsFallback();
            assertEquals(2, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("trusts peer gossip even when it returns fewer nodes than current ring")
        void trustsPeerGossipWithFewerNodes() {
            service = spy(new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService));

            service.updateHashRing(Set.of(
                    new CacheNode("A", "10.0.1.1", 8080),
                    new CacheNode("B", "10.0.1.2", 8080),
                    new CacheNode("C", "10.0.1.3", 8080)
            ));

            Set<CacheNode> peerResult = Set.of(
                    new CacheNode("node-A", "10.0.1.1", 8080),
                    new CacheNode("node-B", "10.0.1.2", 8080)
            );
            doReturn(Optional.of(peerResult)).when(service).fetchMembershipFromRandomPeer();

            service.syncWithCluster();

            verify(service, never()).tryDnsFallback();
            assertEquals(2, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("falls back to DNS when peer sync returns empty")
        void fallsToDnsWhenPeerSyncFails() {
            service = spy(new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService));

            doReturn(Optional.empty()).when(service).fetchMembershipFromRandomPeer();
            doReturn(Optional.of(Set.of(
                    new CacheNode("10.0.1.5", "10.0.1.5", 8080)
            ))).when(service).tryDnsFallback();

            service.syncWithCluster();

            verify(service).tryDnsFallback();
            assertEquals(1, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("no update when both peer sync and DNS return empty")
        void noUpdateWhenBothFail() {
            service = spy(new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService));

            doReturn(Optional.empty()).when(service).fetchMembershipFromRandomPeer();
            doReturn(Optional.empty()).when(service).tryDnsFallback();

            service.syncWithCluster();

            assertEquals(0, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("falls back to DNS when peer returns present but empty set")
        void fallsToDnsWhenPeerReturnsEmptySet() {
            service = spy(new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService));

            doReturn(Optional.of(Set.of())).when(service).fetchMembershipFromRandomPeer();
            doReturn(Optional.of(Set.of(
                    new CacheNode("10.0.1.5", "10.0.1.5", 8080)
            ))).when(service).tryDnsFallback();

            service.syncWithCluster();

            verify(service).tryDnsFallback();
            assertEquals(1, service.getActiveNodeCount());
        }

        @Test
        @DisplayName("applies peer result when gossip converged")
        void appliesPeerWhenConverged() {
            service = spy(new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService));

            service.updateHashRing(Set.of(
                    new CacheNode("10.0.1.1", "10.0.1.1", 8080),
                    new CacheNode("10.0.1.2", "10.0.1.2", 8080),
                    new CacheNode("10.0.1.3", "10.0.1.3", 8080)
            ));

            Set<CacheNode> peerResult = Set.of(
                    new CacheNode("node-1", "10.0.1.1", 8080),
                    new CacheNode("node-2", "10.0.1.2", 8080),
                    new CacheNode("node-3", "10.0.1.3", 8080)
            );
            doReturn(Optional.of(peerResult)).when(service).fetchMembershipFromRandomPeer();

            service.syncWithCluster();

            verify(service, never()).tryDnsFallback();
            assertEquals(3, service.getActiveNodeCount());
        }
    }

    // ───────────────────── getActiveNodeCount ────────────────────────────

    @Test
    @DisplayName("getActiveNodeCount returns 0 initially")
    void activeNodeCountZeroInitially() {
        service = new ClusterSyncService(cacheRouter, webClient, props, dnsResolver, metricsService);
        assertEquals(0, service.getActiveNodeCount());
    }
}
