package com.edgefabric.caching.config;

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
    public OpenAPI cachingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("EdgeFabric Cache Node API")
                        .description("Internal APIs for direct cache data storage/retrieval, LRU eviction, " +
                                "and cluster membership. Cache nodes communicate with each other " +
                                "via gossip protocol on these endpoints.")
                        .version("v1.0.0")
                        .contact(new Contact().name("EdgeFabric Team")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Cache Node 1 (local)"),
                        new Server().url("http://localhost:8082").description("Cache Node 2 (local)"),
                        new Server().url("http://localhost:8083").description("Cache Node 3 (local)")
                ));
    }
}
