package com.edgefabric.caching.gossip;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class PeerSelector {

    /**
     * Shared Random instance for shuffling.
     * SecureRandom is cryptographically secure but 10-100x slower to instantiate.
     * java.util.Random is sufficient for gossip peer selection (no security requirement).
     */
    private static final Random RNG = new Random();

    private final MembershipList membershipList;
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);
    private volatile List<String> probeRing = List.of();

    public PeerSelector(MembershipList membershipList) {
        this.membershipList = membershipList;
    }

    /**
     * Selects the next target for failure detection probing using lock-free round-robin.
     * Removed synchronized keyword - uses AtomicInteger and volatile for thread-safety.
     * This eliminates lock contention between failure detector and gossip sender.
     */
    public Optional<NodeInfo> nextTarget() {
        String selfId = membershipList.getSelf().getCacheNodeId();

        List<NodeInfo> alivePeers = membershipList.getNodesForSuspectCheck().stream()
                .filter(node -> !node.getCacheNodeId().equals(selfId))
                .toList();

        if (alivePeers.isEmpty()) {
            probeRing = List.of();
            roundRobinCursor.set(0);
            return Optional.empty();
        }

        Set<String> aliveIds = alivePeers.stream()
                .map(NodeInfo::getCacheNodeId)
                .collect(Collectors.toSet());

        // Rebuild probe ring if membership changed
        if (!aliveIds.equals(Set.copyOf(probeRing))) {
            ArrayList<String> shuffled = new ArrayList<>(aliveIds);
            Collections.shuffle(shuffled, RNG);  // Use static Random instead of new SecureRandom()
            probeRing = List.copyOf(shuffled);
            roundRobinCursor.set(0);
        }

        // AtomicInteger getAndIncrement is lock-free
        List<String> currentRing = probeRing;  // volatile read
        if (currentRing.isEmpty()) {
            return Optional.empty();
        }

        int index = Math.floorMod(roundRobinCursor.getAndIncrement(), currentRing.size());
        String targetId = currentRing.get(index);
        NodeInfo target = membershipList.getNode(targetId);

        if (target == null || target.getStatus() == Status.DEAD || target.getStatus() == Status.SUSPECT) {
            return Optional.empty();
        }

        return Optional.of(target);
    }

    public List<NodeInfo> selectHelpers(String targetNodeId, int maxHelpers) {
        if (maxHelpers <= 0) {
            return List.of();
        }

        // Request one extra to account for filtering out the target
        List<NodeInfo> candidates = membershipList.getRandomPeers(maxHelpers + 1);

        return candidates.stream()
                .filter(node -> !node.getCacheNodeId().equals(targetNodeId))
                .limit(maxHelpers)
                .toList();
    }
}

