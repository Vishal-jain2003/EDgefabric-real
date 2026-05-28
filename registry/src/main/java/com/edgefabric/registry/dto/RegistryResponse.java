package com.edgefabric.registry.dto;

import com.edgefabric.registry.util.node_util.ActiveNodeResponse;
import lombok.Getter;

import java.util.List;

@Getter
public class RegistryResponse {
    private final long registryVersion;
    private final List<ActiveNodeResponse> activeNodes;

    public RegistryResponse(long version, List<ActiveNodeResponse> activeNodes) {
        this.registryVersion = version;
        this.activeNodes = activeNodes;
    }

}
