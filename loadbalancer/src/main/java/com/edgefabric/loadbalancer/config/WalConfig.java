package com.edgefabric.loadbalancer.config;

import com.edgefabric.loadbalancer.wal.LocalWalWriter;
import com.edgefabric.loadbalancer.wal.WalProperties;
import com.edgefabric.loadbalancer.wal.WalSegmentFlusher;
import com.edgefabric.loadbalancer.wal.WalWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * WAL beans — only created when {@code wal.enabled=true}.
 *
 * <p>Two storage backends are supported:
 * <ul>
 *   <li><b>s3</b> (default) — {@link WalSegmentFlusher} writes NDJSON segments to AWS S3.
 *       Requires valid AWS credentials ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}
 *       or instance profile).</li>
 *   <li><b>local</b> — {@link LocalWalWriter} writes to the local filesystem under
 *       {@code wal.local-dir}.  No AWS credentials required; suitable for local development.</li>
 * </ul>
 *
 * <p>When {@code wal.enabled=false} (the default) <em>no</em> WAL bean is registered and
 * {@link com.edgefabric.loadbalancer.service.CacheGatewayService} receives an empty
 * {@code Optional<WalWriter>}, making the WAL a strict no-op.
 */
@Configuration
@EnableConfigurationProperties(WalProperties.class)
@ConditionalOnProperty(prefix = "wal", name = "enabled", havingValue = "true")
public class WalConfig {

    // ── S3 backend (wal.storage=s3, or storage not specified) ────────────────

    @Bean
    @ConditionalOnProperty(prefix = "wal", name = "storage", havingValue = "s3", matchIfMissing = true)
    public S3Client walS3Client(WalProperties props) {
        return S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "wal", name = "storage", havingValue = "s3", matchIfMissing = true)
    public WalWriter walWriter(S3Client walS3Client, WalProperties props, ObjectMapper objectMapper) {
        return new WalSegmentFlusher(walS3Client, props, objectMapper);
    }

    // ── Local backend (wal.storage=local) ────────────────────────────────────

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "wal", name = "storage", havingValue = "local")
    public WalWriter localWalWriter(WalProperties props, ObjectMapper objectMapper) {
        return new LocalWalWriter(props, objectMapper);
    }
}
