package com.edgefabric.loadbalancer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI loadBalancerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("EdgeFabric Load Balancer API")
                        .description("Public-facing API gateway for the EdgeFabric distributed cache. " +
                                "Routes client requests across cache nodes using consistent hashing " +
                                "and quorum-based replication.")
                        .version("v1.0.0")
                        .contact(new Contact().name("EdgeFabric Team")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")
                ));
    }
}
