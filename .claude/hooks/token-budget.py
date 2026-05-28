#!/usr/bin/env python3
"""token-budget.py — Stop-hook enforcer.

Reads ~/.claude/audit-logs/{operations,mcp-operations}.jsonl, counts tool calls
in the current session window (last 30 min), compares to a budget. Emits a
warning line on stderr if the budget is exceeded. Non-blocking by default; set
TOKEN_BUDGET_HARD=1 to exit non-zero (Claude/Copilot then surfaces it).

Defaults (override via env or .claude/settings.json):
  MAX_TOOL_CALLS_PER_TURN = 60
  MAX_BASH_PER_TURN       = 25
  MAX_MCP_PER_TURN        = 40
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

LOG = Path(os.path.expanduser("~")) / ".claude" / "audit-logs"
WINDOW = timedelta(minutes=30)


def _count(path: Path, since: datetime) -> int:
    if not path.exists():
        return 0
    n = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            ts = datetime.strptime(json.loads(line).get("ts", ""),
                                   "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        except Exception:
            continue
        if ts >= since:
            n += 1
    return n


def main() -> int:
    since = datetime.now(timezone.utc) - WINDOW
    bash = _count(LOG / "operations.jsonl", since)
    mcp = _count(LOG / "mcp-operations.jsonl", since)
    total = bash + mcp

    max_total = int(os.environ.get("MAX_TOOL_CALLS_PER_TURN", "60"))
    max_bash = int(os.environ.get("MAX_BASH_PER_TURN", "25"))
    max_mcp = int(os.environ.get("MAX_MCP_PER_TURN", "40"))

    over = []
    if total > max_total: over.append(f"total={total}>{max_total}")
    if bash > max_bash:   over.append(f"bash={bash}>{max_bash}")
    if mcp > max_mcp:     over.append(f"mcp={mcp}>{max_mcp}")

    if over:
        print(f"[token-budget] OVER BUDGET: {', '.join(over)} in last 30m", file=sys.stderr)
        if os.environ.get("TOKEN_BUDGET_HARD") == "1":
            return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
