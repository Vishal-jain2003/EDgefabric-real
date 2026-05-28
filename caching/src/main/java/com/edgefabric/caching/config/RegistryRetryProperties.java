package com.edgefabric.caching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "registry.retry")
@Getter
@Setter
public class RegistryRetryProperties {
    private int attempts;
    private int delay;
}
