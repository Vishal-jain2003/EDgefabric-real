#!/usr/bin/env python3
"""cache-mcp-write.py — PostToolUse companion to cache-mcp.py.

Stores successful MCP responses to the cache so subsequent identical calls within
TTL can be served from disk by cache-mcp.py.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path

CACHE = Path(os.path.expanduser("~")) / ".claude" / "mcp-cache"
MAX_RESPONSE_BYTES = 256 * 1024  # don't cache mega-blobs

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


def _key(tool: str, inp: dict) -> str:
    payload = json.dumps(inp, sort_keys=True, default=str)
    return hashlib.sha1(f"{tool}|{payload}".encode()).hexdigest()


def main() -> int:
    if os.environ.get("MCP_CACHE_DISABLED") == "1":
        return 0
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0

    tool = data.get("tool_name", "")
    if not tool.startswith("mcp__") or tool in MUTATING or tool.startswith("mcp__aws__"):
        return 0

    inp = data.get("tool_input") or {}
    resp = data.get("tool_response") or {}
    exit_code = resp.get("exitCode", 0)
    if exit_code:
        return 0

    body = resp.get("response") or resp.get("output") or resp
    try:
        serialized = json.dumps(body, default=str)
    except Exception:
        return 0
    if len(serialized) > MAX_RESPONSE_BYTES:
        return 0

    key = _key(tool, inp)
    safe = tool.replace("/", "_")
    out_dir = CACHE / safe
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{key}.json").write_text(
        json.dumps({"tool": tool, "input": inp, "response": body}, default=str),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
