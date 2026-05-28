package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;

public class MembershipStateManager {
    public long bumpHeartbeat(NodeInfo node){
        return node.bumpHeartbeat();
    }

    public long refute(NodeInfo node, long incomingIncarnation){
        return node.refute(incomingIncarnation);
    }

    public boolean markAlive(NodeInfo node){
        return node.transitionToAlive();
    }

    public boolean markSuspect(NodeInfo node){
        return node.transitionToSuspect();
    }

    public boolean markDead(NodeInfo node){
        return node.transitionToDead();
    }

    public boolean markDraining(NodeInfo node){
        return node.transitionToDraining();
    }

    public boolean cancelDraining(NodeInfo node){
        return node.transitionFromDrainingToAlive();
    }
}
