package com.edgefabric.caching;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.service.CacheMetricsService;
import com.edgefabric.caching.service.DrainService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CachingAppTests {

    // GossipConfig and NodeRegistration are @Profile("!test") — so MembershipList
    // bean doesn't exist in test profile. Provide a mock so the context loads.
    @MockitoBean
    private MembershipList membershipList;

    // CacheMetricsService depends on selfNodeInfo (@Profile("!test")).
    // Mock the whole service so the context loads without needing a real NodeInfo.
    @MockitoBean
    private CacheMetricsService cacheMetricsService;

    // DrainService calls membershipList.getSelf() in constructor — mock it
    // so it doesn't NPE on the mocked MembershipList.
    @MockitoBean
    private DrainService drainService;

    @Test
    void contextLoads() {
        // This test simply verifies that the Spring application context loads successfully
        // without missing beans or circular dependencies.
    }
}