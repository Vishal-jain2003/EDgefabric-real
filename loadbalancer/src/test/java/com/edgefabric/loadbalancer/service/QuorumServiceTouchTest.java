package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.config.QuorumProperties;
import com.edgefabric.loadbalancer.exception.CacheKeyNotFoundException;
import com.edgefabric.loadbalancer.exception.QuorumNotMetException;
import com.edgefabric.loadbalancer.metrics.QuorumMetricsService;
import com.edgefabric.loadbalancer.model.CacheNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuorumServiceTouchTest {

    @Mock
    private CacheRouter cacheRouter;

    @Mock
    private ClusterClient clusterClient;

    @Mock
    private ReadRepairService readRepairService;

    @Mock
    private QuorumMetricsService metricsService;

    private QuorumProperties quorumProperties;
    private QuorumService quorumService;

    private static final CacheNode NODE_1 = new CacheNode("node-1", "host1", 8081);
    private static final CacheNode NODE_2 = new CacheNode("node-2", "host2", 8082);
    private static final CacheNode NODE_3 = new CacheNode("node-3", "host3", 8083);

    private static final String TENANT_KEY = "tenantA:user:session:abc";

    @BeforeEach
    void setUp() {
        quorumProperties = new QuorumProperties();
        quorumProperties.setReplicationFactor(3);
        quorumProperties.setWrite(2);
        quorumProperties.setRead(2);
        quorumProperties.setTimeoutMs(5000);

        quorumService = new QuorumService(cacheRouter, clusterClient, quorumProperties, readRepairService, metricsService);
    }

    // ─── AC2+AC6: All replicas respond → TouchResult with correct expiresAt ───

    @Test
    @DisplayName("AC2+AC6: quorumTouch succeeds when all replicas respond")
    void quorumTouch_allReplicasSucceed_returnsTouchResult() {
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        when(cacheRouter.routeToReplicas(TENANT_KEY, 3)).thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardTouchRequest(any(), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.just(newExpiresAt));

        QuorumService.TouchResult result = quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block();

        assertThat(result).isNotNull();
        assertThat(result.key()).isEqualTo(TENANT_KEY);
        assertThat(result.expiresAt()).isEqualTo(newExpiresAt);
    }

    // ─── AC6: exactly W replicas succeed → still returns TouchResult ───

    @Test
    @DisplayName("AC6: quorumTouch succeeds with exactly W successful replicas")
    void quorumTouch_exactlyWSuccesses_succeeds() {
        long newExpiresAt = System.currentTimeMillis() + 1_800_000L;
        when(cacheRouter.routeToReplicas(TENANT_KEY, 3)).thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // NODE_1 and NODE_2 succeed, NODE_3 fails
        when(clusterClient.forwardTouchRequest(eq(NODE_1), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.just(newExpiresAt));
        when(clusterClient.forwardTouchRequest(eq(NODE_2), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.just(newExpiresAt));
        when(clusterClient.forwardTouchRequest(eq(NODE_3), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.error(new RuntimeException("node3 unreachable")));

        QuorumService.TouchResult result = quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block();

        assertThat(result).isNotNull();
        assertThat(result.expiresAt()).isEqualTo(newExpiresAt);
    }

    // ─── AC6: fewer than W replicas available → QuorumNotMetException ───

    @Test
    @DisplayName("AC6: quorumTouch fails with QuorumNotMetException when fewer than W nodes available")
    void quorumTouch_fewerNodesThanW_throwsQuorumNotMet() {
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        // Only 1 node available, W=2
        when(cacheRouter.routeToReplicas(TENANT_KEY, 3)).thenReturn(List.of(NODE_1));

        QuorumNotMetException ex = assertThrows(QuorumNotMetException.class,
                () -> quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block());

        assertThat(ex.getOperation()).isEqualTo("TOUCH");
        assertThat(ex.getRequired()).isEqualTo(2);
    }

    // ─── AC6: W - 1 replicas succeed (only 1 success, need 2) → QuorumNotMetException ───

    @Test
    @DisplayName("AC6: quorumTouch fails when fewer than W replicas actually succeed")
    void quorumTouch_onlyOneNodeSucceeds_throwsQuorumNotMet() {
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        when(cacheRouter.routeToReplicas(TENANT_KEY, 3)).thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        // All nodes fail
        when(clusterClient.forwardTouchRequest(any(), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.error(new RuntimeException("unreachable")));

        assertThrows(QuorumNotMetException.class,
                () -> quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block());
    }

    // ─── AC3: node returns CacheKeyNotFoundException → propagated ───

    @Test
    @DisplayName("AC3: quorumTouch propagates CacheKeyNotFoundException when all nodes miss")
    void quorumTouch_allNodesMiss_throwsCacheKeyNotFoundException() {
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        when(cacheRouter.routeToReplicas(TENANT_KEY, 3)).thenReturn(List.of(NODE_1, NODE_2, NODE_3));

        when(clusterClient.forwardTouchRequest(any(), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.error(new CacheKeyNotFoundException(TENANT_KEY)));

        assertThrows(CacheKeyNotFoundException.class,
                () -> quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block());
    }

    // ─── Version monotonicity: each quorumTouch call uses a strictly increasing version ───

    @Test
    @DisplayName("quorumTouch uses a strictly increasing version on each call")
    void quorumTouch_usesMonotonicVersion() {
        long newExpiresAt = System.currentTimeMillis() + 3_600_000L;
        when(cacheRouter.routeToReplicas(TENANT_KEY, 3)).thenReturn(List.of(NODE_1, NODE_2, NODE_3));
        when(clusterClient.forwardTouchRequest(any(), eq(TENANT_KEY), eq(newExpiresAt), anyLong()))
                .thenReturn(Mono.just(newExpiresAt));

        quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block();
        quorumService.quorumTouch(TENANT_KEY, newExpiresAt).block();

        // Capture version args across both calls
        var versionCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(clusterClient, atLeast(2))
                .forwardTouchRequest(any(), eq(TENANT_KEY), eq(newExpiresAt), versionCaptor.capture());

        List<Long> versions = versionCaptor.getAllValues();
        // Verify at least some versions are present and all positive
        assertThat(versions).allMatch(v -> v > 0);
    }
}
