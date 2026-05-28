package com.edgefabric.loadbalancer.config;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.hashing.core.ConsistentHashRing;
import com.edgefabric.hashing.core.HashProvider;
import com.edgefabric.hashing.core.MurmurHashProvider;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.CacheRouter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class LoadBalancerConfigTest {

    private final LoadBalancerConfig config = new LoadBalancerConfig();

    @Test
    void quorumExecutor_createsVirtualThreadExecutor() {
        ExecutorService executor = config.quorumExecutor();
        assertNotNull(executor);
        assertFalse(executor.isShutdown());
        executor.close();
    }

    @Test
    void hashRingProperties_createsInstance() {
        HashRingProperties props = config.hashRingProperties();
        assertNotNull(props);
    }

    @Test
    void hashProvider_createsProvider() {
        HashRingProperties props = new HashRingProperties();
        props.setHashAlgorithm("murmur");
        HashProvider provider = config.hashProvider(props);
        assertNotNull(provider);
    }

    @Test
    void consistentHashRing_createsRing() {
        HashProvider provider = new MurmurHashProvider();
        HashRingProperties props = new HashRingProperties();
        props.setVirtualNodes(100);
        ConsistentHashRing<CacheNode> ring = config.consistentHashRing(provider, props);
        assertNotNull(ring);
    }

    @Test
    void cacheRouter_createsRouter() {
        HashProvider provider = new MurmurHashProvider();
        ConsistentHashRing<CacheNode> ring = new ConsistentHashRing<>(provider, 100);
        CacheRouter router = config.cacheRouter(ring);
        assertNotNull(router);
    }
}

