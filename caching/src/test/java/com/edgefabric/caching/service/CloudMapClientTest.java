package com.edgefabric.caching.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.DeregisterInstanceRequest;
import software.amazon.awssdk.services.servicediscovery.model.DeregisterInstanceResponse;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudMapClientTest {

    @Mock
    ServiceDiscoveryClient sdkClient;

    CloudMapClient cloudMapClient;

    @BeforeEach
    void setUp() {
        cloudMapClient = new CloudMapClient(sdkClient);
    }

    @Nested
    @DisplayName("registerInstance()")
    class RegisterTests {

        @Test
        @DisplayName("should call SDK with correct serviceId, instanceId, and attributes")
        void delegatesToSdk() {
            when(sdkClient.registerInstance(any(RegisterInstanceRequest.class)))
                    .thenReturn(RegisterInstanceResponse.builder().build());

            cloudMapClient.registerInstance("srv-abc", "cache-node-1", "10.0.1.5", 8082);

            ArgumentCaptor<RegisterInstanceRequest> captor =
                    ArgumentCaptor.forClass(RegisterInstanceRequest.class);
            verify(sdkClient).registerInstance(captor.capture());

            RegisterInstanceRequest req = captor.getValue();
            assertEquals("srv-abc", req.serviceId());
            assertEquals("cache-node-1", req.instanceId());
            assertEquals("10.0.1.5", req.attributes().get("AWS_INSTANCE_IPV4"));
            assertEquals("8082", req.attributes().get("AWS_INSTANCE_PORT"));
        }

        @Test
        @DisplayName("should propagate SDK exceptions")
        void propagatesSdkException() {
            when(sdkClient.registerInstance(any(RegisterInstanceRequest.class)))
                    .thenThrow(new RuntimeException("network error"));

            assertThrows(RuntimeException.class,
                    () -> cloudMapClient.registerInstance("srv-abc", "id", "1.2.3.4", 8082));
        }
    }

    @Nested
    @DisplayName("deregisterInstance()")
    class DeregisterTests {

        @Test
        @DisplayName("should call SDK with correct serviceId and instanceId")
        void delegatesToSdk() {
            when(sdkClient.deregisterInstance(any(DeregisterInstanceRequest.class)))
                    .thenReturn(DeregisterInstanceResponse.builder().build());

            cloudMapClient.deregisterInstance("srv-abc", "cache-node-1");

            ArgumentCaptor<DeregisterInstanceRequest> captor =
                    ArgumentCaptor.forClass(DeregisterInstanceRequest.class);
            verify(sdkClient).deregisterInstance(captor.capture());

            DeregisterInstanceRequest req = captor.getValue();
            assertEquals("srv-abc", req.serviceId());
            assertEquals("cache-node-1", req.instanceId());
        }

        @Test
        @DisplayName("should propagate SDK exceptions")
        void propagatesSdkException() {
            when(sdkClient.deregisterInstance(any(DeregisterInstanceRequest.class)))
                    .thenThrow(new RuntimeException("timeout"));

            assertThrows(RuntimeException.class,
                    () -> cloudMapClient.deregisterInstance("srv-abc", "id"));
        }
    }

    @Nested
    @DisplayName("close()")
    class CloseTests {

        @Test
        @DisplayName("should close the underlying SDK client")
        void closesDelegateClient() {
            cloudMapClient.close();
            verify(sdkClient).close();
        }
    }
}

