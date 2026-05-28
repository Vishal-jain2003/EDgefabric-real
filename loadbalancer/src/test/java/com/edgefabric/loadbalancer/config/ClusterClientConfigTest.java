package com.edgefabric.loadbalancer.config;

import com.edgefabric.loadbalancer.client.ClusterClient;
import com.edgefabric.loadbalancer.client.GrpcClusterClient;
import com.edgefabric.loadbalancer.client.HttpClusterClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link ClusterClientConfig} wires the correct {@link ClusterClient}
 * implementation based on {@code edgefabric.cluster.get.transport}.
 */
class ClusterClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BaseConfig.class, ClusterClientConfig.class);

    @Test
    void grpcBean_isRegistered_whenTransportIsGrpc() {
        contextRunner
                .withPropertyValues("edgefabric.cluster.get.transport=grpc")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(GrpcClusterClient.class);
                    // GrpcClusterClient is @Primary, so injecting ClusterClient resolves to it.
                    Holder holder = ctx.getBean(Holder.class);
                    assertThat(holder.clusterClient).isInstanceOf(GrpcClusterClient.class);
                });
    }

    @Test
    void grpcBean_isNotRegistered_whenTransportIsHttp() {
        contextRunner
                .withPropertyValues("edgefabric.cluster.get.transport=http")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(GrpcClusterClient.class);
                    Holder holder = ctx.getBean(Holder.class);
                    assertThat(holder.clusterClient).isInstanceOf(HttpClusterClient.class);
                });
    }

    @Test
    void grpcBean_isNotRegistered_byDefault() {
        contextRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(GrpcClusterClient.class);
            assertThat(ctx).hasSingleBean(HttpClusterClient.class);
        });
    }

    /**
     * Minimal context: an HttpClusterClient bean (always present in production)
     * and a holder that records which {@link ClusterClient} gets injected.
     */
    @Configuration
    static class BaseConfig {
        @Bean
        HttpClusterClient httpClusterClient() {
            return new HttpClusterClient(mock(WebClient.class), 5000L);
        }

        @Bean
        Holder holder(ClusterClient clusterClient) {
            return new Holder(clusterClient);
        }
    }

    static class Holder {
        final ClusterClient clusterClient;
        @Autowired
        Holder(ClusterClient clusterClient) {
            this.clusterClient = clusterClient;
        }
    }
}

