"""
Unit tests for edgefabric_explain_server.py

Tests each MCP tool's diagnosis logic against mocked Observe API responses.
Covers all 4 failure modes, healthy state, partial input, and API errors.
"""

import json
import pytest
from unittest.mock import AsyncMock, patch

# Import the diagnosis functions directly (no server startup needed)
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))

from edgefabric_explain_server import (
    diagnose_snapshot,
    diagnose_node,
    diagnose_slo,
    diagnose_latency,
    diagnose_self_healing,
    FAILURE_MODES,
    observe_get,
)


# ── Fixtures ──────────────────────────────────────────────────────────────────

HEALTHY_SNAPSHOT = {
    "totalNodes": 5,
    "healthyNodes": 5,
    "suspectNodes": 0,
    "deadNodes": 0,
    "snapshotTakenAt": "2026-01-15T10:30:00Z",
    "avgHitRate": 0.95,
    "totalClusterHits": 50000,
    "totalClusterMisses": 2500,
    "sloAvailabilityRatio": 0.9999,
    "sloBurnRate1h": 0.3,
    "errorRate5m": 0.001,
    "nodes": [
        {
            "nodeId": "node-1",
            "host": "10.0.1.1",
            "port": 8082,
            "status": "HEALTHY",
            "lbReachable": True,
            "hitRate": 0.96,
            "missRate": 0.04,
            "totalHits": 10000,
            "totalMisses": 500,
            "cacheSize": 5000,
            "memorySizeBytes": 536870912,
            "memoryUsedRatio": 0.65,
            "evictionsPerMin": 5.0,
            "p50LatencyMs": 2.1,
            "p99LatencyMs": 15.3,
            "drainActive": False,
            "selfHealingAttempts": 0,
            "antiEntropyRepairs": 2,
            "migrationQueueSize": 0,
            "gossipStatus": "ALIVE",
            "gossipHeartbeat": 1705312200,
            "secondsSinceUpdate": 5,
            "incarnation": 42,
            "clusterAliveCount": 5,
            "clusterSuspectCount": 0,
            "clusterDeadCount": 0,
        }
    ],
}

ISOLATED_NODE_SNAPSHOT = {
    **HEALTHY_SNAPSHOT,
    "healthyNodes": 3,
    "deadNodes": 2,
    "nodes": [
        {
            "nodeId": "node-2",
            "status": "DEAD",
            "lbReachable": False,
            "memoryUsedRatio": 0.5,
            "evictionsPerMin": 0,
            "migrationQueueSize": 0,
            "drainActive": False,
            "secondsSinceUpdate": 120,
            "clusterAliveCount": 3,
            "clusterSuspectCount": 0,
            "clusterDeadCount": 2,
        },
        {
            "nodeId": "node-3",
            "status": "UNREACHABLE",
            "lbReachable": False,
            "memoryUsedRatio": 0.4,
            "evictionsPerMin": 0,
            "migrationQueueSize": 0,
            "drainActive": False,
            "secondsSinceUpdate": 90,
            "clusterAliveCount": 3,
            "clusterSuspectCount": 0,
            "clusterDeadCount": 2,
        },
    ],
}

MEMORY_PRESSURE_SNAPSHOT = {
    **HEALTHY_SNAPSHOT,
    "nodes": [
        {
            "nodeId": "node-1",
            "status": "HEALTHY",
            "lbReachable": True,
            "memoryUsedRatio": 0.92,
            "evictionsPerMin": 250.0,
            "migrationQueueSize": 0,
            "drainActive": False,
            "secondsSinceUpdate": 3,
            "clusterAliveCount": 5,
            "clusterSuspectCount": 0,
            "clusterDeadCount": 0,
        }
    ],
}

REBALANCING_SNAPSHOT = {
    **HEALTHY_SNAPSHOT,
    "nodes": [
        {
            "nodeId": "node-4",
            "status": "HEALTHY",
            "lbReachable": True,
            "memoryUsedRatio": 0.6,
            "evictionsPerMin": 10.0,
            "migrationQueueSize": 25,
            "drainActive": True,
            "secondsSinceUpdate": 2,
            "clusterAliveCount": 5,
            "clusterSuspectCount": 0,
            "clusterDeadCount": 0,
        }
    ],
}

