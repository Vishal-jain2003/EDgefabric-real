package com.edgefabric.caching.event;

import com.edgefabric.caching.model.NodeInfo;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Published when the gossip protocol detects a stable topology change
 * (node transitions to ALIVE or DEAD).
 *
 * <p>Listeners should treat {@code allAliveNodes} as the authoritative
 * membership snapshot at event time.</p>
 */
public class TopologyChangedEvent extends ApplicationEvent {

    private final transient Set<NodeInfo> addedNodes;
    private final transient Set<NodeInfo> removedNodes;
    private final transient List<NodeInfo> allAliveNodes;

    public TopologyChangedEvent(Object source,
                                Set<NodeInfo> addedNodes,
                                Set<NodeInfo> removedNodes,
                                List<NodeInfo> allAliveNodes) {
        super(source);
        this.addedNodes = Collections.unmodifiableSet(addedNodes);
        this.removedNodes = Collections.unmodifiableSet(removedNodes);
        this.allAliveNodes = Collections.unmodifiableList(allAliveNodes);
    }

    public Set<NodeInfo> getAddedNodes() {
        return addedNodes;
    }

    public Set<NodeInfo> getRemovedNodes() {
        return removedNodes;
    }

    public List<NodeInfo> getAllAliveNodes() {
        return allAliveNodes;
    }
}
