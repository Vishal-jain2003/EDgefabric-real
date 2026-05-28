package com.edgefabric.caching.service;

import com.edgefabric.caching.model.NodeInfo;

import java.time.Duration;

public interface FailureDetectionTransport {

    boolean sendPing(NodeInfo targetNode, Duration timeout);

    boolean sendPingReq(NodeInfo helperNode, NodeInfo targetNode, Duration timeout);
}

