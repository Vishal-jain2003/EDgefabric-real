package com.edgefabric.caching.config;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.service.CloudMapClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudMapRegistrationTest {

    @Mock
    CloudMapClient cloudMapClient;

    CloudMapProperties properties;
    NodeInfo selfNodeInfo;
    CloudMapRegistration registration;

    @BeforeEach
    void setUp() {
        properties = new CloudMapProperties();
        properties.setEnabled(true);
        properties.setServiceId("srv-test123");
        properties.setRegion("ap-south-1");

        selfNodeInfo = new NodeInfo("cache-node-10.0.1.5-8082", "10.0.1.5", 8082, 7946);
        registration = new CloudMapRegistration(cloudMapClient, properties, selfNodeInfo);
    }

    /**
     * Ensures every test cleans up properly.  Because {@code registered} is
     * now cleared <em>before</em> the network call inside {@code deregister()},
     * this @AfterEach is a guaranteed no-op for any test that already called
     * {@code deregister()} — whether it succeeded or failed — so it never
     * triggers an unexpected interaction on the mock.
     */
    @AfterEach
    void tearDown() {
        registration.deregister();
    }

    // ── Registration tests ─────────────────────────────────────────

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("should register with correct serviceId, instanceId, host, port")
        void registersWithCorrectArgs() {
            registration.register();

            verify(cloudMapClient).registerInstance(
                    "srv-test123",
                    "cache-node-10.0.1.5-8082",
                    "10.0.1.5",
                    8082);
        }

        @Test
        @DisplayName("should skip registration when serviceId is null")
        void skipsWhenServiceIdNull() {
            properties.setServiceId(null);

            registration.register();

            verifyNoInteractions(cloudMapClient);
        }

        @Test
        @DisplayName("should skip registration when serviceId is blank")
        void skipsWhenServiceIdBlank() {
            properties.setServiceId("   ");

            registration.register();

            verifyNoInteractions(cloudMapClient);
        }

        @Test
        @DisplayName("should not throw when CloudMap API fails")
        void doesNotThrowOnApiFailure() {
            doThrow(new RuntimeException("AWS SDK error"))
                    .when(cloudMapClient)
                    .registerInstance(anyString(), anyString(), anyString(), anyInt());

            assertDoesNotThrow(() -> registration.register());
        }

        @Test
        @DisplayName("should not set registered flag when registration fails")
        void registeredFlagFalseOnFailure() {
            doThrow(new RuntimeException("AWS SDK error"))
                    .when(cloudMapClient)
                    .registerInstance(anyString(), anyString(), anyString(), anyInt());

            registration.register();

            // deregister should skip because registered is still false
            registration.deregister();
            verify(cloudMapClient, never()).deregisterInstance(anyString(), anyString());
        }
    }

    // ── Deregistration tests ───────────────────────────────────────

    @Nested
    @DisplayName("deregister()")
    class DeregisterTests {

        @Test
        @DisplayName("should deregister after successful registration")
        void deregistersAfterSuccessfulRegister() {
            registration.register();
            registration.deregister();

            verify(cloudMapClient).deregisterInstance(
                    "srv-test123",
                    "cache-node-10.0.1.5-8082");
        }

        @Test
        @DisplayName("should skip deregister if register was never called")
        void skipsDeregisterIfNeverRegistered() {
            registration.deregister();

            verify(cloudMapClient, never()).deregisterInstance(anyString(), anyString());
        }

        @Test
        @DisplayName("should skip deregister if registration failed")
        void skipsDeregisterIfRegistrationFailed() {
            doThrow(new RuntimeException("fail"))
                    .when(cloudMapClient)
                    .registerInstance(anyString(), anyString(), anyString(), anyInt());

            registration.register();
            registration.deregister();

            verify(cloudMapClient, never()).deregisterInstance(anyString(), anyString());
        }

        @Test
        @DisplayName("should not throw when deregister API fails")
        void doesNotThrowOnDeregisterFailure() {
            registration.register();

            doThrow(new RuntimeException("deregister failed"))
                    .when(cloudMapClient)
                    .deregisterInstance(anyString(), anyString());

            assertDoesNotThrow(() -> registration.deregister());

            // After a failed deregistration the `registered` flag must already be
            // cleared (it is reset BEFORE the network call) so that a repeated
            // @PreDestroy invocation — or the @AfterEach teardown — is a
            // guaranteed no-op and never causes a second attempt on a failing client.
            assertDoesNotThrow(() -> registration.deregister());
            verify(cloudMapClient, times(1)).deregisterInstance(anyString(), anyString());
        }

        @Test
        @DisplayName("should skip deregister when serviceId was blank at register time")
        void skipsDeregisterIfServiceIdWasBlank() {
            properties.setServiceId("   ");
            registration.register();
            properties.setServiceId("srv-test123"); // set it later

            registration.deregister();

            verify(cloudMapClient, never()).deregisterInstance(anyString(), anyString());
        }
    }
}

