package com.edgefabric.loadbalancer.wal;

/**
 * Non-blocking WAL append port.
 * Implementations buffer entries and flush asynchronously to S3.
 */
public interface WalWriter {
    void append(WalEntry entry);

    /**
     * Returns the number of entries waiting to be flushed.
     */
    default int getPendingCount() {
        return 0;
    }

    /**
     * Replays the WAL entries chronologically for recovery.
     * @param handler A consumer that processes each recovered WAL entry.
     */
    default void replay(java.util.function.Consumer<WalEntry> handler) {
        // default no-op, to be implemented by specific writers
    }
}