QUORUM_FAILURE_SNAPSHOT = {
    "totalNodes": 3,
    "healthyNodes": 1,
    "suspectNodes": 0,
    "deadNodes": 2,
    "snapshotTakenAt": "2026-01-15T10:30:00Z",
    "avgHitRate": 0.3,
    "nodes": [
        {
            "nodeId": "node-1",
            "status": "HEALTHY",
            "clusterAliveCount": 1,
            "clusterDeadCount": 2,
            "memoryUsedRatio": 0.5,
            "evictionsPerMin": 0,
            "migrationQueueSize": 0,
            "drainActive": False,
            "lbReachable": True,
            "secondsSinceUpdate": 2,
        }
    ],
}


# ── Test: Healthy Cluster ─────────────────────────────────────────────────────

class TestHealthyCluster:
    def test_healthy_snapshot_returns_ok(self):
        result = diagnose_snapshot(HEALTHY_SNAPSHOT, "cluster_health")
        assert result["severity"] == "OK"
        assert result["failure_mode"] is None
        assert "healthy" in result["diagnosis_string"].lower()

    def test_healthy_has_required_fields(self):
        result = diagnose_snapshot(HEALTHY_SNAPSHOT, "cluster_health")
        assert "tool" in result
        assert "severity" in result
        assert "failure_mode" in result
        assert "root_cause" in result
        assert "evidence" in result
        assert "recommendations" in result
        assert "diagnosis_string" in result
        assert "snapshot_time" in result


# ── Test: Node Isolation ──────────────────────────────────────────────────────

class TestNodeIsolation:
    def test_detects_dead_nodes(self):
        result = diagnose_snapshot(ISOLATED_NODE_SNAPSHOT, "cluster_health")
        assert result["severity"] == "CRITICAL"
        assert result["failure_mode"] == "node_isolation"
        assert len(result["evidence"]) > 0

    def test_evidence_contains_node_info(self):
        result = diagnose_snapshot(ISOLATED_NODE_SNAPSHOT, "cluster_health")
        evidence_str = " ".join(result["evidence"])
        assert "node-2" in evidence_str or "DEAD" in evidence_str or "deadNodes" in evidence_str

    def test_recommendations_present(self):
        result = diagnose_snapshot(ISOLATED_NODE_SNAPSHOT, "cluster_health")
        assert len(result["recommendations"]) > 0
        assert any("gossip" in r.lower() or "network" in r.lower() for r in result["recommendations"])


# ── Test: Ring Rebalancing Lag ────────────────────────────────────────────────

class TestRingRebalancing:
    def test_detects_rebalancing(self):
        result = diagnose_snapshot(REBALANCING_SNAPSHOT, "cluster_health")
        assert result["severity"] == "WARNING"
        assert result["failure_mode"] == "ring_rebalancing_lag"

    def test_evidence_has_migration_queue(self):
        result = diagnose_snapshot(REBALANCING_SNAPSHOT, "cluster_health")
        evidence_str = " ".join(result["evidence"])
        assert "migrationQueueSize" in evidence_str or "drainActive" in evidence_str


# ── Test: Cache Memory Pressure ───────────────────────────────────────────────

class TestCacheMemoryPressure:
    def test_detects_memory_pressure(self):
        result = diagnose_snapshot(MEMORY_PRESSURE_SNAPSHOT, "cluster_health")
        assert result["severity"] == "WARNING"
        assert result["failure_mode"] == "cache_memory_pressure"

    def test_evidence_has_memory_fields(self):
        result = diagnose_snapshot(MEMORY_PRESSURE_SNAPSHOT, "cluster_health")
        evidence_str = " ".join(result["evidence"])
        assert "memoryUsedRatio" in evidence_str or "evictionsPerMin" in evidence_str


# ── Test: Quorum Failure ──────────────────────────────────────────────────────

class TestQuorumFailure:
    def test_detects_quorum_failure(self):
        result = diagnose_snapshot(QUORUM_FAILURE_SNAPSHOT, "cluster_health")
        assert result["severity"] == "CRITICAL"
        # Could be quorum_failure or node_isolation (both are CRITICAL)
        assert result["failure_mode"] in ("quorum_failure", "node_isolation")

    def test_urgent_recommendation(self):
        result = diagnose_snapshot(QUORUM_FAILURE_SNAPSHOT, "cluster_health")
        recs = " ".join(result["recommendations"]).lower()
        assert "urgent" in recs or "quorum" in recs or "recover" in recs or "network" in recs


# ── Test: Node Diagnosis ──────────────────────────────────────────────────────

