package com.edgefabric.agentops.act;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
public class AgentAction {

    private final String id;
    private final ActionType type;
    private final String target;
    private ActionStatus status;
    private final String reasoning;
    private final List<String> plan;
    private final String rollbackHint;
    private final String clusterSnapshotRef;
    private final Instant proposedAt;
    private final Instant expiresAt;
    @Setter private String approvedBy;
    @Setter private Instant decidedAt;
    @Setter private String rejectionReason;
    @Setter private String comment;

    public void updateStatus(ActionStatus newStatus) {
        this.status = newStatus;
    }
}
