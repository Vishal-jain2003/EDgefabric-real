package com.edgefabric.registry.util.registry_util;

import com.edgefabric.registry.util.node_util.CacheNodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InMemoryRegistryState implements RegistryState{

    private final ConcurrentMap<String, CacheNodeInfo> activeNodes;
    private final AtomicLong registryVersion;

    @Value("${registry.ttl}")
    private long ttl;
    private static final Logger logger = LoggerFactory.getLogger(InMemoryRegistryState.class);

    public InMemoryRegistryState(ConcurrentMap<String, CacheNodeInfo> activeNodes, AtomicLong registryVersion) {
        this.activeNodes = activeNodes;
        this.registryVersion = registryVersion;
    }

    @Override
    public void save(String cacheNodeId, CacheNodeInfo cacheNodeInfo) {
        activeNodes.put(cacheNodeId, cacheNodeInfo);
        registryVersion.incrementAndGet();
    }




    @Override
    public boolean exists(String cacheNodeId) {
        return activeNodes.containsKey(cacheNodeId);
    }

    @Override
    public long getVersion() {
        return registryVersion.get();
    }

    public Map<String, CacheNodeInfo> getActiveNodes() {
        return activeNodes;
    }


    @Override
    public void removeIfPresent(String cacheNodeId) {
        CacheNodeInfo removedNode = activeNodes.remove(cacheNodeId);
        if (removedNode != null) {
            registryVersion.incrementAndGet();

        }
    }

    @Override
    public void updateVersion() {
        registryVersion.incrementAndGet();
    }

    @Override
    public CacheNodeInfo getCache(String cacheNodeId) {
               return activeNodes.get(cacheNodeId);
    }

    @Scheduled(fixedDelayString = "${registry.eviction.delay}")
    public void evictExpiredNodes() {
        long currentTime = System.currentTimeMillis();
        activeNodes.entrySet().removeIf(entry -> {
            CacheNodeInfo node = entry.getValue();
            long lastHeartbeat = node.getLastHeartBeatTime();
            boolean isExpired = currentTime - lastHeartbeat > ttl;

            if (isExpired) {
                updateVersion();
                logger.warn("Node {} expired due to missing heartbeat", entry.getKey());

            }
            return isExpired;
        });
    }
}
