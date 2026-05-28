package com.edgefabric.loadbalancer.validation;

import com.edgefabric.loadbalancer.config.CacheProperties;
import org.springframework.stereotype.Component;
import com.edgefabric.loadbalancer.exception.CacheValidationException;

@Component
public class CacheEntryValidator {
    private final CacheProperties cacheProperties;

    public CacheEntryValidator(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
    }

    public void validateData(byte[] data){
        if(data==null || data.length==0){
            throw new IllegalArgumentException(
                    "Cache value must not be null or empty."
            );
        }

        if(data.length > cacheProperties.getMaxCacheEntrySizeBytes()){
            throw new CacheValidationException(
                    "Cache value exceeds the allowed maximum cache value."
                    , data.length
                    , cacheProperties.getMaxCacheEntrySizeBytes()
            );
        }
    }
}
