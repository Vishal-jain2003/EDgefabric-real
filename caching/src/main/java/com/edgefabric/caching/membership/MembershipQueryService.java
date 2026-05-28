package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MembershipQueryService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MembershipStore store;
    private final String selfId;

    public MembershipQueryService(MembershipStore store, String selfId) {
        this.store = store;
        this.selfId = selfId;
    }

    public List<NodeInfo> getAliveNodes(){
        return store.getAll()
                .stream()
                .filter(n -> n.getStatus() == Status.ALIVE)
                .toList();
    }

    public List<NodeInfo> getRandomPeers(int k){
        List<NodeInfo> candidates = new ArrayList<>(store.getAll().stream()
                .filter(n -> !n.getCacheNodeId().equals(selfId))
                .filter(n -> n.getStatus() == Status.ALIVE || n.getStatus() == Status.SUSPECT || n.getStatus() == Status.DRAINING)
                .toList());

        Collections.shuffle(candidates, SECURE_RANDOM);
        return List.copyOf(candidates.subList(0, Math.min(k, candidates.size())));
    }

    public List<NodeInfo> getNodesForSuspectCheck(){
        return store.getAll()
                .stream()
                .filter(n -> !n.getCacheNodeId().equals(selfId))
                .filter(n -> n.getStatus() == Status.ALIVE || n.getStatus() == Status.SUSPECT || n.getStatus() == Status.DRAINING)
                .toList();
    }
}
