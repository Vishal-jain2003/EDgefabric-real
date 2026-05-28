package com.edgefabric.loadbalancer.exception;

public class ClusterBootstrapException extends RuntimeException {

    public ClusterBootstrapException(String message) {
        super(message);
    }

    public ClusterBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}

