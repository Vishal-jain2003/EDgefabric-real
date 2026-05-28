package com.edgefabric.agentops.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Creates named {@link RestClient} beans for each upstream data source.
 * Each bean is pre-configured with the correct base URL from {@link AgentOpsProperties}.
 */
@Configuration
@EnableConfigurationProperties(AgentOpsProperties.class)
public class RestClientConfig {

    @Bean("loadBalancerRestClient")
    public RestClient loadBalancerRestClient(AgentOpsProperties props) {
        return RestClient.builder()
                .baseUrl(props.loadbalancerBaseUrl())
                .build();
    }

    @Bean("prometheusRestClient")
    public RestClient prometheusRestClient(AgentOpsProperties props) {
        return RestClient.builder()
                .baseUrl(props.prometheusBaseUrl())
                .build();
    }

    @Bean("lokiRestClient")
    public RestClient lokiRestClient(AgentOpsProperties props) {
        return RestClient.builder()
                .baseUrl(props.lokiBaseUrl())
                .build();
    }

    @Bean("tempoRestClient")
    public RestClient tempoRestClient(AgentOpsProperties props) {
        return RestClient.builder()
                .baseUrl(props.tempoBaseUrl())
                .build();
    }

    /**
     * Cache-node client has no fixed base URL — it is constructed per-node at call time.
     */
    @Bean("cacheNodeRestClient")
    public RestClient cacheNodeRestClient() {
        return RestClient.builder().build();
    }
}
