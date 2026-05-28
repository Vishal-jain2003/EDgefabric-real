package com.edgefabric.loadbalancer.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public WebClient webClient(
            WebClient.Builder builder,
            @Value("${webclient.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${webclient.response-timeout-ms:10000}") long responseTimeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
