package com.edgefabric.caching.service;

import com.edgefabric.caching.config.ClusterJoinProperties;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.resolver.DnsNodeDiscoveryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class ClusterJoinServiceTest {

    @Mock private DnsNodeDiscoveryResolver resolver;
    @Mock private MembershipList membershipList;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private ClusterJoinProperties joinProperties;

    @InjectMocks
    private ClusterJoinService service;

    private NodeInfo self;

    private NodeInfo createNode(String id, String host) {
        return new NodeInfo(id, host, 8082, 9092);
    }

    @BeforeEach
    void setup() {
        self = createNode("self", "127.0.0.1");
        lenient().when(membershipList.getSelf()).thenReturn(self);

        when(joinProperties.getJitterMaxMs()).thenReturn(0L);
        when(joinProperties.getDnsReconcileTimeoutMs()).thenReturn(0L);
        lenient().when(joinProperties.getDnsReconcileIntervalMs()).thenReturn(15_000L);
    }

    // ── Initial join tests ─────────────────────────────────────────────────────

    @Test
    void shouldReturnWhenNoIps() {
        when(resolver.resolve()).thenReturn(List.of());

        service.joinCluster();

        verifyNoInteractions(restClient);
    }

    @Test
    void shouldSkipSelfIp() {
        when(resolver.resolve()).thenReturn(List.of("127.0.0.1"));

        service.joinCluster();

        verifyNoInteractions(restClient);
    }

    @Test
    void shouldJoinSuccessfully() {
        when(resolver.resolve()).thenReturn(List.of("192.168.1.1"));
        List<NodeInfo> response = List.of(createNode("n2", "192.168.1.2"));
        mockRestClient(response);

        service.joinCluster();

        verify(membershipList, times(1)).merge(any());
    }

    @Test
    void shouldNotMergeWhenResponseEmpty() {
        when(resolver.resolve()).thenReturn(List.of("192.168.1.1"));
        mockRestClient(List.of());

        service.joinCluster();

        verify(membershipList, never()).merge(any());
    }

    @Test
    void shouldHandleNetworkException() {
        when(resolver.resolve()).thenReturn(List.of("192.168.1.1"));
        when(restClient.post()).thenThrow(
                new ResourceAccessException("connection refused"));

        service.joinCluster();

        verify(membershipList, never()).merge(any());
    }

    @Test
    void shouldHandleHttpErrorException() {
        when(resolver.resolve()).thenReturn(List.of("192.168.1.1"));
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(NodeInfo.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(
                new RestClientResponseException("Bad Request", HttpStatusCode.valueOf(400), "Bad Request", null, null, null));

        service.joinCluster();

        verify(membershipList, never()).merge(any());
    }

    @Test
    void shouldNotRetryDnsOnFailure() {
        when(resolver.resolve()).thenReturn(List.of());

        service.joinCluster();

        verify(resolver, times(1)).resolve();
        verifyNoInteractions(restClient);
    }

    // ── Reconciliation thread tests ────────────────────────────────────────────

    @Test
    void reconciliationStopsWithoutJoinWhenAllDnsPeersAlreadyKnown() throws Exception {
        NodeInfo peer = createNode("peer-1", "10.0.0.1");

        when(joinProperties.getDnsReconcileTimeoutMs()).thenReturn(5_000L);
        when(joinProperties.getDnsReconcileIntervalMs()).thenReturn(20L);

        when(membershipList.getAliveNodes()).thenReturn(List.of(peer));

        when(resolver.resolve())
                .thenReturn(List.of())
                .thenReturn(List.of("10.0.0.1"));

        service.joinCluster();

        verify(resolver, timeout(3_000).atLeast(2)).resolve();

        verifyNoInteractions(restClient);
    }

    @Test
    void reconciliationJoinsViaMissingDnsPeers() throws Exception {
        when(joinProperties.getDnsReconcileTimeoutMs()).thenReturn(5_000L);
        when(joinProperties.getDnsReconcileIntervalMs()).thenReturn(20L);

        when(resolver.resolve())
                .thenReturn(List.of())
                .thenReturn(List.of("10.0.0.2"));

        when(membershipList.getAliveNodes()).thenReturn(List.of());

        mockRestClient(List.of(createNode("peer-2", "10.0.0.2")));

        service.joinCluster();

        verify(restClient, timeout(3_000).atLeastOnce()).post();
        verify(membershipList, timeout(3_000).atLeastOnce()).merge(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockRestClient(List<NodeInfo> response) {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(NodeInfo.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class)))
                .thenReturn(response);
    }
}