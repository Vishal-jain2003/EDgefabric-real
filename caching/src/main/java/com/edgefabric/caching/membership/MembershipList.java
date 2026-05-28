package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;

import java.util.List;

public interface MembershipList {

    NodeInfo getSelf();

    NodeInfo getNode(String nodeId);

    int size();

    void bumpSelfHeartbeat();

    void refuteSuspicion();

    void merge(NodeInfo incoming);

    void markSuspect(String nodeId);

    void markAlive(String nodeId);

    void markDead(String nodeId);

    void markDraining(String nodeId);

    void cancelDraining(String nodeId);

    List<NodeInfo> getAliveNodes();

    List<NodeInfo> getNodesForSuspectCheck();

    List<NodeInfo> getDigest();

    List<NodeInfo> getRandomPeers(int k);

    List<NodeInfo> getDirtyDigestAndClear();

    void markDirty(String nodeId);

    /**
     * Removes a DEAD node from the membership table entirely.
     * Used by the dead-node reaper after a node has been DEAD for longer than the
     * configured TTL. Returns {@code true} if the node was removed, {@code false}
     * if the node was not found or is not in DEAD state.
     *
     * <p><b>Safety:</b> refuses to remove self and refuses to remove nodes that
     * are not DEAD (prevents accidental eviction of healthy nodes).
     */
    boolean removeNode(String nodeId);

    /**
     * Returns all nodes currently in DEAD state.
     */
    List<NodeInfo> getDeadNodes();
}
