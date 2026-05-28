package com.edgefabric.loadbalancer.exception;

import lombok.Getter;

@Getter
public class CacheValidationException extends RuntimeException{

    private final long actualValue;
    private final long maxAllowedValue;

    public CacheValidationException(String message, long actualValue, long maxAllowedValue){
        super(message);
        this.actualValue = actualValue;
        this.maxAllowedValue = maxAllowedValue;
    }

}
