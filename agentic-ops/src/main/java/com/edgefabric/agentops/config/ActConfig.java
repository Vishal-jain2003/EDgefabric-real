package com.edgefabric.agentops.config;

import com.edgefabric.agentops.act.CircuitBreaker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ActConfig {

    @Bean
    public CircuitBreaker circuitBreaker() {
        // failureThreshold=3, successThreshold=2, halfOpenTimeoutMs=60000
        return new CircuitBreaker(null, 3, 2, 60_000L);
    }
}
