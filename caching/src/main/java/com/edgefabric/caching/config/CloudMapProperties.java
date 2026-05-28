package com.edgefabric.caching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Properties for AWS Cloud Map auto-registration.
 *
 * <p>When {@code cloudmap.enabled=true} the cache node registers itself
 * on startup and deregisters on graceful shutdown — removing the need
 * for Jenkins/CI to call the Cloud Map API during deploy.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "cloudmap")
@Getter
@Setter
public class CloudMapProperties {

    /** Master switch. Disabled by default (Docker Compose / local dev). */
    private boolean enabled = false;

    /** Cloud Map service ID, e.g. {@code srv-6lnd44knosnojplq}. */
    private String serviceId;

    /** AWS region for the Cloud Map API. Defaults to ap-south-1. */
    private String region = "ap-south-1";
}

