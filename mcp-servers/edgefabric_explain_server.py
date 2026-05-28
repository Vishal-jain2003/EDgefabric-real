"""
EdgeFabric Explain MCP Server — Stage 2 of the Autonomous Ops Loop.

Interprets structured observation snapshots (from Observe API on port 8090)
and produces human-readable diagnoses with severity, root cause, evidence,
and recommendations.
"""

import asyncio
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import httpx
from dotenv import load_dotenv
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

OBSERVE_API_URL = os.getenv("OBSERVE_API_URL", "http://localhost:8090")

app = Server("edgefabric-explain")

# ── Data-Driven Failure Mode Configuration ────────────────────────────────────
# Adding a new failure mode requires only a new dict entry — no code changes.

FAILURE_MODES = {
    "node_isolation": {
        "description": "One or more nodes are unreachable/dead and isolated from the cluster",
        "triggers": [
            {"field": "deadNodes", "op": ">", "threshold": 0},
            {"field": "node.status", "op": "in", "values": ["DEAD", "UNREACHABLE"]},
            {"field": "node.lbReachable", "op": "==", "value": False},
        ],
        "severity": "CRITICAL",
        "recommendations": [
            "Check network connectivity and firewall rules for isolated nodes",
            "Verify gossip protocol on port 7946 is reachable",
            "Check if failure detector (port 7000) has marked nodes dead",
            "Consider manual node restart if gossip heartbeat stale > 60s",
        ],
    },
    "ring_rebalancing_lag": {
        "description": "Hash ring is rebalancing but migration queue is backed up",
        "triggers": [
            {"field": "node.migrationQueueSize", "op": ">", "threshold": 10},
            {"field": "node.drainActive", "op": "==", "value": True},
        ],
        "severity": "WARNING",
        "recommendations": [
            "Monitor migration queue size — should decrease over time",
            "Check if target nodes have sufficient memory for incoming keys",
            "Verify consistent-hashing ring has stabilized (no flapping nodes)",
            "Consider throttling client writes during rebalance",
        ],
    },
    "cache_memory_pressure": {
        "description": "Cache node memory usage is high causing excessive evictions",
        "triggers": [
            {"field": "node.memoryUsedRatio", "op": ">", "threshold": 0.85},
            {"field": "node.evictionsPerMin", "op": ">", "threshold": 100},
        ],
        "severity": "WARNING",
        "recommendations": [
            "Consider scaling out (adding cache nodes) to distribute load",
            "Review TTL policies — shorter TTLs reduce memory pressure",
            "Check for hot keys causing uneven distribution",
            "Monitor hit rate — high evictions may degrade hit rate",
        ],
    },
    "quorum_failure": {
        "description": "Insufficient healthy nodes to meet quorum requirements",
        "triggers": [
            {"field": "healthyNodes", "op": "<", "threshold": 2},
            {"field": "node.clusterAliveCount", "op": "<", "threshold": 2},
        ],
        "severity": "CRITICAL",
        "recommendations": [
            "URGENT: Quorum lost — writes and consistent reads will fail",
            "Immediately investigate and recover dead/suspect nodes",
            "Check if split-brain has occurred (multiple partitions)",
            "Consider emergency read-from-any mode if availability > consistency needed",
        ],
    },
}


# ── HTTP Helper ───────────────────────────────────────────────────────────────

async def observe_get(path: str) -> dict:
    """Fetch data from the Observe REST API. Returns parsed JSON or error dict."""
    url = f"{OBSERVE_API_URL}{path}"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            r = await client.get(url)
            r.raise_for_status()
            return r.json()
    except httpx.ConnectError:
        return {"_error": f"Observe API unreachable at {OBSERVE_API_URL}"}
    except httpx.HTTPStatusError as e:
        return {"_error": f"Observe API returned {e.response.status_code}: {e.response.text[:200]}"}
    except Exception as e:
        return {"_error": f"Unexpected error calling Observe API: {str(e)[:200]}"}


