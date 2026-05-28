package com.edgefabric.caching.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO sent from the cache node to the load-balancer WAL append endpoint.
 * Uses Jackson annotations for proper serialization/deserialization.
 */
public class WalAppendRequest {

    private final String key;
    private final String dataBase64;
    private final long expiresAt;
    private final String contentType;
    private final long version;
    private final String originatorNodeId;

    @JsonCreator
    public WalAppendRequest(
            @JsonProperty("key") String key,
            @JsonProperty("dataBase64") String dataBase64,
            @JsonProperty("expiresAt") long expiresAt,
            @JsonProperty("contentType") String contentType,
            @JsonProperty("version") long version,
            @JsonProperty("originatorNodeId") String originatorNodeId) {
        this.key = key;
        this.dataBase64 = dataBase64;
        this.expiresAt = expiresAt;
        this.contentType = contentType;
        this.version = version;
        this.originatorNodeId = originatorNodeId;
    }

    @JsonProperty("key")
    public String getKey() { return key; }

    @JsonProperty("dataBase64")
    public String getDataBase64() { return dataBase64; }

    @JsonProperty("expiresAt")
    public long getExpiresAt() { return expiresAt; }

    @JsonProperty("contentType")
    public String getContentType() { return contentType; }

    @JsonProperty("version")
    public long getVersion() { return version; }

    @JsonProperty("originatorNodeId")
    public String getOriginatorNodeId() { return originatorNodeId; }
}
