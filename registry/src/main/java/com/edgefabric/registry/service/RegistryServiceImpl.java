package com.edgefabric.registry.service;



import com.edgefabric.registry.util.node_util.ActiveNodeResponse;
import com.edgefabric.registry.dto.RegisterRequest;
import com.edgefabric.registry.dto.RegistryResponse;
import com.edgefabric.registry.exceptions.NodeAlreadyRegisteredException;
import com.edgefabric.registry.util.node_util.CacheNodeInfo;
import com.edgefabric.registry.util.registry_util.RegistryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RegistryServiceImpl implements RegistryService{



    private final RegistryState registryState;
    private static final Logger logger = LoggerFactory.getLogger(RegistryServiceImpl.class);

    @Autowired
    public RegistryServiceImpl(RegistryState registryState){
        this.registryState = registryState;
    }


    @Override
    public void register(RegisterRequest request) {

        String cacheNodeId = request.getCacheNodeId();
        String host = request.getHost();
        int port = request.getPort();

        logger.info("Attempting to register node with id {}", cacheNodeId);

        if (registryState.exists(cacheNodeId)) {
            logger.warn("Node with id {} already exists", cacheNodeId);
            throw new NodeAlreadyRegisteredException("Node already exists: " + cacheNodeId);
        }

        CacheNodeInfo cacheNodeInfo = new CacheNodeInfo(host, port);
        registryState.save(cacheNodeId, cacheNodeInfo);

        logger.info("Node with id {} registered successfully", cacheNodeId);
    }



    @Override
    public RegistryResponse getRegistryState() {
         long registryVersion = registryState.getVersion();
        Map<String, CacheNodeInfo> activeNodes = registryState.getActiveNodes();

        logger.info("Getting registry version {} and  activeList", registryVersion);

        List<ActiveNodeResponse> responseNodes =
                activeNodes.entrySet()
                        .stream()
                        .map(entry -> new ActiveNodeResponse(
                                entry.getKey(),                      // cacheNodeId
                                entry.getValue().getHost(),          // host
                                entry.getValue().getPort()))         // port
                        .toList();

        return new RegistryResponse(registryVersion, responseNodes);

    }



    public void processHeartbeat(String cacheNodeId) {
        CacheNodeInfo node = registryState.getCache(cacheNodeId);

        if (node != null) {
            node.setLastHeartBeatTime(System.currentTimeMillis());
            logger.info("Heartbeat updated for node: {}", cacheNodeId);
        }
    }

    @Override
    public void deregister(String cacheNodeId) {

        logger.info("Attempting to deregister node with id {}", cacheNodeId);

        registryState.removeIfPresent(cacheNodeId);

        logger.info("Node with id {} deregistered successfully", cacheNodeId);
    }

}
