package com.edgefabric.agentops.act.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * No-op InfrastructureProvider for tests. Active under Spring profile "test".
 * All operations succeed immediately with no side effects.
 */
@Slf4j
@Component
@Profile("test")
public class DryRunProvider implements InfrastructureProvider {

    @Override
    public String provisionNode(NodeSpec spec) {
        String instanceId = "dry-run-" + spec.nodeType() + "-" + System.currentTimeMillis();
        log.info("[DryRun] provisionNode: {} -> instanceId={}", spec, instanceId);
        return instanceId;
    }

    @Override
    public void terminateNode(String instanceId) {
        log.info("[DryRun] terminateNode: instanceId={}", instanceId);
    }

    @Override
    public NodeProvisionStatus getProvisionStatus(String instanceId) {
        log.info("[DryRun] getProvisionStatus: instanceId={}", instanceId);
        return NodeProvisionStatus.RUNNING;
    }
}
