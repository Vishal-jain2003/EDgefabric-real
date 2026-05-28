package com.edgefabric.caching.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
public class AppConfig {

    /**
     * Registry-bound WebClient — only created when registry integration is enabled.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "edgefabric.registry.enabled", havingValue = "true", matchIfMissing = false)
    public WebClient webClient(RegistryProperties registryProperties){
        return  WebClient
                .builder()
                .baseUrl(registryProperties.getServiceRegistryUrl())
                .build();
    }

    @Bean
    public WebClient peerWebClient() {
        return WebClient.builder().build();
    }

    /**
     * Blocking RestClient for peer-to-peer calls (cluster join, etc.).
     * Uses bounded connect/read timeouts to prevent thread exhaustion
     * when used inside @Async methods.
     */
    @Bean
    public RestClient peerRestClient() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

}
