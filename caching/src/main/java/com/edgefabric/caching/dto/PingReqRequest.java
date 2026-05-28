package com.edgefabric.caching.dto;

import com.edgefabric.caching.model.NodeInfo;

public record PingReqRequest(NodeInfo targetNode, long timeoutMs) {
}

