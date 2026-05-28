package com.edgefabric.caching.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.concurrent.atomic.AtomicReference;

@Getter
@EqualsAndHashCode(of = "cacheNodeId")
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeInfo {
    private final String cacheNodeId;
    private final String host;
    private final int servicePort;
    private final int gossipPort;

    @JsonIgnore
    private final AtomicReference<NodeState> state;

    public NodeInfo(String cacheNodeId, String host, int servicePort, int gossipPort) {
        if (cacheNodeId == null || cacheNodeId.isBlank()) {
            throw new IllegalArgumentException("cacheNodeId must not be null or blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }

        this.cacheNodeId = cacheNodeId;
        this.host = host;
        this.servicePort = servicePort;
        this.gossipPort = gossipPort;

        this.state = new AtomicReference<>(
                new NodeState(Status.ALIVE, 0, 0, System.currentTimeMillis())
        );
    }

    public static NodeInfo getInstance(String cacheNodeId, String host,
                                       int servicePort, int gossipPort,
                                       Status status, long heartbeat,
                                       long incarnation) {

        NodeInfo node = new NodeInfo(cacheNodeId, host, servicePort, gossipPort);

        node.state.set(new NodeState(
                status,
                heartbeat,
                incarnation,
                System.currentTimeMillis()
        ));

        return node;
    }

    @JsonCreator
    public static NodeInfo fromJson(
            @JsonProperty("cacheNodeId") String cacheNodeId,
            @JsonProperty("host") String host,
            @JsonProperty("servicePort") int servicePort,
            @JsonProperty("gossipPort") int gossipPort,
            @JsonProperty("status") Status status,
            @JsonProperty("heartbeat") long heartbeat,
            @JsonProperty("incarnation") long incarnation) {
        return getInstance(cacheNodeId, host, servicePort, gossipPort,
                status != null ? status : Status.ALIVE, heartbeat, incarnation);
    }

    // ── Convenience delegates (read-through to current NodeState) ──

    public Status getStatus() {
        return state.get().status();
    }

    public long getHeartbeat() {
        return state.get().heartbeat();
    }

    public long getIncarnation() {
        return state.get().incarnation();
    }

    public long getLastUpdatedTime() {
        return state.get().lastUpdatedTime();
    }

    // ── Convenience setters (CAS-loop, lock-free) ──

    public void setStatus(Status status) {
        while (true) {
            NodeState old = state.get();
            NodeState updated = new NodeState(
                    status, old.heartbeat(), old.incarnation(), System.currentTimeMillis());
            if (state.compareAndSet(old, updated)) return;
        }
    }

    public void setIncarnation(long incarnation) {
        while (true) {
            NodeState old = state.get();
            NodeState updated = new NodeState(
                    old.status(), old.heartbeat(), incarnation, System.currentTimeMillis());
            if (state.compareAndSet(old, updated)) return;
        }
    }

    // ── Snapshots & mutations ──

    public NodeState snapshot() {
        return state.get();
    }

    public long bumpHeartbeat() {
        while (true) {
            NodeState old = state.get();

            NodeState updated = new NodeState(
                    old.status,
                    old.heartbeat + 1,
                    old.incarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return updated.heartbeat;
            }
        }
    }

    public long refute(long incomingIncarnation) {
        while (true) {
            NodeState old = state.get();

            long newIncarnation = Math.max(old.incarnation, incomingIncarnation) + 1;

            Status refutedStatus = old.status == Status.DRAINING ? Status.DRAINING : Status.ALIVE;

            NodeState updated = new NodeState(
                    refutedStatus,
                    old.heartbeat,
                    newIncarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return newIncarnation;
            }
        }
    }

    public boolean transitionToAlive() {
        while (true) {
            NodeState old = state.get();

            if (old.status == Status.ALIVE) return false;

            NodeState updated = new NodeState(
                    Status.ALIVE,
                    old.heartbeat,
                    old.incarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return true;
            }
        }
    }

    public boolean transitionToDraining() {
        while (true) {
            NodeState old = state.get();

            if (old.status != Status.ALIVE) return false;

            NodeState updated = new NodeState(
                    Status.DRAINING,
                    old.heartbeat,
                    old.incarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return true;
            }
        }
    }

    public boolean transitionFromDrainingToAlive() {
        while (true) {
            NodeState old = state.get();

            if (old.status != Status.DRAINING) return false;

            // Bump incarnation so peers accept ALIVE over DRAINING
            // (merger only accepts lower-severity at higher incarnation)
            NodeState updated = new NodeState(
                    Status.ALIVE,
                    old.heartbeat,
                    old.incarnation + 1,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return true;
            }
        }
    }

    public boolean transitionToSuspect() {
        while (true) {
            NodeState old = state.get();

            if (old.status != Status.ALIVE) return false;

            NodeState updated = new NodeState(
                    Status.SUSPECT,
                    old.heartbeat,
                    old.incarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return true;
            }
        }
    }

    public boolean transitionToDead() {
        while (true) {
            NodeState old = state.get();

            if (old.status == Status.DEAD) return false;

            NodeState updated = new NodeState(
                    Status.DEAD,
                    old.heartbeat,
                    old.incarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return true;
            }
        }
    }

    public void applyUpdate(Status status, long heartbeat, long incarnation) {
        while (true) {
            NodeState old = state.get();

            NodeState updated = new NodeState(
                    status,
                    heartbeat,
                    incarnation,
                    System.currentTimeMillis()
            );

            if (state.compareAndSet(old, updated)) {
                return;
            }
        }
    }

    public record NodeState(Status status, long heartbeat, long incarnation, long lastUpdatedTime) {
    }
}