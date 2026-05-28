package com.edgefabric.caching.service;

import com.edgefabric.caching.model.NodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "edgefabric.registry.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class HeartbeatScheduler {
    private final RegistryClient registryClient;
    private final NodeInfo nodeInfo;

    public HeartbeatScheduler(RegistryClient registryClient, NodeInfo selfNodeInfo){
        this.registryClient = registryClient;
        this.nodeInfo = selfNodeInfo;
    }

    @Scheduled(fixedDelayString = "${registry.heartbeat-interval-ms}")
    public void sendHeartbeat(){
        if(nodeInfo == null) return;

        try {
            registryClient.sendHeartbeat(nodeInfo.getCacheNodeId());
        } catch (Exception ex) {
            log.warn("Heartbeat failed", ex);
        }
    }
}
