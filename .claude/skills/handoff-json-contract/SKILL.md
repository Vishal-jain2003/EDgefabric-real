---
name: handoff-json-contract
description: How to write a valid stage-handoff JSON so the next agent can pick up the work. Load this whenever an agent finishes a stage, writes to .codemie/handoff/, or you see a HANDOFF / RESULT token in agent output.
---

# Skill: SDLC Handoff JSON Contract

Every SDLC stage writes one JSON file under
`.codemie/handoff/stage-<N>-<agent>.json`. The next stage reads it as its
**only** context — a malformed handoff blocks the pipeline.

## File location pattern

```
.codemie/handoff/
├── stage-0.5-scrum-master.json
├── stage-1-solution-architect.json
├── stage-2a-test-writer.json
├── stage-2b-java-implementer.json
├── stage-2c-test-runner.json
├── stage-3a-code-reviewer.json
├── stage-4b-docker-image-builder.json
├── stage-6b-deployment-verifier.json
└── ...
```

## Required fields

| Field | Type | Notes |
|-------|------|-------|
| `agent` | string | Producing agent name (matches `.claude/agents/*.md` name) |
| `stage` | string | `0.5`, `1`, `2a`, `2b`, `3a`, `4b`, `6b`, etc. |
| `ticket` | string \| null | Jira key, or `null` for ad-hoc work |
| `status` | enum | `ok` \| `blocked` \| `failed` |
| `written_at` | string | ISO-8601 UTC: `2026-05-14T16:30:00Z` |

## Common optional fields

```json
{
  "files_created": ["caching/src/main/java/.../X.java"],
  "files_modified": ["pom.xml"],
  "compile_result": "BUILD SUCCESS",
  "tests_run": 142,
  "tests_passed": 142,
  "tests_failed": 0,
  "coverage_pct": 84.7,
  "notes": "Followed strategy 2 from ADR-024",
  "next_stage": "3a",
  "needs_escalation": false
}
```

`needs_escalation: true` is the cheap-model escape hatch — set it when a
`haiku` agent encounters reasoning above its pay grade. The orchestrator
will re-dispatch to a `sonnet` or `opus` agent.

## Result token (last line of agent output)

The orchestrator parses **only the last line** of stdout to read status:

```
SCRUM_MASTER_RESULT: {"status":"ok","ticket":"EPMICMPHE-42","next_stage":"1"}
SOLUTION_ARCHITECT_RESULT: {"status":"ok","spec_path":".codemie/specs/EPMICMPHE-42/spec.md"}
JAVA_IMPLEMENTER_RESULT: {"status":"ok","compile_result":"BUILD SUCCESS"}
```

Token format: `<UPPER_SNAKE_AGENT_NAME>_RESULT: <minified-json>`.

## Validation

```bash
# Standalone
py .claude/scripts/validate-handoff.py .codemie/handoff/stage-2b-java-implementer.json

# Auto: PostToolUse hook re-checks every Edit/Write to .codemie/handoff/*.json
# Bad handoff → exit 1 → next stage refuses to start (avoids silent drift)
```

## When `status: blocked`

Always include a `blocker` field with a human-readable reason and a
`requires` field listing what would unblock you:

```json
{
  "status": "blocked",
  "blocker": "Acceptance criteria #3 ambiguous — does 'eventually' mean ≤5s or ≤30s?",
  "requires": "human_clarification",
  "next_stage": null
}
```

The orchestrator will surface the blocker to the human and pause the
pipeline — never silently skip and continue.

## When `status: failed`

Include `error` (one line) and `details` (longer trace, ≤ 500 chars):

```json
{
  "status": "failed",
  "error": "mvn compile exited 1 in caching module",
  "details": "CacheService.java:142: cannot find symbol GossipDigestDTO.builder()",
  "next_stage": null
}
```

The `pipeline-monitor` agent reads `error`/`details` to decide whether to
auto-retry (max 3) or escalate.