# ── Diagnosis Engine ──────────────────────────────────────────────────────────

def _safe_get(data: dict, field: str):
    """Safely navigate dotted field paths like 'node.status'. Returns None if missing."""
    parts = field.split(".")
    current = data
    for part in parts:
        if isinstance(current, dict):
            current = current.get(part)
        else:
            return None
        if current is None:
            return None
    return current


def _check_trigger(trigger: dict, data: dict) -> tuple[bool, str | None]:
    """Check if a single trigger condition is met. Returns (matched, evidence_string)."""
    field = trigger["field"]
    op = trigger["op"]
    value = _safe_get(data, field)

    if value is None:
        return False, None

    if op == ">" and isinstance(value, (int, float)):
        threshold = trigger["threshold"]
        if value > threshold:
            return True, f"{field}={value} (threshold: >{threshold})"
    elif op == "<" and isinstance(value, (int, float)):
        threshold = trigger["threshold"]
        if value < threshold:
            return True, f"{field}={value} (threshold: <{threshold})"
    elif op == "==" :
        expected = trigger["value"]
        if value == expected:
            return True, f"{field}={value}"
    elif op == "in":
        allowed = trigger["values"]
        if value in allowed:
            return True, f"{field}={value} (in {allowed})"

    return False, None


def _check_node_triggers(trigger: dict, nodes: list[dict]) -> tuple[bool, list[str]]:
    """Check a node-level trigger against all nodes. Returns (any_matched, evidence_list)."""
    evidence = []
    matched = False
    for node in nodes:
        node_data = {"node": node}
        hit, ev = _check_trigger(trigger, node_data)
        if hit:
            matched = True
            node_id = node.get("nodeId", "unknown")
            evidence.append(f"[{node_id}] {ev}")
    return matched, evidence


def diagnose_snapshot(data: dict, context: str = "cluster") -> dict:
    """
    Run all failure mode checks against observation data.
    Returns structured diagnosis with the highest-severity match.
    """
    if "_error" in data:
        return {
            "tool": f"explain_{context}",
            "severity": "CRITICAL",
            "failure_mode": None,
            "root_cause": f"Cannot diagnose: {data['_error']}",
            "evidence": ["Observe API unavailable or returned error"],
            "recommendations": [
                "Ensure agentic-ops module is running on port 8090",
                "Check network connectivity to Observe API",
            ],
            "diagnosis_string": f"Diagnosis unavailable — {data['_error']}",
            "snapshot_time": datetime.now(timezone.utc).isoformat(),
        }

    nodes = data.get("nodes", [])
    snapshot_time = data.get("snapshotTakenAt", datetime.now(timezone.utc).isoformat())

    matches: list[dict] = []

    for mode_name, mode_config in FAILURE_MODES.items():
        all_evidence = []
        any_trigger_hit = False

        for trigger in mode_config["triggers"]:
            field = trigger["field"]

            if field.startswith("node."):
                hit, ev_list = _check_node_triggers(trigger, nodes)
                if hit:
                    any_trigger_hit = True
                    all_evidence.extend(ev_list)
            else:
                hit, ev = _check_trigger(trigger, data)
                if hit:
                    any_trigger_hit = True
                    if ev:
                        all_evidence.append(ev)

        if any_trigger_hit:
            matches.append({
                "mode": mode_name,
                "severity": mode_config["severity"],
                "description": mode_config["description"],
                "evidence": all_evidence,
                "recommendations": mode_config["recommendations"],
            })

    # Pick highest-severity match (CRITICAL > WARNING > INFO)
    severity_order = {"CRITICAL": 0, "WARNING": 1, "INFO": 2}
    if matches:
        matches.sort(key=lambda m: severity_order.get(m["severity"], 99))
        best = matches[0]

        # Build human-readable diagnosis string
        evidence_summary = "; ".join(best["evidence"][:3])
        diagnosis_string = (
            f"{best['description']} — {evidence_summary} → {best['recommendations'][0]}"
        )

        return {
            "tool": f"explain_{context}",
            "severity": best["severity"],
            "failure_mode": best["mode"],
            "root_cause": best["description"],
            "evidence": best["evidence"],
            "recommendations": best["recommendations"],
            "diagnosis_string": diagnosis_string,
            "snapshot_time": snapshot_time,
            "additional_findings": [
                {"mode": m["mode"], "severity": m["severity"]}
                for m in matches[1:]
            ] if len(matches) > 1 else [],
        }

    # Healthy cluster
    healthy = data.get("healthyNodes", "data_unavailable")
    total = data.get("totalNodes", "data_unavailable")
    hit_rate = data.get("avgHitRate", "data_unavailable")

    return {
        "tool": f"explain_{context}",
        "severity": "OK",
        "failure_mode": None,
        "root_cause": "No anomalies detected — cluster is healthy",
        "evidence": [
            f"healthyNodes={healthy}/{total}",
            f"avgHitRate={hit_rate}",
            f"deadNodes={data.get('deadNodes', 0)}",
            f"suspectNodes={data.get('suspectNodes', 0)}",
        ],
        "recommendations": [],
        "diagnosis_string": f"Cluster healthy — {healthy}/{total} nodes operational, hit rate {hit_rate}",
        "snapshot_time": snapshot_time,
    }