class TestNodeDiagnosis:
    def test_healthy_node(self):
        node = HEALTHY_SNAPSHOT["nodes"][0]
        result = diagnose_node(node)
        assert result["severity"] == "OK"
        assert result["failure_mode"] is None

    def test_dead_node(self):
        node = ISOLATED_NODE_SNAPSHOT["nodes"][0]
        result = diagnose_node(node)
        assert result["severity"] == "CRITICAL"
        assert result["failure_mode"] == "node_isolation"
        assert result.get("affected_component") == "node-2"

    def test_memory_pressure_node(self):
        node = MEMORY_PRESSURE_SNAPSHOT["nodes"][0]
        result = diagnose_node(node)
        assert result["severity"] == "WARNING"
        assert result["failure_mode"] == "cache_memory_pressure"

    def test_rebalancing_node(self):
        node = REBALANCING_SNAPSHOT["nodes"][0]
        result = diagnose_node(node)
        assert result["severity"] == "WARNING"
        assert result["failure_mode"] == "ring_rebalancing_lag"


# ── Test: SLO Diagnosis ───────────────────────────────────────────────────────

class TestSloDiagnosis:
    def test_healthy_slo(self):
        data = {"sloAvailabilityRatio": 0.9999, "sloBurnRate1h": 0.3, "errorRate5m": 0.001, "healthVerdict": "OK"}
        result = diagnose_slo(data)
        assert result["severity"] == "OK"

    def test_burn_rate_critical(self):
        data = {"sloAvailabilityRatio": 0.995, "sloBurnRate1h": 2.5, "errorRate5m": 0.05, "healthVerdict": "CRITICAL"}
        result = diagnose_slo(data)
        assert result["severity"] == "CRITICAL"
        assert "burn rate" in result["root_cause"].lower()

    def test_low_availability_warning(self):
        data = {"sloAvailabilityRatio": 0.998, "sloBurnRate1h": 0.8, "errorRate5m": 0.005}
        result = diagnose_slo(data)
        assert result["severity"] == "WARNING"


# ── Test: Latency Diagnosis ───────────────────────────────────────────────────

class TestLatencyDiagnosis:
    def test_healthy_latency(self):
        data = [
            {"nodeId": "node-1", "p99LatencyMs": 15.0, "tier": "FAST"},
            {"nodeId": "node-2", "p99LatencyMs": 45.0, "tier": "OK"},
        ]
        result = diagnose_latency(data)
        assert result["severity"] == "OK"

    def test_critical_latency(self):
        data = [
            {"nodeId": "node-1", "p99LatencyMs": 15.0, "tier": "FAST"},
            {"nodeId": "node-3", "p99LatencyMs": 800.0, "tier": "CRITICAL"},
        ]
        result = diagnose_latency(data)
        assert result["severity"] == "CRITICAL"
        assert "node-3" in result["diagnosis_string"]

    def test_slow_latency(self):
        data = [{"nodeId": "node-2", "p99LatencyMs": 300.0, "tier": "SLOW"}]
        result = diagnose_latency(data)
        assert result["severity"] == "WARNING"

    def test_node_id_filter(self):
        data = [
            {"nodeId": "node-1", "p99LatencyMs": 800.0, "tier": "CRITICAL"},
            {"nodeId": "node-2", "p99LatencyMs": 10.0, "tier": "FAST"},
        ]
        result = diagnose_latency(data, node_id="node-2")
        assert result["severity"] == "OK"


# ── Test: Self-Healing Diagnosis ──────────────────────────────────────────────

class TestSelfHealingDiagnosis:
    def test_normal_state(self):
        data = {"antiEntropyRepairs": 5, "migrationQueueSize": 2, "drainActive": False}
        result = diagnose_self_healing(data)
        assert result["severity"] == "OK"

    def test_active_drain_with_large_queue(self):
        data = {"antiEntropyRepairs": 10, "migrationQueueSize": 20, "drainActive": True}
        result = diagnose_self_healing(data)
        assert result["severity"] == "WARNING"
        assert result["failure_mode"] == "ring_rebalancing_lag"

    def test_high_repairs(self):
        data = {"antiEntropyRepairs": 100, "migrationQueueSize": 3, "drainActive": False}
        result = diagnose_self_healing(data)
        assert result["severity"] == "WARNING"


# ── Test: Graceful Partial Input (AC6) ────────────────────────────────────────

