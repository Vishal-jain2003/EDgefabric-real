package com.edgefabric.registry.controller;

import com.edgefabric.registry.dto.RegisterRequest;
import com.edgefabric.registry.dto.SuccessResponse;
import com.edgefabric.registry.service.RegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/registry/node")
public class CacheNodeController {
    private final RegistryService registryService;

    public CacheNodeController(RegistryService registryService) {
        this.registryService = registryService;
    }

    @PostMapping
    public ResponseEntity<SuccessResponse> registerNode(@Valid @RequestBody RegisterRequest request) {
        registryService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new SuccessResponse("Node registered successfully"));
    }

    @DeleteMapping("/{cacheNodeId}")
    public ResponseEntity<SuccessResponse> deregisterNode(
            @PathVariable @NotBlank(message = "cacheNodeId must not be blank") String cacheNodeId) {

        registryService.deregister(cacheNodeId);

        return ResponseEntity.ok(new SuccessResponse("Node de-registered successfully"));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<SuccessResponse> handleHeartBeat(
            @RequestBody String cacheNodeId) {

        cacheNodeId = cacheNodeId.replace("\"", "");

        registryService.processHeartbeat(cacheNodeId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(new SuccessResponse("Node Heartbeat updated successfully"));
    }
}