def diagnose_node(data: dict) -> dict:
    """Diagnose a single node's health based on NodeSnapshot fields."""
    if "_error" in data:
        return diagnose_snapshot(data, context="node_anomaly")

    node_id = data.get("nodeId", "unknown")
    status = data.get("status", "data_unavailable")
    evidence = []
    issues = []

    # Check node status
    if status in ("DEAD", "UNREACHABLE"):
        issues.append("node_isolation")
        evidence.append(f"status={status}")

    # Check memory pressure
    mem_ratio = data.get("memoryUsedRatio")
    evictions = data.get("evictionsPerMin")
    if mem_ratio is not None and mem_ratio > 0.85:
        issues.append("cache_memory_pressure")
        evidence.append(f"memoryUsedRatio={mem_ratio:.2f}")
    if evictions is not None and evictions > 100:
        issues.append("cache_memory_pressure")
        evidence.append(f"evictionsPerMin={evictions}")

    # Check migration/drain
    migration_queue = data.get("migrationQueueSize")
    drain_active = data.get("drainActive")
    if migration_queue is not None and migration_queue > 10:
        issues.append("ring_rebalancing_lag")
        evidence.append(f"migrationQueueSize={migration_queue}")
    if drain_active:
        issues.append("ring_rebalancing_lag")
        evidence.append("drainActive=true")

    # Check gossip staleness
    seconds_since = data.get("secondsSinceUpdate")
    if seconds_since is not None and seconds_since > 60:
        issues.append("node_isolation")
        evidence.append(f"secondsSinceUpdate={seconds_since}s (stale)")

    # Check LB reachability
    if data.get("lbReachable") is False:
        issues.append("node_isolation")
        evidence.append("lbReachable=false")

    if not issues:
        hit_rate = data.get("hitRate", "data_unavailable")
        p99 = data.get("p99LatencyMs", "data_unavailable")
        return {
            "tool": "explain_node_anomaly",
            "severity": "OK",
            "failure_mode": None,
            "root_cause": f"Node {node_id} is healthy",
            "evidence": [f"status={status}", f"hitRate={hit_rate}", f"p99={p99}ms"],
            "recommendations": [],
            "diagnosis_string": f"Node {node_id} healthy — status={status}, hitRate={hit_rate}",
            "snapshot_time": datetime.now(timezone.utc).isoformat(),
        }

    # Determine primary failure mode
    primary_mode = issues[0]
    mode_config = FAILURE_MODES[primary_mode]

    diagnosis_string = (
        f"Node {node_id}: {mode_config['description']} — "
        + "; ".join(evidence[:3])
    )

    return {
        "tool": "explain_node_anomaly",
        "severity": mode_config["severity"],
        "failure_mode": primary_mode,
        "affected_component": node_id,
        "root_cause": f"Node {node_id}: {mode_config['description']}",
        "evidence": evidence,
        "recommendations": mode_config["recommendations"],
        "diagnosis_string": diagnosis_string,
        "snapshot_time": datetime.now(timezone.utc).isoformat(),
    }


