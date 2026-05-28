# EdgeFabric — Agent Instructions

> This file mirrors `CLAUDE.md` so the GitHub Copilot CLI (which reads
> `AGENTS.md` in the git root and cwd) sees the same project rules.
> Update both, or update `CLAUDE.md` and run `py .claude/scripts/sync-instructions.py`
> (TODO) to copy.

See `CLAUDE.md` at the repo root for the full canonical instructions, and
the following shared snippets that every agent should consult:

- `.claude/shared/project-context.md` — modules, ports, deployment, branching
- `.claude/shared/java-conventions.md` — layering, DI, exceptions, async, tests
- `.claude/shared/codebase-index-usage.md` — read this BEFORE grepping
- `.claude/shared/handoff-schema.md` — SDLC handoff JSON contract
- `.codemie/codebase/OVERVIEW.md` — auto-generated codebase map (READ FIRST)

## Codebase memory (READ FIRST)

Before scanning the repo, consult the auto-generated index under
`.codemie/codebase/` or call the `codebase` MCP server tools:
`codebase_overview`, `codebase_module(name)`, `find_symbol(name)`,
`find_files(pattern)`, `list_endpoints(module?)`, `codebase_stats`.

The index is kept fresh by a pre-commit hook (`py scripts/install-hooks.py`
once per clone) and a CI gate (`py mcp-servers/codebase_indexer.py --check`).
