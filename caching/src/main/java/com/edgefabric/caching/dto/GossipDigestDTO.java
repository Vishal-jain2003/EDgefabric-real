package com.edgefabric.caching.dto;

import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GossipDigestDTO {
    String senderNodeId;

    /**
     * The sender's own heartbeat at the moment the packet was built.
     * All entry heartbeats on the wire are stored as
     * {@code (entry.heartbeat - senderHeartbeat)}, so the absolute value
     * is reconstructed as {@code senderHeartbeat + delta} on the receiver side.
     * Defaults to 0 when constructed via JSON (legacy path or tests).
     */
    @Builder.Default
    long senderHeartbeat = 0;

    List<GossipNodeEntry> entries;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GossipNodeEntry {
        String cacheNodeId;

        /**
         * {@code null} for DELTA entries (host/port omitted from the wire).
         * Non-null for FULL entries that carry complete network coordinates.
         */
        String host;
        int servicePort;
        int gossipPort;
        String status;
        long incarnation;
        long heartbeat;

        // ── Entry-type helpers ────────────────────────────────────────────

        /**
         * Returns {@code true} when this entry carries full network coordinates
         * (host + ports). Used by the codec and the receiver to decide how to
         * reconstruct a {@link NodeInfo}.
         */
        public boolean isFullEntry() {
            return host != null;
        }

        // ── Factory methods ───────────────────────────────────────────────

        /**
         * Creates a <b>FULL</b> entry that includes host and port information.
         * Used for nodes that may not yet be known to the receiver (heartbeat
         * below {@code GossipMessageCodec.FULL_ENTRY_HEARTBEAT_THRESHOLD}).
         */
        public static GossipNodeEntry fromNodeInfo(NodeInfo nodeInfo) {
            return GossipNodeEntry.builder()
                    .cacheNodeId(nodeInfo.getCacheNodeId())
                    .host(nodeInfo.getHost())
                    .servicePort(nodeInfo.getServicePort())
                    .gossipPort(nodeInfo.getGossipPort())
                    .status(nodeInfo.getStatus().name())
                    .incarnation(nodeInfo.getIncarnation())
                    .heartbeat(nodeInfo.getHeartbeat())
                    .build();
        }

        /**
         * Creates a <b>DELTA</b> entry that carries only the node's ID and
         * mutable state (status, incarnation, heartbeat). Host and ports are
         * omitted from the wire because the receiver can look them up in its
         * own membership list. Used for well-established nodes whose coordinates
         * are already known cluster-wide.
         */
        public static GossipNodeEntry fromNodeInfoDelta(NodeInfo nodeInfo) {
            return GossipNodeEntry.builder()
                    .cacheNodeId(nodeInfo.getCacheNodeId())
                    // host intentionally null — signals DELTA to the codec
                    .status(nodeInfo.getStatus().name())
                    .incarnation(nodeInfo.getIncarnation())
                    .heartbeat(nodeInfo.getHeartbeat())
                    .build();
        }

        // ── NodeInfo reconstruction ───────────────────────────────────────

        /**
         * Converts this entry to a {@link NodeInfo}.
         * <b>Only valid for FULL entries</b> ({@code host != null}).
         * For DELTA entries use {@link #toNodeInfo(NodeInfo)}.
         *
         * @throws IllegalStateException if called on a DELTA entry
         */
        public NodeInfo toNodeInfo() {
            if (host == null) {
                throw new IllegalStateException(
                        "toNodeInfo() called on a DELTA entry for node '" + cacheNodeId +
                        "'. Use toNodeInfo(NodeInfo existing) to reconstruct from existing coordinates.");
            }
            return NodeInfo.getInstance(
                    cacheNodeId, host, servicePort, gossipPort,
                    Status.valueOf(status), heartbeat, incarnation);
        }

        /**
         * Reconstructs a {@link NodeInfo} for a DELTA entry by combining the
         * incoming mutable state with the network coordinates from
         * {@code existing} (which was looked up from the local membership list).
         */
        public NodeInfo toNodeInfo(NodeInfo existing) {
            return NodeInfo.getInstance(
                    existing.getCacheNodeId(),
                    existing.getHost(),
                    existing.getServicePort(),
                    existing.getGossipPort(),
                    Status.valueOf(status),
                    heartbeat,
                    incarnation);
        }
    }
}
