package com.edgefabric.caching.config;

import com.edgefabric.caching.antiEntropy.S3WalReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(name = "cache.wal.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(WalReaderProperties.class)
public class S3WalReaderConfig {

    @Bean(destroyMethod = "close")
    public S3Client walReaderS3Client(WalReaderProperties props) {
        return S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public S3WalReader s3WalReader(S3Client walReaderS3Client, WalReaderProperties props, ObjectMapper objectMapper) {
        return new S3WalReader(walReaderS3Client, props, objectMapper);
    }
}
