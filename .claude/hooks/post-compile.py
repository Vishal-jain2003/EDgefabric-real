#!/usr/bin/env python3
# PostToolUse hook — runs mvn compile on the affected module after a Java file is edited.
import sys
import json
import os
import subprocess


try:
    data = json.load(sys.stdin)
    file_path = data.get("tool_input", {}).get("file_path", "")
except Exception:
    sys.exit(0)

if not file_path.endswith(".java"):
    sys.exit(0)

try:
    root = subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"],
        stderr=subprocess.DEVNULL, text=True
    ).strip()

    rel = os.path.relpath(file_path, root)
    module = rel.split(os.sep)[0]
    m2_repo = os.path.join(root, ".m2repo-lb-test")

    result = subprocess.run(
        ["mvn", "compile", "-pl", module, f"-Dmaven.repo.local={m2_repo}", "-q"],
        capture_output=True, text=True, cwd=root
    )

    if result.returncode != 0:
        output = (result.stdout + result.stderr).strip()
        last_lines = "\n".join(output.split("\n")[-5:])
        print(f"[COMPILE ERROR] Fix compilation in {file_path} before continuing\n{last_lines}")
except Exception:
    pass

sys.exit(0)
