#!/usr/bin/env python3
"""skip-if-unchanged.py — emit `SKIP` (exit 0) when nothing has changed since
the last successful run for the given key, otherwise emit `RUN` (exit 0).

Usage:
  py .claude/scripts/skip-if-unchanged.py <key> <path1> [<path2> ...]

Hashes the listed paths + git HEAD; compares to ~/.claude/skip-cache/<key>.sha.
Agents call this at the top of their script to short-circuit no-op runs:

    if py .claude/scripts/skip-if-unchanged.py code-review $(git diff --name-only HEAD~1) | grep -q SKIP; then
        echo "no changes - skipping"; exit 0
    fi
"""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys
from pathlib import Path

CACHE = Path(os.path.expanduser("~")) / ".claude" / "skip-cache"
CACHE.mkdir(parents=True, exist_ok=True)


def _hash(paths: list[str]) -> str:
    h = hashlib.sha256()
    try:
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        h.update(sha.encode())
    except Exception:
        pass
    for p in sorted(set(paths)):
        fp = Path(p)
        if not fp.exists() or fp.is_dir():
            continue
        try:
            h.update(fp.read_bytes())
        except Exception:
            pass
    return h.hexdigest()


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: skip-if-unchanged.py <key> <path...>", file=sys.stderr)
        return 2
    key, paths = sys.argv[1], sys.argv[2:]
    cur = _hash(paths)
    cf = CACHE / f"{key}.sha"
    prev = cf.read_text(encoding="utf-8").strip() if cf.exists() else ""
    if cur == prev and prev:
        print("SKIP")
    else:
        print("RUN")
        cf.write_text(cur, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
