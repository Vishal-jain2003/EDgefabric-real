#!/usr/bin/env python3
"""PostToolUse audit (enhanced) — logs Bash commands, durations, exit codes.

Append-only JSONL at ~/.claude/audit-logs/operations.jsonl.

Adds duration_ms when Claude/Copilot provides it via tool_response.duration_ms.
Falls back to 0 when not available.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone


def main() -> int:
    try:
        data = json.load(sys.stdin)
        cmd = (data.get("tool_input") or {}).get("command", "")
        resp = data.get("tool_response") or {}
        exit_code = resp.get("exitCode", 0)
        duration_ms = resp.get("duration_ms", 0) or resp.get("duration", 0)
    except Exception:
        return 0

    if not cmd:
        return 0

    AUDIT_PATTERN = re.compile(
        r"git\s+(commit|push|merge|rebase|tag|branch\s+-[dD])"
        r"|aws\s+(ec2|s3|iam|ssm|rds|eks)"
        r"|docker\s+(build|push|rm|volume|rmi)"
        r"|(curl|wget)\s+.*-[oO]\b"
        r"|mvn\s+(deploy|release|package|verify|test)",
        re.IGNORECASE,
    )
    if not AUDIT_PATTERN.search(cmd):
        return 0

    log_dir = os.path.join(os.path.expanduser("~"), ".claude", "audit-logs")
    os.makedirs(log_dir, exist_ok=True)

    try:
        branch = subprocess.check_output(
            ["git", "branch", "--show-current"], stderr=subprocess.DEVNULL, text=True
        ).strip()
    except Exception:
        branch = "unknown"

    entry = {
        "ts":          datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "branch":      branch,
        "exit":        exit_code or 0,
        "duration_ms": duration_ms or 0,
        "cmd":         cmd[:500],
    }
    with open(os.path.join(log_dir, "operations.jsonl"), "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
