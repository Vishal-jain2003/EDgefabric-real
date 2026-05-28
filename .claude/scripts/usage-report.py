"""Summarise ~/.claude/audit-logs into a per-tool usage report.

Usage:
  py .claude/scripts/usage-report.py            # last 7 days
  py .claude/scripts/usage-report.py --days 30
  py .claude/scripts/usage-report.py --cost     # estimate $ using token deltas

Cost model (USD per 1M tokens, input/output):
  haiku:  0.80 / 4.00
  sonnet: 3.00 / 15.00
  opus:   15.0 / 75.00
Set per-run model via env COST_MODEL (default: sonnet) when token deltas are missing.
"""
from __future__ import annotations

import argparse
import json
import os
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

LOG_DIR = Path(os.path.expanduser("~")) / ".claude" / "audit-logs"

PRICES = {
    "haiku":  (0.80,  4.00),
    "sonnet": (3.00, 15.00),
    "opus":  (15.00, 75.00),
}


def load(path: Path, since: datetime) -> list[dict]:
    out: list[dict] = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            entry = json.loads(line)
        except Exception:
            continue
        try:
            ts = datetime.strptime(entry.get("ts", ""), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        except Exception:
            continue
        if ts >= since:
            out.append(entry)
    return out


def summarise(entries: list[dict], key: str) -> list[tuple[str, int, int, int]]:
    """Return [(name, calls, fails, total_ms)] sorted by calls desc."""
    agg: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
    for e in entries:
        name = e.get(key, "?")
        agg[name][0] += 1
        if int(e.get("exit", 0) or 0) != 0:
            agg[name][1] += 1
        agg[name][2] += int(e.get("duration_ms", 0) or 0)
    return sorted(((n, v[0], v[1], v[2]) for n, v in agg.items()),
                  key=lambda x: -x[1])


def render_table(rows, headers):
    widths = [max(len(str(h)), max((len(str(r[i])) for r in rows), default=0)) for i, h in enumerate(headers)]
    line = " | ".join(h.ljust(w) for h, w in zip(headers, widths))
    sep = "-+-".join("-" * w for w in widths)
    print(line)
    print(sep)
    for r in rows:
        print(" | ".join(str(c).ljust(w) for c, w in zip(r, widths)))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=7)
    ap.add_argument("--cost", action="store_true",
                    help="estimate $ from input_tokens/output_tokens fields when present")
    args = ap.parse_args()
    since = datetime.now(timezone.utc) - timedelta(days=args.days)

    print(f"=== Usage report (last {args.days} days, since {since.strftime('%Y-%m-%d')}) ===\n")

    bash = load(LOG_DIR / "operations.jsonl", since)
    mcp = load(LOG_DIR / "mcp-operations.jsonl", since)

    print(f"Bash audit entries: {len(bash)}")
    print(f"MCP  audit entries: {len(mcp)}")
    print()

    if mcp:
        print("== MCP tool usage ==")
        rows = [(n, c, f, ms) for n, c, f, ms in summarise(mcp, "tool")]
        render_table(rows, ["tool", "calls", "fails", "total_ms"])
        print()
    if bash:
        print("== Bash audit (top branches) ==")
        rows = [(n, c, f, ms) for n, c, f, ms in summarise(bash, "branch")]
        render_table(rows, ["branch", "cmds", "fails", "total_ms"])
        print()

    if args.cost:
        print("== Cost estimate ==")
        total_usd = 0.0
        per_model: dict[str, float] = defaultdict(float)
        for e in (bash + mcp):
            tin = int(e.get("input_tokens", 0) or 0)
            tout = int(e.get("output_tokens", 0) or 0)
            model = (e.get("model") or os.environ.get("COST_MODEL", "sonnet")).lower()
            for k in PRICES:
                if k in model:
                    pin, pout = PRICES[k]
                    cost = (tin * pin + tout * pout) / 1_000_000
                    per_model[k] += cost
                    total_usd += cost
                    break
        if total_usd == 0:
            print("(no input_tokens/output_tokens recorded — wire your runtime to log them)")
        else:
            for m, c in sorted(per_model.items(), key=lambda x: -x[1]):
                print(f"  {m:<8} ${c:8.4f}")
            print(f"  {'TOTAL':<8} ${total_usd:8.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
