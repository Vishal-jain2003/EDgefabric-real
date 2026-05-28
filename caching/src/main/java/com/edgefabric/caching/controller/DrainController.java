package com.edgefabric.caching.controller;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.service.DrainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Node Drain", description = "Gracefully drain a cache node for maintenance")
@RestController
@RequestMapping("/internal/cluster")
@RequiredArgsConstructor
public class DrainController {

    private final DrainService drainService;
    private final MembershipList membershipList;

    @Operation(summary = "Start draining this node",
            description = "Transitions the node to DRAINING state. The node stops accepting writes, " +
                    "continues serving reads, and gossip propagates the state to peers.")
    @ApiResponse(responseCode = "200", description = "Node is now draining",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @ApiResponse(responseCode = "409", description = "Node is not in ALIVE state (already draining, suspect, or dead)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @PostMapping("/drain")
    public ResponseEntity<Map<String, String>> startDrain() {
        String nodeId = membershipList.getSelf().getCacheNodeId();
        if (drainService.startDrain()) {
            return ResponseEntity.ok(Map.of("status", "DRAINING", "nodeId", nodeId));
        }
        return ResponseEntity.status(409).body(
                Map.of("error", "Node is not in ALIVE state", "nodeId", nodeId));
    }

    @Operation(summary = "Cancel drain and restore this node",
            description = "Transitions the node from DRAINING back to ALIVE. " +
                    "Writes are accepted again and the LB will resume routing to this node.")
    @ApiResponse(responseCode = "200", description = "Node restored to ALIVE",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @ApiResponse(responseCode = "409", description = "Node is not in DRAINING state",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @DeleteMapping("/drain")
    public ResponseEntity<Map<String, String>> cancelDrain() {
        String nodeId = membershipList.getSelf().getCacheNodeId();
        if (drainService.cancelDrain()) {
            return ResponseEntity.ok(Map.of("status", "ALIVE", "nodeId", nodeId));
        }
        return ResponseEntity.status(409).body(
                Map.of("error", "Node is not in DRAINING state", "nodeId", nodeId));
    }
}
