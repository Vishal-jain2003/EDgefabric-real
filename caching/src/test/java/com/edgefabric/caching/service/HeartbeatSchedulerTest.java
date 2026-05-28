package com.edgefabric.caching.service;

import com.edgefabric.caching.model.NodeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class HeartbeatSchedulerTest {
    @Mock
    RegistryClient registryClient;

    @Spy
    NodeInfo nodeInfo = new NodeInfo("node1", "127.0.0.1", 8080, 7946);

    @InjectMocks
    HeartbeatScheduler heartbeatScheduler;

    @Test
    void shouldSendHeartbeat() {
        heartbeatScheduler.sendHeartbeat();

        Mockito.verify(registryClient)
                .sendHeartbeat("node1");
    }

    @Test
    void shouldNotSendHeartbeatIfNodeInfoNull() {
        HeartbeatScheduler schedulerWithNull = new HeartbeatScheduler(registryClient, null);
        schedulerWithNull.sendHeartbeat();

        Mockito.verify(registryClient, never())
                .sendHeartbeat(any());
    }

    @Test
    void shouldHandleExceptionGracefully() {
        doThrow(new RuntimeException())
                .when(registryClient)
                .sendHeartbeat(any());

        assertDoesNotThrow(
                () -> heartbeatScheduler.sendHeartbeat()
        );
    }
}
