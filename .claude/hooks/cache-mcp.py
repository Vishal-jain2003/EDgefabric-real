#!/usr/bin/env python3
"""cache-mcp.py — PreToolUse hook that short-circuits idempotent MCP calls.

Reads the MCP tool invocation on stdin (Claude/Copilot hook protocol). If a cached
response exists for the same (tool, normalized-args) within TTL, it emits the cached
response on stdout and exits with code 2 (which the runtime treats as "tool already
handled — skip execution"). Otherwise exits 0 and lets the call proceed.

The companion hook `audit-mcp.py` (PostToolUse) writes successful responses into
the cache via `cache-mcp-write` when invoked with --write.

Cache layout: ~/.claude/mcp-cache/<tool>/<sha1>.json
TTL: env MCP_CACHE_TTL_MIN (default 15). Set MCP_CACHE_DISABLED=1 to bypass.
Only READ tools are cached — anything in audit-mcp.MUTATING_TOOLS is never cached.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from pathlib import Path

CACHE = Path(os.path.expanduser("~")) / ".claude" / "mcp-cache"
TTL_SEC = int(os.environ.get("MCP_CACHE_TTL_MIN", "15")) * 60

# Tools we never cache (writes/mutations). Mirrors audit-mcp.MUTATING_TOOLS.
MUTATING = {
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

# Per-tool TTL overrides (seconds). Live data needs short TTL.
TTL_OVERRIDES = {
    "mcp__jenkins__get_build_status": 60,
    "mcp__gitlab__get_pipeline_status": 60,
    "mcp__gitlab__get_mr_status": 60,
    "mcp__sonarqube__get_quality_gate_status": 120,
    "mcp__atlassian__get_issue": 600,
    "mcp__atlassian__search_issues": 300,
}


def _key(tool: str, inp: dict) -> str:
    payload = json.dumps(inp, sort_keys=True, default=str)
    return hashlib.sha1(f"{tool}|{payload}".encode()).hexdigest()


def _cache_path(tool: str, key: str) -> Path:
    safe = tool.replace("/", "_")
    return CACHE / safe / f"{key}.json"


def _is_cacheable(tool: str) -> bool:
    if not tool.startswith("mcp__"):
        return False
    if tool in MUTATING or tool.startswith("mcp__aws__"):
        return False
    return True


def _record_stat(event: str, tool: str) -> None:
    try:
        stats_dir = Path(os.path.expanduser("~")) / ".claude" / "audit-logs"
        stats_dir.mkdir(parents=True, exist_ok=True)
        with open(stats_dir / "mcp-cache.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps({"ts": int(time.time()), "event": event, "tool": tool}) + "\n")
    except Exception:
        pass


def main() -> int:
    if os.environ.get("MCP_CACHE_DISABLED") == "1":
        return 0
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0

    tool = data.get("tool_name", "")
    inp = data.get("tool_input") or {}
    if not _is_cacheable(tool):
        return 0

    key = _key(tool, inp)
    cf = _cache_path(tool, key)
    if not cf.exists():
        _record_stat("miss", tool)
        return 0

    ttl = TTL_OVERRIDES.get(tool, TTL_SEC)
    try:
        mtime = cf.stat().st_mtime
    except FileNotFoundError:
        return 0
    if (time.time() - mtime) > ttl:
        _record_stat("expired", tool)
        return 0

    try:
        cached = json.loads(cf.read_text(encoding="utf-8"))
    except Exception:
        _record_stat("corrupt", tool)
        return 0

    _record_stat("hit", tool)
    # Emit a hook decision telling the runtime to skip execution and use this output.
    decision = {
        "decision": "block",
        "reason": "served from mcp-cache",
        "stopReason": "cache-hit",
        "additionalContext": cached.get("response", ""),
    }
    sys.stdout.write(json.dumps(decision))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
