package com.edgefabric.caching.resolver;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DnsNodeDiscoveryResolverTest {

    @Test
    void shouldReturnIpListWhenDnsResolves() {

        InetAddress mockAddress1 = mock(InetAddress.class);
        InetAddress mockAddress2 = mock(InetAddress.class);

        when(mockAddress1.getHostAddress()).thenReturn("192.168.1.1");
        when(mockAddress2.getHostAddress()).thenReturn("192.168.1.2");

        try (MockedStatic<InetAddress> mocked = mockStatic(InetAddress.class)) {

            mocked.when(() -> InetAddress.getAllByName(anyString()))
                    .thenReturn(new InetAddress[]{mockAddress1, mockAddress2});

            DnsNodeDiscoveryResolver resolver = new DnsNodeDiscoveryResolver("cache-nodes.cache-cluster.internal");
            List<String> result = resolver.resolve();

            assertEquals(2, result.size());
            assertTrue(result.contains("192.168.1.1"));
            assertTrue(result.contains("192.168.1.2"));
        }
    }

    @Test
    void shouldReturnEmptyListWhenDnsFails() {

        try (MockedStatic<InetAddress> mocked = mockStatic(InetAddress.class)) {

            mocked.when(() -> InetAddress.getAllByName(anyString()))
                    .thenThrow(new UnknownHostException("DNS failure"));

            DnsNodeDiscoveryResolver resolver = new DnsNodeDiscoveryResolver("cache-nodes.cache-cluster.internal");
            List<String> result = resolver.resolve();

            assertTrue(result.isEmpty());
        }
    }
}