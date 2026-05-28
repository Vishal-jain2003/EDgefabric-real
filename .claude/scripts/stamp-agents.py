"""One-off: stamp each .claude/agents/*.md with:
  1. A `model: <recommended>` line in the frontmatter (added if missing).
  2. A short "## Codebase index" block right after the H1, pointing agents
     at the new shared snippet so they stop grepping blind.

Idempotent — safe to re-run.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
AGENTS = ROOT / ".claude" / "agents"

# Cost-tier routing decisions:
#   haiku  -> light: scanning logs, file lookups, simple checks
#   sonnet -> code & multi-step reasoning
#   opus   -> high-stakes architecture / orchestration
MODEL_MAP = {
    "aws-ssm-deployer":     "sonnet",
    "code-reviewer":        "haiku",
    "dead-code-cleaner":    "haiku",
    "deployment-verifier":  "haiku",
    "docker-image-builder": "haiku",
    "java-implementer":     "sonnet",
    "performance-tester":   "sonnet",
    "pipeline-monitor":     "haiku",
    "scrum-master":         "haiku",
    "sdlc-orchestrator":    "opus",
    "solution-architect":   "opus",
    "test-runner":          "haiku",
    "test-writer":          "sonnet",
}

INDEX_BLOCK = """
## Codebase index — READ FIRST

Before grep / view / glob, consult the auto-generated codebase index:

- `.codemie/codebase/OVERVIEW.md` — module map
- `.codemie/codebase/modules/<module>.md` — controllers, endpoints, services
- `.codemie/codebase/symbols.json` — O(1) class/interface lookup

Or call the `codebase` MCP server: `codebase_overview`, `codebase_module(name)`,
`find_symbol(name)`, `find_files(pattern)`, `list_endpoints(module?)`.

Only fall back to repo-wide scanning if the index doesn't answer.
See `.claude/shared/codebase-index-usage.md` for the full rule set.
"""


MARKER = "## Codebase index — READ FIRST"


def stamp(p: Path) -> str:
    text = p.read_text(encoding="utf-8")
    changed = False

    # 1. Frontmatter model
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end > 0:
            fm = text[3:end]
            wanted = MODEL_MAP.get(p.stem, "sonnet")
            if re.search(r"^model\s*:\s*.+$", fm, re.M):
                new_fm = re.sub(r"^model\s*:\s*.+$", f"model: {wanted}", fm, count=1, flags=re.M)
            else:
                # insert after `name:` line if present, else at top of fm
                if re.search(r"^name\s*:\s*.+$", fm, re.M):
                    new_fm = re.sub(r"(^name\s*:\s*.+$)",
                                    rf"\1\nmodel: {wanted}", fm, count=1, flags=re.M)
                else:
                    new_fm = f"model: {wanted}\n" + fm
            if new_fm != fm:
                text = "---" + new_fm + text[end:]
                changed = True

    # 2. Insert index block right after frontmatter (or after first H1 if no fm).
    if MARKER not in text:
        if text.startswith("---"):
            end = text.find("\n---", 3)
            if end > 0:
                fm_end = end + len("\n---")
                # Skip to end of the frontmatter terminator line
                nl = text.find("\n", fm_end)
                insert_at = nl if nl > 0 else fm_end
                text = text[:insert_at] + "\n" + INDEX_BLOCK + text[insert_at:]
            else:
                text = INDEX_BLOCK + "\n" + text
        else:
            m = re.search(r"^#\s+.+$", text, re.M)
            if m:
                text = text[:m.end()] + "\n" + INDEX_BLOCK + text[m.end():]
            else:
                text = INDEX_BLOCK + "\n" + text
        changed = True

    if changed:
        p.write_text(text, encoding="utf-8")
        return "updated"
    return "unchanged"


def main() -> int:
    for p in sorted(AGENTS.glob("*.md")):
        status = stamp(p)
        print(f"[stamp-agents] {status}: {p.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
