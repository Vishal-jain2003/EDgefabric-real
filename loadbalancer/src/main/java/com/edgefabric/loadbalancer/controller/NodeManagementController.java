package com.edgefabric.loadbalancer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Node Management", description = "Proxy drain operations to cache nodes")
@Slf4j
@RestController
@RequestMapping("/api/v1/nodes")
@RequiredArgsConstructor
public class NodeManagementController {

    private final WebClient webClient;

    @Operation(summary = "Start draining a cache node",
            description = "Proxies drain request to the specified cache node")
    @PostMapping("/drain")
    public Mono<ResponseEntity<Map<String, String>>> startDrain(
            @RequestParam String host,
            @RequestParam int port) {

        String url = "http://" + host + ":" + port + "/internal/cluster/drain";
        log.info("Proxying drain request to: {}", url);

        return webClient.post()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> ResponseEntity.ok((Map<String, String>) body))
                .onErrorResume(e -> {
                    log.error("Failed to drain node {}:{}", host, port, e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", "Failed to drain node: " + e.getMessage())));
                });
    }

    @Operation(summary = "Cancel drain on a cache node",
            description = "Proxies cancel drain request to the specified cache node")
    @DeleteMapping("/drain")
    public Mono<ResponseEntity<Map<String, String>>> cancelDrain(
            @RequestParam String host,
            @RequestParam int port) {

        String url = "http://" + host + ":" + port + "/internal/cluster/drain";
        log.info("Proxying cancel drain request to: {}", url);

        return webClient.delete()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> ResponseEntity.ok((Map<String, String>) body))
                .onErrorResume(e -> {
                    log.error("Failed to cancel drain on node {}:{}", host, port, e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", "Failed to cancel drain: " + e.getMessage())));
                });
    }
}
