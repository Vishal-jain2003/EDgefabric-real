package com.edgefabric.caching.exception;

/**
 * Thrown when a write request arrives at a cache node without the
 * mandatory {@code X-Quorum-Version} header.
 *
 * <p>All writes must be coordinated through the load balancer's
 * {@code QuorumService}, which is the single source of truth for
 * version generation via {@code System.nanoTime()}.
 * Direct writes that bypass the quorum coordinator are rejected
 * to prevent version drift across replicas.
 */
public class MissingVersionException extends RuntimeException {

    public MissingVersionException(String key) {
        super("X-Quorum-Version header is required for key: '" + key
                + "'. All writes must go through the quorum coordinator.");
    }
}

