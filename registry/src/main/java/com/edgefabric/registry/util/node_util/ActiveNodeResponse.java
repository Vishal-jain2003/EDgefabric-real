package com.edgefabric.registry.util.node_util;

import lombok.Getter;

@Getter
public class ActiveNodeResponse {

    private final String cacheNodeId;
    private final  String host;
    private final int port;

    public ActiveNodeResponse(String cacheNodeId, String host, int port) {
        this.cacheNodeId = cacheNodeId;
        this.host = host;
        this.port = port;
    }
}