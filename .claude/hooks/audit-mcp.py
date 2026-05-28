#!/usr/bin/env python3
"""PostToolUse audit (enhanced) — logs every MCP call with duration + exit.

Captures EVERY MCP tool call (not just mutating ones) so we can build per-tool
usage / latency dashboards. Mutating-only filtering moves to the report script.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone


MUTATING_TOOLS = {
    "mcp__gitlab__commit_files", "mcp__gitlab__create_merge_request",
    "mcp__gitlab__merge_merge_request", "mcp__gitlab__add_mr_comment",
    "mcp__gitlab__delete_branch", "mcp__gitlab__create_branch",
    "mcp__gitlab__trigger_pipeline",
    "mcp__jenkins__trigger_build",
    "mcp__atlassian__create_issue", "mcp__atlassian__update_issue",
    "mcp__atlassian__update_issue_status", "mcp__atlassian__add_issue_comment",
    "mcp__atlassian__create_confluence_page", "mcp__atlassian__update_confluence_page",
    "mcp__notion__API-post-page", "mcp__notion__API-patch-block-children",
    "mcp__notion__API-patch-page", "mcp__notion__API-delete-a-block",
}


def main() -> int:
    try:
        data = json.load(sys.stdin)
        tool = data.get("tool_name", "")
        inp = data.get("tool_input") or {}
        resp = data.get("tool_response") or {}
        exit_code = resp.get("exitCode", 0)
        duration_ms = resp.get("duration_ms", 0) or resp.get("duration", 0)
    except Exception:
        return 0

    if not tool.startswith("mcp__"):
        return 0

    log_dir = os.path.join(os.path.expanduser("~"), ".claude", "audit-logs")
    os.makedirs(log_dir, exist_ok=True)

    try:
        branch = subprocess.check_output(
            ["git", "branch", "--show-current"], stderr=subprocess.DEVNULL, text=True
        ).strip()
    except Exception:
        branch = "unknown"

    summary = {
        k: v for k, v in inp.items()
        if isinstance(v, (str, int, float, bool))
        or k in ("branch", "mr_iid", "status", "job_name", "ref")
    }
    entry = {
        "ts":          datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "branch":      branch,
        "tool":        tool,
        "mutating":    tool in MUTATING_TOOLS or tool.startswith("mcp__aws__"),
        "input":       summary,
        "exit":        exit_code or 0,
        "duration_ms": duration_ms or 0,
    }
    with open(os.path.join(log_dir, "mcp-operations.jsonl"), "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
