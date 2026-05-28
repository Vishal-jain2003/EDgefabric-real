package com.edgefabric.caching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("registry")
@Getter
@Setter
public class RegistryProperties {
    private String serviceRegistryUrl;
    private int heartbeatIntervalMs;
}
