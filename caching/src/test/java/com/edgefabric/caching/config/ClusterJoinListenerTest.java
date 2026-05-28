package com.edgefabric.caching.config;

import com.edgefabric.caching.service.ClusterJoinService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ClusterJoinListenerTest {

    @Mock
    private ClusterJoinService clusterJoinService;

    @InjectMocks
    private ClusterJoinListener clusterJoinListener;

    @Test
    void shouldTriggerClusterJoinOnApplicationReady() {
        clusterJoinListener.onApplicationReady();
        verify(clusterJoinService).joinCluster();
    }
}

