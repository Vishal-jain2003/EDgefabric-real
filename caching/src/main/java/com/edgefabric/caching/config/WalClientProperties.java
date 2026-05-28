package com.edgefabric.caching.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the {@link com.edgefabric.caching.client.WalClient}.
 * Bound from {@code cache.wal.client.*}.
 */
@Validated
@ConfigurationProperties(prefix = "cache.wal.client")
public class WalClientProperties {

    @NotBlank(message = "cache.wal.client.lb-base-url must not be blank")
    private String lbBaseUrl = "http://loadbalancer:8080";

    @Min(value = 1, message = "cache.wal.client.queue-capacity must be >= 1")
    private int queueCapacity = 1000;

    @Min(value = 1, message = "cache.wal.client.timeout-ms must be >= 1")
    private int timeoutMs = 500;

    public String getLbBaseUrl() { return lbBaseUrl; }
    public void setLbBaseUrl(String lbBaseUrl) { this.lbBaseUrl = lbBaseUrl; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
