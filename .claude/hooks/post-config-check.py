#!/usr/bin/env python3
# PostToolUse hook — warns when a critical config file is modified.
import sys
import json
import os
import re


try:
    data = json.load(sys.stdin)
    file_path = data.get("tool_input", {}).get("file_path", "")
except Exception:
    sys.exit(0)

if not file_path:
    sys.exit(0)

filename = os.path.basename(file_path)

CRITICAL_CONFIGS = [
    "docker-compose.yml",
    "docker-compose.yaml",
    "application.yml",
    "application.yaml",
    "application.properties",
    "bootstrap.yml",
    "Dockerfile",
    "start.sh",
    "Jenkinsfile",
]

if filename in CRITICAL_CONFIGS or re.search(r'^Dockerfile(\..+)?$', filename):
    print(
        f"[CONFIG CHANGE DETECTED] {file_path} was modified. "
        "Run /validate to check cross-file consistency (ports, image tags, env vars)."
    )

sys.exit(0)
