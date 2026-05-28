# Codebase Index — Use FIRST (CACHED snippet)

> **Before grep / glob / view, consult the codebase index.** It is auto-generated on every
> commit and answers "where does X live?" in one MCP call instead of a repo-wide scan.

## Files (under `.codemie/codebase/`)
| File | Use when |
|------|----------|
| `OVERVIEW.md` | You need the project shape / module map |
| `modules/<name>.md` | You need a module's controllers, endpoints, services, configs |
| `symbols.json` | You have a class/interface/enum name → want file:line |
| `MANIFEST.json` | You need machine-readable per-file metadata, package, types |
| `.index-state.json` | You need the indexed git SHA + per-file hash |

## MCP tools (`codebase` server)
- `codebase_overview` — read OVERVIEW.md
- `codebase_module(name)` — read a module summary
- `find_symbol(name)` — O(1) class/interface/enum/record lookup
- `find_files(pattern)` — glob pre-cached file tree
- `list_endpoints(module?)` — every REST endpoint, optional module filter
- `codebase_stats` — counts + freshness
- `reindex(incremental=True)` — rebuild after large local changes

## Rule
1. Read `OVERVIEW.md` once at the start of any non-trivial task.
2. Use `find_symbol` / `codebase_module` / `list_endpoints` for navigation.
3. Fall back to grep / view ONLY if the index does not answer.
