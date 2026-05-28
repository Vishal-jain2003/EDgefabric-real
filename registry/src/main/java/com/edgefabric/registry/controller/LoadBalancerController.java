package com.edgefabric.registry.controller;

import com.edgefabric.registry.dto.RegistryResponse;
import com.edgefabric.registry.service.RegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/registry/active")
public class LoadBalancerController {

    private final RegistryService registryService;

    public LoadBalancerController(RegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping("/nodes")
    public ResponseEntity<RegistryResponse> getActiveNodes() {
        RegistryResponse response = registryService.getRegistryState();
        return ResponseEntity.ok(response);
    }
}
