package com.edgefabric.caching.model;

public enum Status {
    ALIVE,
    DRAINING,
    SUSPECT,
    DEAD;

    public int severity() {
        return switch (this) {
            case ALIVE -> 0;
            case DRAINING -> 1;
            case SUSPECT -> 2;
            case DEAD -> 3;
        };
    }
}