def diagnose_slo(data: dict) -> dict:
    """Diagnose SLO status."""
    if "_error" in data:
        return diagnose_snapshot(data, context="slo_breach")

    availability = data.get("sloAvailabilityRatio", data.get("availabilityRatio"))
    burn_rate = data.get("sloBurnRate1h", data.get("burnRate1h"))
    error_rate = data.get("errorRate5m")
    health = data.get("healthVerdict", data.get("health"))

    evidence = []
    severity = "OK"
    failure_mode = None
    root_cause = "SLO targets are being met"
    recommendations = []

    if availability is not None and availability != "data_unavailable":
        evidence.append(f"availability={availability:.4f}" if isinstance(availability, float) else f"availability={availability}")
        if isinstance(availability, (int, float)) and availability < 0.999:
            severity = "WARNING"
            failure_mode = "node_isolation"
            root_cause = f"SLO availability below target: {availability:.4f} (target: 0.999)"
            recommendations.append("Investigate failing nodes to restore availability")
    else:
        evidence.append("availability=data_unavailable")

    if burn_rate is not None and burn_rate != "data_unavailable":
        evidence.append(f"burnRate1h={burn_rate:.2f}" if isinstance(burn_rate, float) else f"burnRate1h={burn_rate}")
        if isinstance(burn_rate, (int, float)) and burn_rate > 1.0:
            severity = "CRITICAL"
            failure_mode = "quorum_failure"
            root_cause = f"SLO burn rate critical: {burn_rate:.2f}x — error budget exhausting rapidly"
            recommendations = [
                "URGENT: Error budget burning at >1x rate",
                "Identify and fix the root cause of elevated error rate",
                "Consider enabling circuit breaker to shed load",
            ]
    else:
        evidence.append("burnRate1h=data_unavailable")

    if error_rate is not None and error_rate != "data_unavailable":
        evidence.append(f"errorRate5m={error_rate:.4f}" if isinstance(error_rate, float) else f"errorRate5m={error_rate}")
        if isinstance(error_rate, (int, float)) and error_rate > 0.01 and severity != "CRITICAL":
            severity = "WARNING"
            recommendations.append(f"Error rate elevated at {error_rate:.2%} — investigate recent changes")
    else:
        evidence.append("errorRate5m=data_unavailable")

    if health:
        evidence.append(f"healthVerdict={health}")

    diagnosis_string = f"SLO Status: {severity} — " + "; ".join(evidence[:3])
    if failure_mode:
        diagnosis_string += f" → {root_cause}"

    return {
        "tool": "explain_slo_breach",
        "severity": severity,
        "failure_mode": failure_mode,
        "root_cause": root_cause,
        "evidence": evidence,
        "recommendations": recommendations,
        "diagnosis_string": diagnosis_string,
        "snapshot_time": datetime.now(timezone.utc).isoformat(),
    }


