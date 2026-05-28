package com.edgefabric.caching.config;

import com.edgefabric.caching.client.WalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link WalClient} bean — active only when {@code cache.wal.enabled=true}.
 *
 * <p>The WalClient is also annotated with {@link ConditionalOnProperty} for component-scan
 * safety, but this config class provides the explicit bean definition so that constructor
 * arguments from {@link WalClientProperties} are injected cleanly.
 */
@Configuration
@ConditionalOnProperty(name = "cache.wal.enabled", havingValue = "true")
@EnableConfigurationProperties(WalClientProperties.class)
public class WalClientConfig {

    @Bean
    public WalClient walClient(WalClientProperties props, MeterRegistry meterRegistry,
                               ObjectMapper objectMapper) {
        return new WalClient(
                props.getLbBaseUrl(),
                props.getQueueCapacity(),
                props.getTimeoutMs(),
                meterRegistry,
                objectMapper
        );
    }
}
