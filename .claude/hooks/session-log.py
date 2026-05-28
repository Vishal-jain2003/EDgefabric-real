#!/usr/bin/env python3
# Stop hook — appends a session entry to ~/.claude/session-logs/sessions.jsonl
import os
import json
import subprocess
from datetime import datetime, timezone


log_dir = os.path.join(os.path.expanduser("~"), ".claude", "session-logs")
os.makedirs(log_dir, exist_ok=True)

try:
    branch = subprocess.check_output(
        ["git", "branch", "--show-current"],
        stderr=subprocess.DEVNULL, text=True
    ).strip()
except Exception:
    branch = "unknown"

entry = {
    "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "branch": branch,
    "dir": os.getcwd()
}

with open(os.path.join(log_dir, "sessions.jsonl"), "a", encoding="utf-8") as f:
    f.write(json.dumps(entry) + "\n")
