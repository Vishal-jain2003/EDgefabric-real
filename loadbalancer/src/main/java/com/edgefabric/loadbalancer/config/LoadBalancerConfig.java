

package com.edgefabric.loadbalancer.config;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.hashing.core.ConsistentHashRing;
import com.edgefabric.hashing.core.HashProvider;
import com.edgefabric.hashing.core.HashProviderFactory;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.service.CacheRouter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class LoadBalancerConfig {

    /**
     * Shared executor used for both quorum fan-out and async read-repair writes.
     * Uses virtual threads (Java 21+) for efficient concurrency without fixed pool limits.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService quorumExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /*
     * @ConfigurationProperties(prefix = "ring") tells Spring:
     * read application.yml values under "ring:" and call the setters.
     *
     * So ring.virtual-nodes=10 → calls setVirtualNodes(10)
     *    ring.hash-algorithm=xxhash → calls setHashAlgorithm("xxhash")
     */
    @Bean
    @ConfigurationProperties(prefix = "ring")
    public HashRingProperties hashRingProperties() {
        return new HashRingProperties();
    }

    @Bean
    public HashProvider hashProvider(HashRingProperties properties) {
        return HashProviderFactory.create(properties.getHashAlgorithm());
    }

    @Bean
    public ConsistentHashRing<CacheNode> consistentHashRing(
            HashProvider hashProvider,
            HashRingProperties properties) {
        return new ConsistentHashRing<>(hashProvider, properties.getVirtualNodes());
    }

    @Bean
    public CacheRouter cacheRouter(ConsistentHashRing<CacheNode> ring) {
        return new CacheRouter(ring);
    }
}