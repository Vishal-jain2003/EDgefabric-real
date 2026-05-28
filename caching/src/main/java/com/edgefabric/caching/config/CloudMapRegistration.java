package com.edgefabric.caching.config;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.CloudMapClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Automatically registers this cache node with AWS Cloud Map on startup
 * and deregisters on graceful shutdown.
 *
 * <p>Activated only when {@code cloudmap.enabled=true} (production EC2).
 * In Docker Compose / local dev the property defaults to {@code false},
 * so this bean is never created.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@code @PostConstruct} → registers the node's IP + port in Cloud Map
 *       so that the cluster DNS hostname resolves to this node.</li>
 *   <li>{@code @PreDestroy} → deregisters the node so DNS stops returning
 *       a dead IP on graceful shutdown / rolling deploy.</li>
 * </ol>
 *
 * <p>Registration fires <em>before</em> {@code ApplicationReadyEvent},
 * guaranteeing the node is discoverable via DNS when
 * {@link ClusterJoinListener} triggers the cluster join.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "cloudmap.enabled", havingValue = "true")
@Slf4j
public class CloudMapRegistration {

    private final CloudMapClient cloudMapClient;
    private final CloudMapProperties properties;
    private final NodeInfo nodeInfo;

    /** Tracks whether registration succeeded, so we only deregister if needed. */
    private volatile boolean registered = false;

    public CloudMapRegistration(CloudMapClient cloudMapClient,
                                CloudMapProperties properties,
                                NodeInfo selfNodeInfo) {
        this.cloudMapClient = cloudMapClient;
        this.properties = properties;
        this.nodeInfo = selfNodeInfo;
    }

    @PostConstruct
    public void register() {
        String serviceId = properties.getServiceId();
        String instanceId = nodeInfo.getCacheNodeId();

        if (serviceId == null || serviceId.isBlank()) {
            log.warn("cloudmap.service-id is not set — skipping Cloud Map registration");
            return;
        }

        try {
            cloudMapClient.registerInstance(
                    serviceId,
                    instanceId,
                    nodeInfo.getHost(),
                    nodeInfo.getServicePort());
            registered = true;
            log.info("Node {} registered in Cloud Map (service {})",
                    instanceId, serviceId);
        } catch (Exception ex) {
            log.error("Cloud Map registration failed — node will start but may not be " +
                    "discoverable via DNS until manually registered: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void deregister() {
        if (!registered) {
            log.info("Skipping Cloud Map deregistration — node was never registered");
            return;
        }

        String serviceId = properties.getServiceId();
        String instanceId = nodeInfo.getCacheNodeId();

        // Reset the flag BEFORE the network call so that repeated @PreDestroy
        // invocations (e.g. overlapping shutdown hooks or test-teardown re-entry)
        // are no-ops and never cause a second attempt on an already-failed or
        // already-closed client.
        registered = false;

        try {
            cloudMapClient.deregisterInstance(serviceId, instanceId);
            log.info("Node {} deregistered from Cloud Map (service {})",
                    instanceId, serviceId);
        } catch (Exception ex) {
            log.error("Cloud Map deregistration failed for node {}: {}",
                    instanceId, ex.getMessage(), ex);
        }
    }
}

