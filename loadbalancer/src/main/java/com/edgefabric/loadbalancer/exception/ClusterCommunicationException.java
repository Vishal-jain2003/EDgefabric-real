package com.edgefabric.loadbalancer.exception;

public class ClusterCommunicationException extends RuntimeException {

    public ClusterCommunicationException(String message) {
        super(message);
    }

    public ClusterCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
