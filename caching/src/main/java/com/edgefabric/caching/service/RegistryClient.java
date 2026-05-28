package com.edgefabric.caching.service;

import com.edgefabric.caching.dto.NodeInfoDTO;
import com.edgefabric.caching.exception.RegistryNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "edgefabric.registry.enabled", havingValue = "true", matchIfMissing = false)
@AllArgsConstructor
@Slf4j
public class RegistryClient {

    private final WebClient webClient;

    public Mono<Void> register(NodeInfoDTO nodeInfoDTO){
        return webClient.post()
                .uri("/registry/node")
                .bodyValue(nodeInfoDTO)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        res -> Mono.error(new RegistryNotFoundException(
                                "Registry returned error response")))
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .then()
                .doOnSuccess(v ->
                        log.info("Node registered: {}", nodeInfoDTO.getCacheNodeId()))
                .onErrorMap(ex ->
                        new RegistryNotFoundException("Registry not reachable", ex));
    }

    public void sendHeartbeat(String nodeId){
        webClient.post()
                .uri("/registry/node/heartbeat")
                .bodyValue(nodeId)
                .retrieve()
                .toBodilessEntity()
                .doOnError(err ->
                        log.warn("Heartbeat failed for {}", nodeId))
                .subscribe();
    }



    public void deregister(String nodeId) {

        webClient.delete()
                .uri("/registry/node/{nodeId}", nodeId)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        res -> Mono.error(new RegistryNotFoundException(
                                "Registry returned error response during deregistration")))
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .doOnSuccess(res ->
                        log.info("Node deregistered: {}", nodeId))
                .onErrorMap(ex -> !(ex instanceof RegistryNotFoundException),
                        ex -> new RegistryNotFoundException("Registry not reachable", ex))
                .block();
    }

}
