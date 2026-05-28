package com.edgefabric.registry.service;

import com.edgefabric.registry.dto.RegisterRequest;
import com.edgefabric.registry.dto.RegistryResponse;
import com.edgefabric.registry.exceptions.NodeAlreadyRegisteredException;
import com.edgefabric.registry.util.node_util.ActiveNodeResponse;
import com.edgefabric.registry.util.node_util.CacheNodeInfo;
import com.edgefabric.registry.util.registry_util.InMemoryRegistryState;
import com.edgefabric.registry.util.registry_util.RegistryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class RegistryServiceTest {

    private RegistryState registryState;

    private RegistryService registryService;

    @BeforeEach
    void setUp() {
        this.registryState = new InMemoryRegistryState(new ConcurrentHashMap<>(), new AtomicLong());
        ReflectionTestUtils.setField(registryState, "ttl", 30000L);
        this.registryService = new RegistryServiceImpl(registryState);
    }

    @Test
    void validInputShouldRegisteredSuccessfully() {

        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setCacheNodeId("cache-1");
        registerRequest.setHost("localhost");
        registerRequest.setPort(8081);
        registryService.register(registerRequest);
        assertTrue(registryState.exists(registerRequest.getCacheNodeId()));
    }

    @Test
    void nodeAlreadyRegisteredShouldThrowException() {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setCacheNodeId("cache-1");
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(8081);
        registryService.register(registerRequest);
        assertTrue(registryState.exists(registerRequest.getCacheNodeId()));

        RegisterRequest registerRequest2 = new RegisterRequest();
        registerRequest2.setCacheNodeId("cache-1");
        registerRequest2.setHost("127.0.0.1");
        registerRequest2.setPort(8082);
        assertThrowsExactly(NodeAlreadyRegisteredException.class,() -> registryService.register(registerRequest2));

    }
    @Test
    void shouldReturnLatestVersionData() {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setCacheNodeId("cache-1");
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(8081);
        registryService.register(registerRequest);

        RegisterRequest registerRequest2 = new RegisterRequest();
        registerRequest2.setCacheNodeId("cache-2");
        registerRequest2.setHost("127.0.0.1");
        registerRequest2.setPort(8082);
        registryService.register(registerRequest2);
        RegistryResponse registryResponse = registryService.getRegistryState();
        assertEquals(2L, registryResponse.getRegistryVersion());
        assertThat(registryResponse.getActiveNodes()
                .contains(new ActiveNodeResponse("cache-1", "127.0.0.1", 8081)));
    }

    @Test
    void deregisterShouldRemoveNodeSuccessfully() {

        RegisterRequest request = new RegisterRequest();
        request.setCacheNodeId("cache-1");
        request.setHost("127.0.0.1");
        request.setPort(8081);

        registryService.register(request);

        assertTrue(registryState.exists("cache-1"));

        registryService.deregister("cache-1");

        assertFalse(registryState.exists("cache-1"));
    }

    @Test
    void deregisterShouldIncrementVersion() {

        RegisterRequest request = new RegisterRequest();
        request.setCacheNodeId("cache-1");
        request.setHost("localhost");
        request.setPort(8081);

        registryService.register(request);

        long versionAfterRegister = registryState.getVersion();

        registryService.deregister("cache-1");

        assertEquals(versionAfterRegister + 1, registryState.getVersion());
    }

    @Test
    void deregisterNonExistingNodeShouldNotIncrementVersion() {

        long initialVersion = registryState.getVersion();

        registryService.deregister("unknown-node");

        assertEquals(initialVersion, registryState.getVersion());
    }

    @Test
    void expiredNodeShouldBeRemoved() {

        RegisterRequest request = new RegisterRequest();
        request.setCacheNodeId("cache-1");
        request.setHost("127.0.0.1");
        request.setPort(8081);

        registryService.register(request);

        CacheNodeInfo node = registryState.getActiveNodes().get("cache-1");
        node.setLastHeartBeatTime(System.currentTimeMillis() - 60000); // 60s ago, exceeds 30s TTL

        registryState.evictExpiredNodes();

        assertFalse(registryState.exists("cache-1"));
    }

    @Test
    void nodeShouldNotBeRemovedIfHeartbeatIsRecent() {

        RegisterRequest request = new RegisterRequest();
        request.setCacheNodeId("cache-1");
        request.setHost("localhost");
        request.setPort(8081);

        registryService.register(request);

        CacheNodeInfo node = registryState.getActiveNodes().get("cache-1");
        node.setLastHeartBeatTime(System.currentTimeMillis());

        registryState.evictExpiredNodes();

        assertTrue(registryState.exists("cache-1"));
    }



    @Test
    void processHeartbeatShouldUpdateLastHeartbeatTime() {

        RegisterRequest request = new RegisterRequest();
        request.setCacheNodeId("cache-1");
        request.setHost("localhost");
        request.setPort(8081);

        registryService.register(request);

        CacheNodeInfo node = registryState.getActiveNodes().get("cache-1");
        long oldTime = node.getLastHeartBeatTime();

        registryService.processHeartbeat("cache-1");

        long updatedTime = node.getLastHeartBeatTime();

        assertTrue(updatedTime >= oldTime);
    }

    @Test
    void shouldDoNothingWhenNodeNotFound() {

        String nodeId = "node1";

        registryService.processHeartbeat(nodeId);

        assertFalse(registryState.exists(nodeId));
    }

}
