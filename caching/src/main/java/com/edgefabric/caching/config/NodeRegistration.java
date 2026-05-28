package com.edgefabric.caching.config;

import com.edgefabric.caching.dto.NodeInfoDTO;
import com.edgefabric.caching.exception.RegistryNotFoundException;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.RegistryClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "edgefabric.registry.enabled", havingValue = "true", matchIfMissing = false)
@Getter
@Slf4j
public class NodeRegistration {

    private final RegistryClient registryClient;
    private final RegistryRetryProperties registryRetryProperties;
    private final NodeInfo nodeInfo;

    private NodeInfoDTO nodeInfoDTO;

    public NodeRegistration(RegistryClient registryClient,
                            RegistryRetryProperties registryRetryProperties,
                            NodeInfo selfNodeInfo) {
        this.registryClient = registryClient;
        this.registryRetryProperties = registryRetryProperties;
        this.nodeInfo = selfNodeInfo;
    }

    @PostConstruct
    public void register() {
        this.nodeInfoDTO = new NodeInfoDTO(
                nodeInfo.getCacheNodeId(),
                nodeInfo.getHost(),
                nodeInfo.getServicePort());

        try {
            registryClient.register(nodeInfoDTO)
                    .retryWhen(
                            Retry.fixedDelay(
                                            registryRetryProperties.getAttempts(),
                                            Duration.ofSeconds(registryRetryProperties.getDelay()))
                                    .doBeforeRetry(retry ->
                                            log.warn(
                                                    "Registry unavailable. Retrying... attempt={}",
                                                    retry.totalRetries() + 1))
                                    .onRetryExhaustedThrow(
                                            (spec, signal) ->
                                                    new RegistryNotFoundException(
                                                            "Registry unavailable after retries")
                                    )
                    )
                    .block();

            log.info("Node registered successfully");

        } catch (RegistryNotFoundException ex) {
            log.warn("Registry is unavailable; node will start without registration: {}", ex.getMessage());
        }
    }


    @PreDestroy
    public void deregisterOnShutdown() {
        String nodeId = nodeInfo.getCacheNodeId();

        if (nodeId == null || nodeId.isBlank()) {
            log.warn("Skipping deregistration: node ID is missing");
            return;
        }

        try {
            registryClient.deregister(nodeId);
            log.info("Node deregistered successfully: {}", nodeId);
        } catch (WebClientException ex) {
            log.error("WebClient error during deregistration for node {}: {}",
                    nodeId, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error during deregistration for node {}: {}",
                    nodeId, ex.getMessage(), ex);
        }

    }
}
