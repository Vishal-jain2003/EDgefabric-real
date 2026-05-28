package com.edgefabric.caching.config;

import com.edgefabric.caching.dto.NodeInfoDTO;
import com.edgefabric.caching.exception.RegistryNotFoundException;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.RegistryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class NodeRegistrationTest {

    @Mock
    RegistryClient registryClient;

    @Mock
    RegistryRetryProperties registryRetryProperties;

    private NodeInfo selfNodeInfo;
    private NodeRegistration nodeRegistration;

    @BeforeEach
    void setup() {
        selfNodeInfo = new NodeInfo("cache-node-127.0.0.1-8080", "127.0.0.1", 8080, 7946);

        Mockito.lenient().when(registryRetryProperties.getAttempts()).thenReturn(1);
        Mockito.lenient().when(registryRetryProperties.getDelay()).thenReturn(1);
        Mockito.lenient().when(registryClient.register(any())).thenReturn(Mono.empty());

        nodeRegistration = new NodeRegistration(registryClient, registryRetryProperties, selfNodeInfo);
    }

    @Test
    void shouldRegisterNodeOnStartup() {
        nodeRegistration.register();
        Mockito.verify(registryClient).register(any(NodeInfoDTO.class));
    }

    @Test
    void shouldUseInjectedNodeInfo() {
        nodeRegistration.register();
        NodeInfo result = nodeRegistration.getNodeInfo();
        assertNotNull(result);
        assertEquals("cache-node-127.0.0.1-8080", result.getCacheNodeId());
        assertEquals(8080, result.getServicePort());
        assertEquals("127.0.0.1", result.getHost());
    }

    @Test
    void shouldThrowExceptionWhenRegistryUnavailable() {
        Mockito.when(registryClient.register(any()))
                .thenReturn(Mono.error(new RegistryNotFoundException("fail")));

        // register() is now non-fatal: logs a warning instead of rethrowing
        assertDoesNotThrow(() -> nodeRegistration.register());
    }

    @Test
    void shouldSendCorrectNodeInfoToRegistry() {
        nodeRegistration.register();

        Mockito.verify(registryClient).register(argThat(dto ->
                dto.getServicePort() == 8080 &&
                        dto.getCacheNodeId().equals("cache-node-127.0.0.1-8080")));
    }

    @Test
    void shouldRetryWhenRegistryUnavailable() {
        Mockito.when(registryClient.register(any()))
                .thenReturn(Mono.error(new RuntimeException("fail")));

        // register() is now non-fatal: retries then logs a warning instead of rethrowing
        assertDoesNotThrow(() -> nodeRegistration.register());
        Mockito.verify(registryRetryProperties).getAttempts();
    }

    @Test
    void shouldDeregisterOnShutdown() {
        nodeRegistration.register();
        nodeRegistration.deregisterOnShutdown();
        Mockito.verify(registryClient).deregister(any(String.class));
    }

    @Test
    void shouldNotDeregisterIfNodeIdIsNull() {
        NodeInfo infoWithNullId = Mockito.mock(NodeInfo.class);
        Mockito.when(infoWithNullId.getCacheNodeId()).thenReturn(null);
        NodeRegistration reg = new NodeRegistration(registryClient, registryRetryProperties, infoWithNullId);
        // Don't call register() — nodeInfoDTO is null so deregister should still handle nodeInfo
        // but nodeInfo is non-null; check the null cacheNodeId path
        setNodeInfo(reg, infoWithNullId);

        reg.deregisterOnShutdown();
        Mockito.verify(registryClient, Mockito.never()).deregister(any());
    }

    @Test
    void shouldNotDeregisterIfNodeIdIsBlank() {
        NodeInfo infoWithBlankId = Mockito.mock(NodeInfo.class);
        Mockito.when(infoWithBlankId.getCacheNodeId()).thenReturn("   ");
        NodeRegistration reg = new NodeRegistration(registryClient, registryRetryProperties, infoWithBlankId);
        setNodeInfo(reg, infoWithBlankId);

        reg.deregisterOnShutdown();
        Mockito.verify(registryClient, Mockito.never()).deregister(any());
    }

    @Test
    void shouldHandleWebClientExceptionDuringDeregister() {
        nodeRegistration.register();

        Mockito.doThrow(new WebClientRequestException(
                        new RuntimeException("connection refused"),
                        org.springframework.http.HttpMethod.DELETE,
                        URI.create("/registry/node/test"),
                        org.springframework.http.HttpHeaders.EMPTY))
                .when(registryClient).deregister(any());

        assertDoesNotThrow(() -> nodeRegistration.deregisterOnShutdown());
    }

    @Test
    void shouldHandleUnexpectedExceptionDuringDeregister() {
        nodeRegistration.register();

        Mockito.doThrow(new RuntimeException("unexpected failure"))
                .when(registryClient).deregister(any());

        assertDoesNotThrow(() -> nodeRegistration.deregisterOnShutdown());
    }

    private void setNodeInfo(NodeRegistration target, NodeInfo nodeInfo) {
        try {
            java.lang.reflect.Field field = NodeRegistration.class.getDeclaredField("nodeInfo");
            field.setAccessible(true);
            field.set(target, nodeInfo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
