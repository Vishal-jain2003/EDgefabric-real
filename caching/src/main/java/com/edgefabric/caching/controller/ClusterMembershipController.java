package com.edgefabric.caching.controller;

import com.edgefabric.caching.dto.ClusterMemberDTO;
import com.edgefabric.caching.dto.GossipMemberDTO;
import com.edgefabric.caching.dto.GossipTableDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.service.ClusterMembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Cluster Membership", description = "Inspect the live cluster view and gossip state of this cache node")
@Slf4j
@RestController
@RequestMapping("/internal/cluster")
@RequiredArgsConstructor
public class ClusterMembershipController {

    private final ClusterMembershipService clusterMembershipService;
    private final MembershipList membershipList;

    @Operation(summary = "List alive cluster members",
            description = "Returns the set of nodes currently considered ALIVE by this node's failure detector. " +
                    "Used by the load balancer to discover healthy peers for routing.")
    @ApiResponse(responseCode = "200", description = "List of alive cluster members",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = ClusterMemberDTO.class))))
    @GetMapping("/members")
    public ResponseEntity<List<ClusterMemberDTO>> getClusterMembers() {
        List<ClusterMemberDTO> members = clusterMembershipService.getAliveMembers();
        log.info("Membership request received — returning {} alive cluster members", members.size());
        return ResponseEntity.ok(members);
    }

    /**
     * Returns the full gossip membership table — all nodes regardless of status.
     *
     * <p>Use this to verify the gossip protocol is working:</p>
     * <ul>
     *   <li><b>heartbeat</b> should increment every gossip round (~every 5 s by default)</li>
     *   <li><b>secondsSinceUpdate</b> should stay low for live nodes</li>
     *   <li><b>status</b> changes (ALIVE → SUSPECT → DEAD) confirm failure detection works</li>
     *   <li>New nodes appearing here confirm peer discovery is working</li>
     * </ul>
     */
    @Operation(summary = "Get full gossip table",
            description = "Returns all nodes known to this node's gossip protocol — ALIVE, SUSPECT, and DEAD. " +
                    "Useful for debugging: heartbeat should increment each gossip round (~5 s), " +
                    "and secondsSinceUpdate should stay low for healthy nodes.")
    @ApiResponse(responseCode = "200", description = "Full gossip membership table",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GossipTableDTO.class)))
    @GetMapping("/gossip")
    public ResponseEntity<GossipTableDTO> getGossipTable() {
        String selfId = membershipList.getSelf().getCacheNodeId();

        List<GossipMemberDTO> rows = membershipList.getDigest()
                .stream()
                .map(node -> GossipMemberDTO.from(node, selfId))
                .toList();

        return ResponseEntity.ok(new GossipTableDTO(rows));
    }
}



