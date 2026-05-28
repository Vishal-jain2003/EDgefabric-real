package com.edgefabric.registry.exceptions;

public class NodeAlreadyRegisteredException extends RuntimeException{
    public NodeAlreadyRegisteredException(String nodeId) {
        super("Node already registered: " + nodeId);
    }

}
