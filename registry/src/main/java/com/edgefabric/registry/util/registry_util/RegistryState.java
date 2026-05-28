package com.edgefabric.registry.util.registry_util;

import com.edgefabric.registry.util.node_util.CacheNodeInfo;

import java.util.Map;

public interface RegistryState{

    void save(String cacheNodeId, CacheNodeInfo cacheNodeInfo);
    boolean exists(String cacheNodeId);
    long getVersion();
    Map<String, CacheNodeInfo> getActiveNodes();
    CacheNodeInfo getCache(String cacheNodeId);
    void removeIfPresent(String cacheNodeId);
    void updateVersion();
    public void evictExpiredNodes();
}