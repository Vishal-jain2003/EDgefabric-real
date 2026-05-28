package com.edgefabric.caching.exception;

public class RegistryNotFoundException extends RuntimeException {
    public RegistryNotFoundException(String message) {
        super(message);
    }

    public RegistryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
