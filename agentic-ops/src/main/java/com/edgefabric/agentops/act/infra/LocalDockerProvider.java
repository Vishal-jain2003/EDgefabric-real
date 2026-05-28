package com.edgefabric.agentops.act.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Docker-based InfrastructureProvider for local development.
 * Active under Spring profile "docker".
 * Stub implementation — Phase 1 does not execute real docker commands.
 */
@Slf4j
@Component
@Profile("docker")
public class LocalDockerProvider implements InfrastructureProvider {

    @Override
    public String provisionNode(NodeSpec spec) {
        String instanceId = "docker-" + spec.nodeType() + "-" + System.currentTimeMillis();
        log.info("[LocalDocker] provisionNode: {} -> instanceId={}", spec, instanceId);
        return instanceId;
    }

    @Override
    public void terminateNode(String instanceId) {
        log.info("[LocalDocker] terminateNode: instanceId={}", instanceId);
    }

    @Override
    public NodeProvisionStatus getProvisionStatus(String instanceId) {
        log.info("[LocalDocker] getProvisionStatus: instanceId={}", instanceId);
        return NodeProvisionStatus.RUNNING;
    }
}
