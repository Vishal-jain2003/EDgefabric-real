package com.edgefabric.caching.controller;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Cluster Gossip", description = "Gossip protocol join endpoint used for cluster membership exchange between cache nodes")
@RestController
@RequestMapping("/cluster")
@RequiredArgsConstructor
public class ClusterController {

    private final MembershipList membershipList;

    @Operation(summary = "Join / gossip exchange",
            description = "A peer sends its NodeInfo to this node as part of the gossip protocol. " +
                    "This node merges the incoming state into its membership list and returns its full " +
                    "membership digest so both peers converge to the same view of the cluster.")
    @ApiResponse(responseCode = "200", description = "Membership digest returned after merge",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = NodeInfo.class))))
    @PostMapping("/join")
    public ResponseEntity<List<NodeInfo>> join(@RequestBody NodeInfo incomingNode) {

        membershipList.merge(incomingNode);

        return ResponseEntity.ok(membershipList.getDigest());
    }
}