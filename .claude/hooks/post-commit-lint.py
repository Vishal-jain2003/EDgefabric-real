#!/usr/bin/env python3
# PostToolUse hook — validates git commit messages follow conventional commits format.
import sys
import json
import re


try:
    data = json.load(sys.stdin)
    cmd = data.get("tool_input", {}).get("command", "")
except Exception:
    sys.exit(0)

if "git commit" not in cmd:
    sys.exit(0)

# Extract message from -m '...' or -m "..."
msg_match = re.search(r'-m\s+[\'"]([^\'"]+)[\'"]', cmd)
if not msg_match:
    sys.exit(0)

msg = msg_match.group(1).strip()

if not re.match(r'^(feat|fix|refactor|test|chore|docs|perf)(\(.+\))?:', msg):
    print(
        "[COMMIT FORMAT WARNING] Message does not follow conventional commits: "
        "feat|fix|refactor|test|chore|docs|perf. Please revise."
    )

sys.exit(0)
