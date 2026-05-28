package com.edgefabric.registry.util.node_util;

import lombok.Getter;

@Getter
public class CacheNodeInfo {
    private final String host;
    private final int port;
    private volatile long lastHeartBeatTime;

    public CacheNodeInfo(String host, int port) {
        this.host = host;
        this.port = port;
        this.lastHeartBeatTime = System.currentTimeMillis();
    }

    public void setLastHeartBeatTime(long lastHeartBeatTime) {

        this.lastHeartBeatTime = lastHeartBeatTime;
    }
}