def diagnose_latency(data: list | dict, node_id: str | None = None) -> dict:
    """Diagnose latency anomalies."""
    if isinstance(data, dict) and "_error" in data:
        return diagnose_snapshot(data, context="latency_spike")

    profiles = data if isinstance(data, list) else [data]
    if node_id:
        profiles = [p for p in profiles if p.get("nodeId") == node_id]

    evidence = []
    slow_nodes = []
    critical_nodes = []

    for profile in profiles:
        nid = profile.get("nodeId", "unknown")
        p99 = profile.get("p99LatencyMs", profile.get("p99"))
        tier = profile.get("tier", profile.get("latencyTier"))

        if p99 is None:
            evidence.append(f"[{nid}] p99=data_unavailable")
            continue

        evidence.append(f"[{nid}] p99={p99}ms tier={tier or 'N/A'}")

        if tier == "CRITICAL" or (isinstance(p99, (int, float)) and p99 > 500):
            critical_nodes.append(nid)
        elif tier == "SLOW" or (isinstance(p99, (int, float)) and p99 > 200):
            slow_nodes.append(nid)

    if critical_nodes:
        severity = "CRITICAL"
        failure_mode = "cache_memory_pressure"
        root_cause = f"Critical latency on nodes: {', '.join(critical_nodes)} — p99 > 500ms"
        recommendations = [
            "Check memory pressure on affected nodes (high evictions cause latency)",
            "Verify network connectivity — high latency may indicate packet loss",
            "Check if affected nodes are under rebalancing (drain active)",
            "Consider removing hot keys or redistributing load",
        ]
    elif slow_nodes:
        severity = "WARNING"
        failure_mode = "cache_memory_pressure"
        root_cause = f"Elevated latency on nodes: {', '.join(slow_nodes)} — p99 > 200ms"
        recommendations = [
            "Monitor if latency is trending upward",
            "Check memory used ratio on slow nodes",
            "Verify no ongoing ring rebalancing affecting these nodes",
        ]
    else:
        severity = "OK"
        failure_mode = None
        root_cause = "All nodes within acceptable latency bounds"
        recommendations = []

    diagnosis_string = f"Latency: {severity}"
    if critical_nodes:
        diagnosis_string += f" — critical nodes: {', '.join(critical_nodes)}"
    elif slow_nodes:
        diagnosis_string += f" — slow nodes: {', '.join(slow_nodes)}"
    else:
        diagnosis_string += " — all nodes in FAST/OK tier"

    return {
        "tool": "explain_latency_spike",
        "severity": severity,
        "failure_mode": failure_mode,
        "root_cause": root_cause,
        "evidence": evidence,
        "recommendations": recommendations,
        "diagnosis_string": diagnosis_string,
        "snapshot_time": datetime.now(timezone.utc).isoformat(),
    }


def diagnose_self_healing(data: dict) -> dict:
    """Diagnose self-healing activity."""
    if "_error" in data:
        return diagnose_snapshot(data, context="self_healing")

    # Handle both flat and nested response shapes
    repairs = data.get("antiEntropyRepairs", data.get("totalRepairs", 0))
    migration_queue = data.get("migrationQueueSize", data.get("pendingMigrations", 0))
    drain_active = data.get("drainActive", data.get("anyDrainActive", False))

    evidence = []
    evidence.append(f"antiEntropyRepairs={repairs if repairs is not None else 'data_unavailable'}")
    evidence.append(f"migrationQueueSize={migration_queue if migration_queue is not None else 'data_unavailable'}")
    evidence.append(f"drainActive={drain_active}")

    if drain_active and migration_queue and migration_queue > 10:
        severity = "WARNING"
        failure_mode = "ring_rebalancing_lag"
        root_cause = f"Active drain with large migration queue ({migration_queue} pending)"
        recommendations = FAILURE_MODES["ring_rebalancing_lag"]["recommendations"]
    elif repairs and isinstance(repairs, (int, float)) and repairs > 50:
        severity = "WARNING"
        failure_mode = "ring_rebalancing_lag"
        root_cause = f"High anti-entropy repair count ({repairs}) — significant data inconsistency being repaired"
        recommendations = [
            "Repairs indicate recent node failures or network partitions",
            "Monitor until repair count stabilizes",
            "Check if any nodes were recently added/removed from ring",
        ]
    else:
        severity = "OK" if not drain_active else "INFO"
        failure_mode = None
        root_cause = "Self-healing activity is normal" if not drain_active else "Drain active but migration queue is manageable"
        recommendations = []

    diagnosis_string = f"Self-healing: {severity} — repairs={repairs}, queue={migration_queue}, drain={'active' if drain_active else 'inactive'}"

    return {
        "tool": "explain_self_healing",
        "severity": severity,
        "failure_mode": failure_mode,
        "root_cause": root_cause,
        "evidence": evidence,
        "recommendations": recommendations,
        "diagnosis_string": diagnosis_string,
        "snapshot_time": datetime.now(timezone.utc).isoformat(),
    }


