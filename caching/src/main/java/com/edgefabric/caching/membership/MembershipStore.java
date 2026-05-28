package com.edgefabric.caching.membership;

import com.edgefabric.caching.model.NodeInfo;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;


public class MembershipStore {
    private final ConcurrentHashMap<String, NodeInfo> members;

    public MembershipStore() {
        this.members = new ConcurrentHashMap<>();
    }

    public NodeInfo get(String nodeId){
        return members.get(nodeId);
    }

    public void put(NodeInfo node){
        members.put(node.getCacheNodeId(), node);
    }

    public Collection<NodeInfo> getAll(){
        return members.values();
    }

    public NodeInfo remove(String nodeId){
        return members.remove(nodeId);
    }

    public boolean contains(String nodeId){
        return members.containsKey(nodeId);
    }

    public int size(){
        return members.size();
    }
}
