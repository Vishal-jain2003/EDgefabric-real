package com.edgefabric.caching.service;

import com.edgefabric.caching.config.CloudMapProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.DeregisterInstanceRequest;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;

import java.util.Map;

/**
 * Thin wrapper around the AWS Cloud Map {@link ServiceDiscoveryClient}.
 *
 * <p>Only instantiated when {@code cloudmap.enabled=true} and the
 * {@code test} profile is inactive — so Docker Compose, local dev,
 * and unit tests are unaffected.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "cloudmap.enabled", havingValue = "true")
@Slf4j
public class CloudMapClient {

    private final ServiceDiscoveryClient client;

    @Autowired
    public CloudMapClient(CloudMapProperties properties) {
        this.client = ServiceDiscoveryClient.builder()
                .region(Region.of(properties.getRegion()))
                .build();
        log.info("CloudMapClient initialised for region {}", properties.getRegion());
    }

    /** Package-private — allows unit tests to inject a mock SDK client. */
    CloudMapClient(ServiceDiscoveryClient client) {
        this.client = client;
    }

    /**
     * Register a cache-node instance in Cloud Map so that DNS queries
     * for the cluster hostname return this node's IP.
     */
    public void registerInstance(String serviceId, String instanceId, String ipv4, int port) {
        client.registerInstance(RegisterInstanceRequest.builder()
                .serviceId(serviceId)
                .instanceId(instanceId)
                .attributes(Map.of(
                        "AWS_INSTANCE_IPV4", ipv4,
                        "AWS_INSTANCE_PORT", String.valueOf(port)))
                .build());
        log.info("Registered instance {} ({}:{}) in Cloud Map service {}",
                instanceId, ipv4, port, serviceId);
    }

    /**
     * Remove the instance from Cloud Map so DNS stops returning its IP.
     */
    public void deregisterInstance(String serviceId, String instanceId) {
        client.deregisterInstance(DeregisterInstanceRequest.builder()
                .serviceId(serviceId)
                .instanceId(instanceId)
                .build());
        log.info("Deregistered instance {} from Cloud Map service {}",
                instanceId, serviceId);
    }

    @PreDestroy
    public void close() {
        client.close();
        log.info("CloudMapClient closed");
    }
}

