package com.edgefabric.caching.migration;

import com.edgefabric.hashing.config.HashRingProperties;
import com.edgefabric.hashing.core.ConsistentHashRing;
import com.edgefabric.hashing.core.HashProvider;
import com.edgefabric.hashing.core.HashProviderFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
@ConditionalOnProperty(name = "migration.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationConfig {

    @Bean
    @ConfigurationProperties(prefix = "ring")
    public HashRingProperties migrationHashRingProperties() {
        return new HashRingProperties();
    }

    @Bean
    public HashProvider migrationHashProvider(HashRingProperties migrationHashRingProperties) {
        return HashProviderFactory.create(migrationHashRingProperties.getHashAlgorithm());
    }

    @Bean
    public ConsistentHashRing<NodeInfoHashAdapter> migrationHashRing(
            HashProvider migrationHashProvider,
            HashRingProperties migrationHashRingProperties) {
        return new ConsistentHashRing<>(migrationHashProvider,
                migrationHashRingProperties.getVirtualNodes());
    }

    @Bean
    public WebClient migrationWebClient() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
