"""Validate a handoff JSON file against .codemie/handoff/SCHEMA.json.

Used in two ways:
  - Standalone: `py .claude/scripts/validate-handoff.py <path>` (exit 1 on bad)
  - PostToolUse hook: stdin JSON from Claude / Copilot, reads tool_input.file_path

No external dependencies (no jsonschema lib). Implements only the constructs
used by SCHEMA.json (type, required, enum, pattern, minimum, maximum, items).
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
SCHEMA_PATH = REPO_ROOT / ".codemie" / "handoff" / "SCHEMA.json"


def _type_ok(value, t):
    if isinstance(t, list):
        return any(_type_ok(value, x) for x in t)
    return {
        "string":  isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "number":  isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "object":  isinstance(value, dict),
        "array":   isinstance(value, list),
        "null":    value is None,
    }.get(t, False)


def validate(instance: dict, schema: dict, path: str = "$") -> list[str]:
    errs: list[str] = []
    if "type" in schema and not _type_ok(instance, schema["type"]):
        errs.append(f"{path}: expected type {schema['type']}, got {type(instance).__name__}")
        return errs
    if isinstance(instance, dict):
        for req in schema.get("required", []):
            if req not in instance:
                errs.append(f"{path}: missing required field '{req}'")
        for key, sub_schema in schema.get("properties", {}).items():
            if key in instance:
                errs.extend(validate(instance[key], sub_schema, f"{path}.{key}"))
    if isinstance(instance, str):
        if "minLength" in schema and len(instance) < schema["minLength"]:
            errs.append(f"{path}: length {len(instance)} < minLength {schema['minLength']}")
        if "enum" in schema and instance not in schema["enum"]:
            errs.append(f"{path}: '{instance}' not in {schema['enum']}")
        if "pattern" in schema and not re.search(schema["pattern"], instance):
            errs.append(f"{path}: does not match pattern {schema['pattern']}")
    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            errs.append(f"{path}: {instance} < minimum {schema['minimum']}")
        if "maximum" in schema and instance > schema["maximum"]:
            errs.append(f"{path}: {instance} > maximum {schema['maximum']}")
    if isinstance(instance, list) and "items" in schema:
        for i, item in enumerate(instance):
            errs.extend(validate(item, schema["items"], f"{path}[{i}]"))
    return errs


def validate_file(path: Path) -> list[str]:
    if not path.exists():
        return [f"{path}: file not found"]
    try:
        instance = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        return [f"{path}: invalid JSON - {e}"]
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return validate(instance, schema)


def _hook_mode() -> int:
    """Read tool_input.file_path from stdin (PostToolUse hook payload)."""
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0
    fp = (data.get("tool_input") or {}).get("file_path", "")
    if not fp:
        return 0
    p = Path(fp)
    # Only validate handoff files
    norm = str(p).replace("\\", "/")
    if "/.codemie/handoff/" not in norm or not norm.endswith(".json") or norm.endswith("SCHEMA.json"):
        return 0
    errs = validate_file(p)
    if errs:
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PostToolUse",
                "additionalContext": "[handoff-schema] INVALID:\n  " + "\n  ".join(errs),
            }
        }))
    return 0


def main(argv: list[str]) -> int:
    if not argv:
        # Hook mode (stdin JSON)
        if not sys.stdin.isatty():
            return _hook_mode()
        print("usage: validate-handoff.py <handoff.json>", file=sys.stderr)
        return 2
    bad = 0
    for arg in argv:
        errs = validate_file(Path(arg))
        if errs:
            bad += 1
            print(f"[handoff-schema] {arg}:", file=sys.stderr)
            for e in errs:
                print(f"  - {e}", file=sys.stderr)
        else:
            print(f"[handoff-schema] {arg}: OK")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
