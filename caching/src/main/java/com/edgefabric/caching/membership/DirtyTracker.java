package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DirtyTracker {
    private final Set<String> dirtyNodes;

    public DirtyTracker() {
        this.dirtyNodes = ConcurrentHashMap.newKeySet();
    }

    public void markDirty(String nodeId){
        dirtyNodes.add(nodeId);
    }

    public List<NodeInfo> getAndClear(MembershipStore store){
        // Atomic drain: snapshot and remove in one pass to avoid losing
        // entries marked dirty between snapshot and removal.
        Set<String> snapshot = new HashSet<>();
        dirtyNodes.removeIf(id -> {
            snapshot.add(id);
            return true;
        });

        List<NodeInfo> result = new ArrayList<>();
        for(String id : snapshot){
            NodeInfo node = store.get(id);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }
}
