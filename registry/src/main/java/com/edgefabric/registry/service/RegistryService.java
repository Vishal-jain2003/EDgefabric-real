package com.edgefabric.registry.service;

import com.edgefabric.registry.dto.RegisterRequest;
import com.edgefabric.registry.dto.RegistryResponse;

public interface RegistryService {
    void register(RegisterRequest request);
    RegistryResponse getRegistryState();
     void processHeartbeat(String cacheNodeId);
    void deregister(String cacheNodeId);
}
