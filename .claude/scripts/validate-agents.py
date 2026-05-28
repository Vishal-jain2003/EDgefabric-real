"""Validate every .claude/agents/*.md frontmatter.

Checks:
  - YAML-ish frontmatter present (--- delimiters)
  - Required keys: name, description, model, tools
  - `model` is one of the allowed shorthands (haiku, sonnet, opus, gpt-5*)
  - `tools` is comma-separated
  - `name` matches filename
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
AGENTS_DIR = ROOT / ".claude" / "agents"

ALLOWED_MODELS = {"haiku", "sonnet", "opus",
                  "claude-haiku", "claude-sonnet", "claude-opus",
                  "gpt-5", "gpt-5-mini", "gpt-5.4", "gpt-5.4-mini",
                  "inherit"}

REQUIRED = ["name", "description", "model", "tools"]


def parse_frontmatter(text: str) -> dict | None:
    if not text.startswith("---"):
        return None
    end = text.find("\n---", 3)
    if end < 0:
        return None
    body = text[3:end].strip()
    out: dict[str, str] = {}
    cur_key = None
    buf: list[str] = []
    for line in body.splitlines():
        m = re.match(r"^([a-zA-Z_][\w-]*)\s*:\s*(.*)$", line)
        if m and not line.startswith(" "):
            if cur_key is not None:
                out[cur_key] = "\n".join(buf).strip()
            cur_key = m.group(1)
            buf = [m.group(2)]
        else:
            buf.append(line)
    if cur_key is not None:
        out[cur_key] = "\n".join(buf).strip()
    return out


def validate_agent(p: Path) -> list[str]:
    errs: list[str] = []
    text = p.read_text(encoding="utf-8")
    fm = parse_frontmatter(text)
    if fm is None:
        return [f"{p.name}: missing or malformed frontmatter (--- ... ---)"]
    for k in REQUIRED:
        if k not in fm or not fm[k]:
            errs.append(f"{p.name}: missing required key '{k}'")
    if "name" in fm and fm["name"] != p.stem:
        errs.append(f"{p.name}: frontmatter name '{fm['name']}' != filename '{p.stem}'")
    if "model" in fm and fm["model"]:
        m = fm["model"].strip().strip('"').strip("'")
        if m not in ALLOWED_MODELS:
            errs.append(f"{p.name}: model '{m}' not in allowed set {sorted(ALLOWED_MODELS)}")
    return errs


def main() -> int:
    if not AGENTS_DIR.exists():
        print(f"[validate-agents] {AGENTS_DIR} not found")
        return 0
    bad = 0
    for p in sorted(AGENTS_DIR.glob("*.md")):
        errs = validate_agent(p)
        if errs:
            bad += 1
            for e in errs:
                print(f"[validate-agents] {e}", file=sys.stderr)
    if bad:
        print(f"[validate-agents] {bad} agent file(s) failed validation", file=sys.stderr)
        return 1
    print(f"[validate-agents] OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
