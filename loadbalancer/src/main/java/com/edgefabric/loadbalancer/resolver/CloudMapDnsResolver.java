package com.edgefabric.loadbalancer.resolver;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class CloudMapDnsResolver implements DnsResolver {

    @Override
    public InetAddress[] resolve(String hostname) throws UnknownHostException {
        return InetAddress.getAllByName(hostname);
    }
}