class TestGracefulPartialInput:
    def test_empty_snapshot(self):
        result = diagnose_snapshot({}, "cluster_health")
        assert result["severity"] == "OK"
        assert "data_unavailable" in str(result["evidence"])

    def test_missing_nodes(self):
        data = {"totalNodes": 3, "healthyNodes": 3, "deadNodes": 0}
        result = diagnose_snapshot(data, "cluster_health")
        assert result["severity"] == "OK"

    def test_partial_node(self):
        node = {"nodeId": "node-x", "status": "HEALTHY"}
        result = diagnose_node(node)
        assert result["severity"] == "OK"

    def test_empty_slo(self):
        result = diagnose_slo({})
        assert "data_unavailable" in str(result["evidence"])

    def test_empty_latency_list(self):
        result = diagnose_latency([])
        assert result["severity"] == "OK"


# ── Test: API Error Handling ──────────────────────────────────────────────────

class TestApiErrorHandling:
    def test_error_in_snapshot(self):
        data = {"_error": "Observe API unreachable at http://localhost:8090"}
        result = diagnose_snapshot(data, "cluster_health")
        assert result["severity"] == "CRITICAL"
        assert "unreachable" in result["root_cause"].lower()

    def test_error_in_node(self):
        data = {"_error": "Observe API returned 404"}
        result = diagnose_node(data)
        assert result["severity"] == "CRITICAL"


# ── Test: Failure Mode Config is Data-Driven (AC8) ────────────────────────────

class TestDataDrivenConfig:
    def test_all_four_modes_exist(self):
        assert "node_isolation" in FAILURE_MODES
        assert "ring_rebalancing_lag" in FAILURE_MODES
        assert "cache_memory_pressure" in FAILURE_MODES
        assert "quorum_failure" in FAILURE_MODES

    def test_each_mode_has_required_keys(self):
        for mode_name, config in FAILURE_MODES.items():
            assert "description" in config, f"{mode_name} missing description"
            assert "triggers" in config, f"{mode_name} missing triggers"
            assert "severity" in config, f"{mode_name} missing severity"
            assert "recommendations" in config, f"{mode_name} missing recommendations"
            assert len(config["triggers"]) > 0, f"{mode_name} has no triggers"
            assert len(config["recommendations"]) > 0, f"{mode_name} has no recommendations"

    def test_adding_new_mode_requires_no_code_change(self):
        """Verify we can add a new failure mode by just adding to the dict."""
        FAILURE_MODES["test_mode"] = {
            "description": "Test failure mode",
            "triggers": [{"field": "testField", "op": ">", "threshold": 0}],
            "severity": "INFO",
            "recommendations": ["Test recommendation"],
        }
        # Verify it's picked up by diagnosis engine
        data = {"testField": 5, "nodes": []}
        result = diagnose_snapshot(data, "test")
        assert result["failure_mode"] == "test_mode"

        # Cleanup
        del FAILURE_MODES["test_mode"]


# ── Test: Output Schema (AC2) ─────────────────────────────────────────────────

class TestOutputSchema:
    """Verify all tool outputs contain the required AC2 fields."""

    REQUIRED_FIELDS = {"severity", "failure_mode", "root_cause", "evidence", "recommendations"}

    def test_cluster_health_schema(self):
        result = diagnose_snapshot(HEALTHY_SNAPSHOT, "cluster_health")
        assert self.REQUIRED_FIELDS.issubset(result.keys())

    def test_node_anomaly_schema(self):
        result = diagnose_node(HEALTHY_SNAPSHOT["nodes"][0])
        assert self.REQUIRED_FIELDS.issubset(result.keys())

    def test_slo_schema(self):
        result = diagnose_slo({"sloAvailabilityRatio": 0.999, "sloBurnRate1h": 0.5, "errorRate5m": 0.001})
        assert self.REQUIRED_FIELDS.issubset(result.keys())

    def test_latency_schema(self):
        result = diagnose_latency([{"nodeId": "n1", "p99LatencyMs": 10, "tier": "FAST"}])
        assert self.REQUIRED_FIELDS.issubset(result.keys())

    def test_self_healing_schema(self):
        result = diagnose_self_healing({"antiEntropyRepairs": 5, "migrationQueueSize": 2, "drainActive": False})
        assert self.REQUIRED_FIELDS.issubset(result.keys())

    def test_diagnosis_string_always_present(self):
        for data, func in [
            (HEALTHY_SNAPSHOT, lambda d: diagnose_snapshot(d, "test")),
            (ISOLATED_NODE_SNAPSHOT["nodes"][0], diagnose_node),
        ]:
            result = func(data)
            assert "diagnosis_string" in result
            assert isinstance(result["diagnosis_string"], str)
            assert len(result["diagnosis_string"]) > 0


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
