    package com.edgefabric.loadbalancer.config;

    import lombok.Getter;
    import lombok.Setter;
    import org.springframework.boot.context.properties.ConfigurationProperties;
    import org.springframework.context.annotation.Configuration;



    @Configuration
    @ConfigurationProperties(prefix = "cache")
    @Getter
    @Setter
    public class CacheProperties {
        private long maxCacheEntrySizeBytes;

        private String pattern;
    }
