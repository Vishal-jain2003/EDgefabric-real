package com.edgefabric.agentops.act;

public enum RollbackStrategy {
    UNDO,
    COMPENSATE,
    RESTORE_SNAPSHOT,
    MANUAL
}
