# EdgeFabric — GitHub Copilot Instructions

See `CLAUDE.md` and `AGENTS.md` at the repo root for the canonical project rules
and conventions. They are mirrored here so GitHub Copilot CLI / IDE plugins pick
them up automatically.

## Codebase memory — READ FIRST

Before grepping or scanning the repo, consult the auto-generated codebase index:

- `.codemie/codebase/OVERVIEW.md` — TL;DR of modules, ports, navigation
- `.codemie/codebase/modules/<module>.md` — controllers, endpoints, services, configs
- `.codemie/codebase/symbols.json` — class/interface/enum → file:line lookup
- `.codemie/codebase/MANIFEST.json` — full machine-readable file metadata

Or use the `codebase` MCP server: `codebase_overview`, `codebase_module(name)`,
`find_symbol(name)`, `find_files(pattern)`, `list_endpoints(module?)`,
`codebase_stats`, `reindex(incremental=True)`.

Only fall back to grep / view / glob if the index does not answer.
The index refreshes via a pre-commit hook and a Jenkins CI gate.

## Shared snippets to consult

- `.claude/shared/project-context.md` — modules, ports, deployment
- `.claude/shared/java-conventions.md` — layering, DI, exceptions, async, tests
- `.claude/shared/codebase-index-usage.md` — index usage rules
- `.claude/shared/handoff-schema.md` — SDLC handoff JSON contract
