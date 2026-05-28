package com.edgefabric.caching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gossip")
@Getter
@Setter
public class GossipProperties {
    private int fanout = 2;
    private long messageIntervalMs = 1000;
    private int port;
}
