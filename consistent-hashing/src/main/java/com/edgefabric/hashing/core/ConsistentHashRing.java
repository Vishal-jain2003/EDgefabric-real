package com.edgefabric.hashing.core;

import com.edgefabric.hashing.api.HashableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.StampedLock;


public class ConsistentHashRing<T extends HashableNode> {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRing.class);

    private final HashProvider hashProvider;
    private final int virtualNodes;


    private final ConcurrentSkipListMap<Long, T> ring = new ConcurrentSkipListMap<>();

    // Use ConcurrentHashMap.newKeySet() for thread-safe Set without ConcurrentModificationException
    // Fixes race condition where optimistic read could iterate HashSet while writer modifies it
    private final Set<String> activeNodeIds = ConcurrentHashMap.newKeySet();

    private final StampedLock lock = new StampedLock();


    public ConsistentHashRing(HashProvider hashProvider, int virtualNodes) {
        if (hashProvider == null) {
            throw new IllegalArgumentException("hashProvider must not be null");
        }
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException(
                    "virtualNodes must be > 0, got: " + virtualNodes);
        }
        this.hashProvider = hashProvider;
        this.virtualNodes = virtualNodes;

        log.info("ConsistentHashRing created. Algorithm: {}, VirtualNodes per physical node: {}",
                hashProvider.getClass().getSimpleName(), virtualNodes);
    }


    public void addNode(T node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }


        long stamp = lock.writeLock();
        try {
            if (activeNodeIds.contains(node.getNodeId())) {

                log.debug("Node '{}' already in ring. addNode skipped.", node.getNodeId());
                return;
            }

            for (int i = 0; i < virtualNodes; i++) {

                long hash = hashProvider.generateHash(node.getNodeId() + "#" + i);
                ring.put(hash, node);
            }

            activeNodeIds.add(node.getNodeId());

            log.info("Node added: '{}'. Virtual positions: {}. Total ring positions: {}. Physical nodes: {}.",
                    node.getNodeId(), virtualNodes, ring.size(), activeNodeIds.size());

        } finally {

            lock.unlockWrite(stamp);
        }
    }


    public void removeNode(T node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }

        long stamp = lock.writeLock();
        try {
            if (!activeNodeIds.contains(node.getNodeId())) {
                log.debug("Node '{}' not in ring. removeNode skipped.", node.getNodeId());
                return;
            }

            for (int i = 0; i < virtualNodes; i++) {
                long hash = hashProvider.generateHash(node.getNodeId() + "#" + i);

                ring.remove(hash);
            }

            activeNodeIds.remove(node.getNodeId());

            log.info("Node removed: '{}'. Total ring positions: {}. Physical nodes: {}.",
                    node.getNodeId(), ring.size(), activeNodeIds.size());

        } finally {
            lock.unlockWrite(stamp);
        }
    }


    public T getNode(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }


        long stamp = lock.tryOptimisticRead();
        T result = lookupNode(key);

        if (!lock.validate(stamp)) {
            // Writer appeared during our read. Retry with real read lock.
            stamp = lock.readLock();
            try {
                result = lookupNode(key);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return result;
    }


    /**
     * Returns up to {@code count} distinct physical nodes responsible for the given key.
     * Walks the ring clockwise from the key's hash, skipping virtual-node duplicates.
     * If fewer than {@code count} distinct physical nodes exist, returns all available nodes.
     *
     * @param key   the routing key
     * @param count the desired number of replica nodes
     * @return an ordered list of distinct physical nodes (primary first)
     */
    public List<T> getNodes(String key, int count) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0, got: " + count);
        }

        long stamp = lock.tryOptimisticRead();
        List<T> result = lookupNodes(key, count);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                result = lookupNodes(key, count);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return result;
    }

    /**
     * Internal: walks the ring clockwise collecting distinct physical nodes.
     */
    private List<T> lookupNodes(String key, int count) {
        if (ring.isEmpty()) {
            return new ArrayList<>();
        }

        long keyHash = hashProvider.generateHash(key);
        int physicalNodeCount = activeNodeIds.size();
        int needed = Math.min(count, physicalNodeCount);

        List<T> result = new ArrayList<>(needed);
        Set<String> seen = new HashSet<>();

        // Walk from keyHash to end of ring
        SortedMap<Long, T> tail = ring.tailMap(keyHash);
        for (T node : tail.values()) {
            if (seen.add(node.getNodeId())) {
                result.add(node);
                if (result.size() >= needed) return result;
            }
        }

        // Wrap around from beginning of ring
        for (T node : ring.values()) {
            if (seen.add(node.getNodeId())) {
                result.add(node);
                if (result.size() >= needed) return result;
            }
        }

        return result;
    }

    private T lookupNode(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException(
                    "No nodes in ring. Call addNode() before routing keys.");
        }

        long keyHash = hashProvider.generateHash(key);


        SortedMap<Long, T> tail = ring.tailMap(keyHash);
        Long nodeHash = tail.isEmpty() ? ring.firstKey() : tail.firstKey();

        return ring.get(nodeHash);
    }


    public int size() {
        long stamp = lock.tryOptimisticRead();
        int size = ring.size();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { size = ring.size(); }
            finally { lock.unlockRead(stamp); }
        }
        return size;

    }


    public int nodeCount() {
        long stamp = lock.tryOptimisticRead();
        int count = activeNodeIds.size();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { count = activeNodeIds.size(); }
            finally { lock.unlockRead(stamp); }
        }
        return count;
    }


    public boolean isEmpty() {
        long stamp = lock.tryOptimisticRead();
        boolean empty = ring.isEmpty();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { empty = ring.isEmpty(); }
            finally { lock.unlockRead(stamp); }
        }
        return empty;
    }


    public Set<String> getActiveNodeIds() {
        long stamp = lock.tryOptimisticRead();
        Set<String> snapshot = Set.copyOf(activeNodeIds);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { snapshot = Set.copyOf(activeNodeIds); }
            finally { lock.unlockRead(stamp); }
        }
        return snapshot;
    }
}