package com.edgefabric.loadbalancer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Request DTO for {@code POST /api/v1/internal/wal/append}.
 *
 * <p>Sent by a cache node after a bypass write to journal the operation in the
 * load balancer's WAL, enabling the anti-entropy self-healer to repair missed
 * writes on peer nodes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalAppendRequest {

    /** The cache key that was written. */
    @NotBlank(message = "key must not be blank")
    private String key;

    /** Base64-encoded payload bytes. */
    private String dataBase64;

    /** Absolute expiry time in epoch milliseconds. */
    private long expiresAt;

    /** MIME content type of the payload. */
    private String contentType;

    /** Quorum version from the original write. */
    private long version;

    /** Cache node ID that performed the bypass write. */
    private String originatorNodeId;
}
