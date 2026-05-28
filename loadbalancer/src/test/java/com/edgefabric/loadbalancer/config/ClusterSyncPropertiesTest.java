package com.edgefabric.loadbalancer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterSyncPropertiesTest {

    @Test
    void defaultValues() {
        ClusterSyncProperties props = new ClusterSyncProperties();
        assertEquals("cache-nodes.cache-cluster.internal", props.getDnsName());
        assertEquals(8082, props.getNodePort());
        assertEquals(5000, props.getSyncIntervalMs());
        assertEquals(5, props.getBootstrapMaxRetries());
        assertEquals(2000, props.getBootstrapRetryDelayMs());
    }

    @Test
    void settersAndGetters() {
        ClusterSyncProperties props = new ClusterSyncProperties();
        props.setDnsName("custom.dns.name");
        props.setNodePort(9090);
        props.setMembershipPath("/custom/path");
        props.setSyncIntervalMs(10000);
        props.setBootstrapMaxRetries(10);
        props.setBootstrapRetryDelayMs(500);

        assertEquals("custom.dns.name", props.getDnsName());
        assertEquals(9090, props.getNodePort());
        assertEquals("/custom/path", props.getMembershipPath());
        assertEquals(10000, props.getSyncIntervalMs());
        assertEquals(10, props.getBootstrapMaxRetries());
        assertEquals(500, props.getBootstrapRetryDelayMs());
    }
}

