package com.edgefabric.caching.service;

import com.edgefabric.caching.dto.ClusterMemberDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Exposes the gossip membership table to external consumers (e.g. Load Balancer).
 *
 * <p>Returns only {@link com.edgefabric.caching.model.Status#ALIVE ALIVE} nodes
 * so the LB never routes traffic to suspected or dead members.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterMembershipService {

    private final MembershipList membershipList;

    public List<ClusterMemberDTO> getAliveMembers() {
        List<NodeInfo> aliveNodes = membershipList.getAliveNodes();

        List<ClusterMemberDTO> members = aliveNodes.stream()
                .map(this::toDTO)
                .toList();

        log.debug("Membership query: returning {} alive member(s)", members.size());
        return members;
    }

    private ClusterMemberDTO toDTO(NodeInfo node) {
        return new ClusterMemberDTO(
                node.getCacheNodeId(),
                node.getHost(),
                node.getServicePort()
        );
    }
}

