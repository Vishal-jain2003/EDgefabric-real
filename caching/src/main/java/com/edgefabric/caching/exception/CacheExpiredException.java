package com.edgefabric.caching.exception;

public class CacheExpiredException extends RuntimeException {
    public CacheExpiredException(String message) {
        super(message);
    }
}

