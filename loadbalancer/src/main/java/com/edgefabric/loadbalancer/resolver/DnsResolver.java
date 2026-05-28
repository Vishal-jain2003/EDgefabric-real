package com.edgefabric.loadbalancer.resolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
public interface DnsResolver {
    InetAddress[] resolve(String hostname) throws UnknownHostException;
}

