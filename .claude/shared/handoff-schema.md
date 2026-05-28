# SDLC Handoff Schema (CACHED snippet)

Every stage writes a JSON file under `.codemie/handoff/stage-<N>-<agent>.json`.
The next stage reads it. **Always validate against `.codemie/handoff/SCHEMA.json`.**

## Required fields
| Field | Type | Notes |
|-------|------|-------|
| `agent` | string | producer agent name |
| `stage` | string | e.g. `0.5`, `1`, `2a`, `2b`, `3` |
| `ticket` | string\|null | Jira key (or `null` for ad-hoc work) |
| `status` | enum | `ok` \| `blocked` \| `failed` |
| `written_at` | string | ISO-8601 UTC `YYYY-MM-DDTHH:MM:SSZ` |

## Common optional fields
- `files_created`, `files_modified` — relative paths
- `compile_result` — `BUILD SUCCESS` / `BUILD FAILURE`
- `tests_run`, `tests_passed`, `tests_failed`
- `coverage_pct` — float 0-100
- `notes`, `next_stage`

## Result token (last line of agent output)
```
<AGENT>_RESULT: {"status":"ok", ...}
```

## Validation
- Standalone: `py .claude/scripts/validate-handoff.py <path>` (exit 1 on bad)
- PostToolUse hook re-checks every change to `.codemie/handoff/*.json`.
- Bad handoff → next stage refuses to start (avoid silent drift).
