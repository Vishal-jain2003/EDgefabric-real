package com.edgefabric.registry.config;

import com.edgefabric.registry.util.node_util.CacheNodeInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class RegistryConfig {
    @Bean
    public ConcurrentMap<String, CacheNodeInfo> cacheNodeInfoMap() {
        return new ConcurrentHashMap<>();
    }
    @Bean
    public AtomicLong registryVersion() {
        return new AtomicLong(0);
    }
}
