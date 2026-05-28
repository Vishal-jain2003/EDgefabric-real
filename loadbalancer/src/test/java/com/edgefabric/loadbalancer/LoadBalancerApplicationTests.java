package com.edgefabric.loadbalancer;

import com.edgefabric.loadbalancer.service.ClusterSyncService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;


@SpringBootTest
@EnableAutoConfiguration()
class LoadBalancerApplicationTests {

    @MockitoBean
    private ClusterSyncService clusterSyncService;

    @Test
    void contextLoads() {
        // Assert that the context starts correctly
        assertNotNull(clusterSyncService);
    }
}