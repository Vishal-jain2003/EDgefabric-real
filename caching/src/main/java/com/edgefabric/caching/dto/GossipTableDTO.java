package com.edgefabric.caching.dto;

import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /internal/cluster/gossip}.
 *
 * <p>Contains the complete gossip membership table plus summary counters so
 * you can tell at a glance whether the protocol is healthy.</p>
 */
@Getter
public class GossipTableDTO {

    /** ISO-8601 timestamp of when this snapshot was taken. */
    private final String snapshotTime;

    /** Total number of entries in the membership table (ALIVE + DRAINING + SUSPECT + DEAD). */
    private final int totalNodes;

    private final long aliveCount;
    private final long drainingCount;
    private final long suspectCount;
    private final long deadCount;

    /** The full membership table — one entry per known node. */
    private final List<GossipMemberDTO> members;

    public GossipTableDTO(List<GossipMemberDTO> members) {
        this.snapshotTime  = Instant.now().toString();
        this.members       = List.copyOf(members);
        this.totalNodes    = members.size();
        this.aliveCount    = members.stream().filter(m -> "ALIVE".equals(m.getStatus())).count();
        this.drainingCount = members.stream().filter(m -> "DRAINING".equals(m.getStatus())).count();
        this.suspectCount  = members.stream().filter(m -> "SUSPECT".equals(m.getStatus())).count();
        this.deadCount     = members.stream().filter(m -> "DEAD".equals(m.getStatus())).count();
    }
}

