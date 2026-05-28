package com.edgefabric.loadbalancer.resolver;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class CloudMapDnsResolverTest {

    private final CloudMapDnsResolver resolver = new CloudMapDnsResolver();

    @Test
    void resolve_shouldReturnAddresses_forLocalhost() throws UnknownHostException {
        InetAddress[] addresses = resolver.resolve("localhost");
        assertNotNull(addresses);
        assertTrue(addresses.length > 0);
    }

    /**
     * Verifies that {@code resolve()} propagates {@link UnknownHostException}
     * from the underlying DNS lookup without swallowing it.
     *
     * <p>We mock {@link InetAddress#getAllByName} statically rather than making
     * a real DNS call, because CI agents may run behind wildcard / stub DNS
     * resolvers that return a synthetic answer for any hostname — including the
     * {@code .invalid} TLD — which would prevent the real call from ever
     * throwing and would make this test flaky in those environments.</p>
     */
    @Test
    void resolve_shouldThrowUnknownHostException_forInvalidHostname() {
        try (MockedStatic<InetAddress> mockedInetAddress = mockStatic(InetAddress.class)) {
            mockedInetAddress
                    .when(() -> InetAddress.getAllByName("this-hostname-definitely-does-not-exist.invalid"))
                    .thenThrow(new UnknownHostException("this-hostname-definitely-does-not-exist.invalid"));

            assertThrows(UnknownHostException.class,
                    () -> resolver.resolve("this-hostname-definitely-does-not-exist.invalid"));
        }
    }
}


