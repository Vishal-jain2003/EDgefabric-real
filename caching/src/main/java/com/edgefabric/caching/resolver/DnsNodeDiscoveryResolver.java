package com.edgefabric.caching.resolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;


@Component
public class DnsNodeDiscoveryResolver {

    private final String clusterDnsName;

    public DnsNodeDiscoveryResolver(
            @Value("${cluster.dns-name:cache-nodes.cache-cluster.internal}") String clusterDnsName) {
        this.clusterDnsName = clusterDnsName;
    }

    public List<String> resolve() {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(clusterDnsName);

            return Arrays.stream(addresses)
                    .map(InetAddress::getHostAddress)
                    .toList();

        } catch (UnknownHostException e) {
            return List.of();
        }
    }
}