# ── MCP Tool Definitions ─────────────────────────────────────────────────────

@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="explain_cluster_health",
            description=(
                "Fetch the cluster snapshot from Observe API and produce a structured "
                "diagnosis of overall cluster health including failure mode, root cause, "
                "evidence, and recommendations."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
            },
        ),
        Tool(
            name="explain_node_anomaly",
            description=(
                "Diagnose anomalies for a specific cache node. Fetches the node snapshot "
                "and identifies issues like isolation, memory pressure, or rebalancing lag."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "node_id": {
                        "type": "string",
                        "description": "The node ID to diagnose (e.g. 'node-1')",
                    },
                },
                "required": ["node_id"],
            },
        ),
        Tool(
            name="explain_slo_breach",
            description=(
                "Fetch SLO status from Observe API and explain whether SLO targets are "
                "being met, identify burn rate issues, and recommend corrective actions."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
            },
        ),
        Tool(
            name="explain_latency_spike",
            description=(
                "Fetch latency profiles from Observe API and identify nodes with "
                "latency anomalies. Optionally filter to a single node."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "node_id": {
                        "type": "string",
                        "description": "Optional node ID to filter latency analysis to",
                    },
                },
            },
        ),
        Tool(
            name="explain_self_healing",
            description=(
                "Fetch self-healing status from Observe API and explain ongoing repair "
                "activity, migration queue depth, and drain state."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
            },
        ),
    ]


# ── Tool Dispatcher ───────────────────────────────────────────────────────────

@app.call_tool()
async def call_tool(name: str, arguments: dict):

    if name == "explain_cluster_health":
        data = await observe_get("/api/v1/observe/snapshot")
        diagnosis = diagnose_snapshot(data, context="cluster_health")
        return [TextContent(type="text", text=json.dumps(diagnosis, indent=2))]

    elif name == "explain_node_anomaly":
        node_id = arguments.get("node_id", "")
        if not node_id:
            return [TextContent(type="text", text=json.dumps({
                "error": "node_id is required",
                "severity": "ERROR",
            }, indent=2))]
        data = await observe_get(f"/api/v1/observe/node/{node_id}")
        diagnosis = diagnose_node(data)
        return [TextContent(type="text", text=json.dumps(diagnosis, indent=2))]

    elif name == "explain_slo_breach":
        data = await observe_get("/api/v1/observe/slo")
        diagnosis = diagnose_slo(data)
        return [TextContent(type="text", text=json.dumps(diagnosis, indent=2))]

    elif name == "explain_latency_spike":
        node_id = arguments.get("node_id")
        data = await observe_get("/api/v1/observe/latency")
        diagnosis = diagnose_latency(data, node_id=node_id)
        return [TextContent(type="text", text=json.dumps(diagnosis, indent=2))]

    elif name == "explain_self_healing":
        data = await observe_get("/api/v1/observe/self-healing")
        diagnosis = diagnose_self_healing(data)
        return [TextContent(type="text", text=json.dumps(diagnosis, indent=2))]

    else:
        return [TextContent(type="text", text=json.dumps({
            "error": f"Unknown tool: {name}",
            "available_tools": [
                "explain_cluster_health",
                "explain_node_anomaly",
                "explain_slo_breach",
                "explain_latency_spike",
                "explain_self_healing",
            ],
        }, indent=2))]


# ── Entry Point ───────────────────────────────────────────────────────────────

async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
