package com.edgefabric.caching.config;

import com.edgefabric.caching.service.ClusterJoinService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@Slf4j
public class ClusterJoinListener {

    private final ClusterJoinService clusterJoinService;

    public ClusterJoinListener(ClusterJoinService clusterJoinService) {
        this.clusterJoinService = clusterJoinService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready — triggering DNS-based cluster join");
        clusterJoinService.joinCluster();
    }
}

