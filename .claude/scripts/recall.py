#!/usr/bin/env python3
"""recall.py — semantic-ish memory store for EdgeFabric agents.

Backed by a SQLite + FTS5 database at ~/.claude/memory/edgefabric.db. Records
key decisions, ADR snippets, gate verdicts, lessons learned. Agents query it at
the start of a run to avoid re-deriving prior context.

CLI:
  py .claude/scripts/recall.py remember --agent solution-architect \
      --topic "EPMICMPHE-87 cache TTL" --body "Rejected option B because ..."
  py .claude/scripts/recall.py recall --agent solution-architect \
      --topic "cache TTL" [--limit 5] [--max-tokens 500]
  py .claude/scripts/recall.py recall --topic "EPMICMPHE-87"
  py .claude/scripts/recall.py stats

Output of `recall` is plain text (one bullet per row), trimmed to --max-tokens
(rough approximation: 4 chars ~ 1 token). Designed to be injected into the
prompt by prompt-context.py.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

DB = Path(os.path.expanduser("~")) / ".claude" / "memory" / "edgefabric.db"
DB.parent.mkdir(parents=True, exist_ok=True)


def _conn() -> sqlite3.Connection:
    c = sqlite3.connect(str(DB))
    c.execute("""
        CREATE TABLE IF NOT EXISTS memories (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            ts        INTEGER NOT NULL,
            agent     TEXT NOT NULL,
            repo      TEXT,
            branch    TEXT,
            ticket    TEXT,
            topic     TEXT NOT NULL,
            body      TEXT NOT NULL,
            tags      TEXT
        )
    """)
    c.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts
        USING fts5(topic, body, tags, content='memories', content_rowid='id')
    """)
    c.execute("""
        CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
            INSERT INTO memories_fts(rowid, topic, body, tags)
            VALUES (new.id, new.topic, new.body, new.tags);
        END
    """)
    c.execute("CREATE INDEX IF NOT EXISTS idx_mem_agent ON memories(agent)")
    c.execute("CREATE INDEX IF NOT EXISTS idx_mem_ticket ON memories(ticket)")
    return c


def _git(cmd: list[str]) -> str:
    try:
        return subprocess.check_output(cmd, stderr=subprocess.DEVNULL, text=True).strip()
    except Exception:
        return ""


def _ctx() -> tuple[str, str, str]:
    branch = _git(["git", "branch", "--show-current"])
    repo = _git(["git", "rev-parse", "--show-toplevel"])
    ticket = ""
    if branch:
        import re
        m = re.search(r"[A-Z]+-\d+", branch)
        if m:
            ticket = m.group(0)
    return repo, branch, ticket


def cmd_remember(args: argparse.Namespace) -> int:
    repo, branch, ticket = _ctx()
    body = args.body or sys.stdin.read().strip()
    if not body:
        print("error: --body required (or pipe via stdin)", file=sys.stderr)
        return 2
    with _conn() as c:
        c.execute(
            "INSERT INTO memories (ts, agent, repo, branch, ticket, topic, body, tags)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (int(time.time()), args.agent, repo, branch, args.ticket or ticket,
             args.topic, body, args.tags or ""),
        )
    print(f"[recall] stored memory for agent={args.agent} topic={args.topic!r}")
    return 0


def _fts_query(topic: str) -> str:
    # Build OR-of-tokens query, escape FTS special chars conservatively.
    toks = [t for t in (topic or "").replace('"', " ").split() if t]
    if not toks:
        return ""
    return " OR ".join(f'"{t}"' for t in toks)


def cmd_recall(args: argparse.Namespace) -> int:
    repo, _branch, ticket_ctx = _ctx()
    where, params = [], []
    if args.agent:
        where.append("m.agent = ?"); params.append(args.agent)
    if args.ticket:
        where.append("m.ticket = ?"); params.append(args.ticket)
    elif ticket_ctx and args.use_ctx_ticket:
        where.append("m.ticket = ?"); params.append(ticket_ctx)
    sql = (
        "SELECT m.ts, m.agent, m.ticket, m.topic, m.body FROM memories m"
    )
    fts = _fts_query(args.topic)
    if fts:
        sql += " JOIN memories_fts f ON f.rowid = m.id WHERE memories_fts MATCH ?"
        params.insert(0, fts)
        if where:
            sql += " AND " + " AND ".join(where)
    elif where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY m.ts DESC LIMIT ?"
    params.append(args.limit)

    with _conn() as c:
        rows = c.execute(sql, params).fetchall()
    if not rows:
        return 0

    out, used = [], 0
    cap_chars = args.max_tokens * 4
    for ts, agent, tkt, topic, body in rows:
        bullet = f"- [{agent}|{tkt or '-'}|{topic}] {body.strip()}"
        if used + len(bullet) > cap_chars:
            bullet = bullet[: max(0, cap_chars - used)] + "…"
            out.append(bullet); break
        out.append(bullet); used += len(bullet) + 1
    sys.stdout.write("\n".join(out) + "\n")
    return 0


def cmd_stats(_args: argparse.Namespace) -> int:
    with _conn() as c:
        total = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        per_agent = c.execute(
            "SELECT agent, COUNT(*) FROM memories GROUP BY agent ORDER BY 2 DESC"
        ).fetchall()
    print(json.dumps({"total": total, "per_agent": dict(per_agent)}, indent=2))
    return 0


def main() -> int:
    p = argparse.ArgumentParser(prog="recall")
    sub = p.add_subparsers(dest="cmd", required=True)

    pr = sub.add_parser("remember")
    pr.add_argument("--agent", required=True)
    pr.add_argument("--topic", required=True)
    pr.add_argument("--body", default=None)
    pr.add_argument("--ticket", default=None)
    pr.add_argument("--tags", default=None)
    pr.set_defaults(fn=cmd_remember)

    pq = sub.add_parser("recall")
    pq.add_argument("--agent", default=None)
    pq.add_argument("--topic", default="")
    pq.add_argument("--ticket", default=None)
    pq.add_argument("--limit", type=int, default=5)
    pq.add_argument("--max-tokens", type=int, default=500)
    pq.add_argument("--use-ctx-ticket", action="store_true")
    pq.set_defaults(fn=cmd_recall)

    ps = sub.add_parser("stats")
    ps.set_defaults(fn=cmd_stats)

    a = p.parse_args()
    return a.fn(a)


if __name__ == "__main__":
    raise SystemExit(main())
