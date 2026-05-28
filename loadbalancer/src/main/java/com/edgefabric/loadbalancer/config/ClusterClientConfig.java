package com.edgefabric.loadbalancer.config;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.client.GrpcClusterClient;
import com.edgefabric.loadbalancer.client.HttpClusterClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the active {@link ClusterClient} implementation based on
 * {@code edgefabric.cluster.get.transport}.
 *
 * <ul>
 *   <li>{@code http} (default) — {@link HttpClusterClient} stays the only bean,
 *       legacy behaviour preserved.</li>
 *   <li>{@code grpc} — {@link GrpcClusterClient} is registered as the primary
 *       bean and used by {@code QuorumService}; PUT is internally delegated
 *       back to {@link HttpClusterClient}.</li>
 * </ul>
 *
 * <p>This indirection ensures zero changes to consumers of {@code ClusterClient}
 * (e.g. {@code QuorumService}, {@code ReadRepairService}).
 */
@Configuration
public class ClusterClientConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "edgefabric.cluster.get.transport", havingValue = "grpc")
    public GrpcClusterClient grpcClusterClient(
            HttpClusterClient httpClusterClient,
            @Value("${edgefabric.cluster.grpc.port:9091}") int grpcPort,
            @Value("${edgefabric.cluster.sync.timeout-ms:5000}") long deadlineMs) {
        return new GrpcClusterClient(httpClusterClient, grpcPort, deadlineMs);
    }
}

