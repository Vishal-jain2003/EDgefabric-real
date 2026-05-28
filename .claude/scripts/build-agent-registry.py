"""Generate .claude/AGENTS_REGISTRY.md from agent + command frontmatter.

Lists each agent with its model, tools, file size, and a summary line. Lets
humans (and other agents) discover what's available without reading every file.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
AGENTS_DIR = ROOT / ".claude" / "agents"
COMMANDS_DIR = ROOT / ".claude" / "commands"
OUT = ROOT / ".claude" / "AGENTS_REGISTRY.md"


def parse_frontmatter(text: str) -> dict:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end < 0:
        return {}
    body = text[3:end].strip()
    out: dict[str, str] = {}
    cur = None
    buf: list[str] = []
    for line in body.splitlines():
        m = re.match(r"^([a-zA-Z_][\w-]*)\s*:\s*(.*)$", line)
        if m and not line.startswith(" "):
            if cur is not None:
                out[cur] = "\n".join(buf).strip()
            cur = m.group(1)
            buf = [m.group(2)]
        else:
            buf.append(line)
    if cur is not None:
        out[cur] = "\n".join(buf).strip()
    return out


def first_line(s: str) -> str:
    return (s or "").splitlines()[0].strip().lstrip("|").strip() if s else ""


def render() -> str:
    lines = [
        "# EdgeFabric Agent & Command Registry (auto-generated)",
        "",
        "> Run `py .claude/scripts/build-agent-registry.py` to refresh.",
        "",
        "## Agents",
        "",
        "| Agent | Model | Size | Description |",
        "|-------|-------|------|-------------|",
    ]
    for p in sorted(AGENTS_DIR.glob("*.md")):
        fm = parse_frontmatter(p.read_text(encoding="utf-8"))
        size_kb = p.stat().st_size // 1024
        lines.append(f"| `{p.stem}` | {fm.get('model','?')} | {size_kb} KB | {first_line(fm.get('description',''))} |")
    lines += ["", "## Slash Commands", "", "| Command | Description |", "|---------|-------------|"]
    for p in sorted(COMMANDS_DIR.glob("*.md")):
        fm = parse_frontmatter(p.read_text(encoding="utf-8"))
        lines.append(f"| `/{p.stem}` | {first_line(fm.get('description',''))} |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    OUT.write_text(render(), encoding="utf-8")
    print(f"[agent-registry] wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
