package com.edgefabric.caching.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NodeInfoDTO {
    private final String cacheNodeId;
    private final String host;

    @JsonProperty("port")
    private final int servicePort;
}
