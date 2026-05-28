#!/usr/bin/env python3
# UserPromptSubmit hook — prints active Jira issue and SDLC session state as context.
import sys
import json
import re
import os
import subprocess


try:
    branch = subprocess.check_output(
        ["git", "branch", "--show-current"],
        stderr=subprocess.DEVNULL, text=True
    ).strip()
except Exception:
    branch = ""

key_match = re.search(r'[A-Z]+-\d+', branch)
if key_match:
    key = key_match.group(0)
    print(f"[JIRA context] Active Jira issue: {key} (branch: {branch}). Use mcp__atlassian__get_issue to load full context if needed.")

try:
    root = subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"],
        stderr=subprocess.DEVNULL, text=True
    ).strip()
    state_file = os.path.join(root, ".codemie", "session-state.json")
    if os.path.isfile(state_file):
        with open(state_file, encoding="utf-8") as f:
            d = json.load(f)
        ticket = d.get("ticket", "?")
        stage = d.get("stage", "?")
        status = d.get("status", "?")
        print(f"[Session state] SDLC in progress — ticket: {ticket} | stage: {stage} | status: {status} | reply /resume to continue")
except Exception:
    pass

# Inject prior memories for the active ticket (capped at 500 tokens).
if key_match and os.environ.get("RECALL_DISABLED") != "1":
    try:
        recall = subprocess.run(
            ["python", ".claude/scripts/recall.py", "recall",
             "--ticket", key_match.group(0), "--limit", "5", "--max-tokens", "500"],
            capture_output=True, text=True, timeout=4,
        )
        out = (recall.stdout or "").strip()
        if out:
            print("[recall] Prior memory for this ticket:\n" + out)
    except Exception:
        pass

sys.exit(0)
