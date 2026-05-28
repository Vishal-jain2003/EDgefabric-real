package com.edgefabric.agentops.act.infra;

/**
 * Abstraction over infrastructure operations.
 * Profile "test" binds DryRunProvider; Profile "docker" binds LocalDockerProvider;
 * default (no profile or "aws") binds AwsEc2Provider.
 */
public interface InfrastructureProvider {

    /**
     * Provision a new node according to the given spec.
     *
     * @param spec the node specification
     * @return an instance ID or resource identifier
     */
    String provisionNode(NodeSpec spec);

    /**
     * Terminate an existing node.
     *
     * @param instanceId the infrastructure instance ID to terminate
     */
    void terminateNode(String instanceId);

    /**
     * Get the current provision status of a node.
     *
     * @param instanceId the infrastructure instance ID
     * @return the current provision status
     */
    NodeProvisionStatus getProvisionStatus(String instanceId);
}
