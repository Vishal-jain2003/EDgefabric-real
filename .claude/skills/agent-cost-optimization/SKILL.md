---
name: agent-cost-optimization
description: How to keep token spend and wallclock low when running EdgeFabric agents — model selection, caching, skip-if-unchanged, parallelization, output budgets. Load this whenever launching a long agent run, an SDLC stage, or anything that might exceed token budgets.
---

# Skill: Agent Cost & Performance Optimization

## 1. Model selection

Right-size the model per task. Default mappings already in agent frontmatter:

| Tier | Models | Use for |
|------|--------|---------|
| Cheap | `haiku` | I/O work — code review, test running, log parsing, scrum gates, deployment verification |
| Standard | `sonnet` | Code generation, AWS deploy logic, performance test design |
| Premium | `opus` | Architecture, ADR strategies, top-level orchestration |

**Rule:** if a `haiku` agent finds itself doing high-stakes architectural
reasoning, it must set `needs_escalation: true` in its handoff JSON
instead of guessing. The orchestrator will re-dispatch.

## 2. Skip-if-unchanged

Before doing real work, gate every agent on:

```bash
py .claude/scripts/skip-if-unchanged.py <agent-name> <relevant files...>
# prints SKIP or RUN
```

If `SKIP`: exit 0 with one line "no changes since last run". Hash inputs
specific to your agent:

| Agent | Hash these |
|-------|-----------|
| `code-reviewer` | `git diff --name-only HEAD~1` output |
| `test-runner` | module `pom.xml` + `src/main/java/**` |
| `docker-image-builder` | `Dockerfile` + `target/*.jar` |
| `deployment-verifier` | currently-running image tag per instance |

## 3. Reuse cached context (NEVER re-fetch)

| Cache | Read first, fetch only on miss |
|-------|-------------------------------|
| `.codemie/context/<JIRA_KEY>.json` | Jira ticket details (created by scrum-master) |
| `.codemie/codebase/` | Codebase index (see `edgefabric-architecture` skill) |
| `.codemie/handoff/stage-*.json` | Prior stage results — don't re-derive |
| `cache-mcp.py` hook | Auto-caches MCP responses with TTL |

## 4. Parallelize where safe

After `java-implementer` finishes, the orchestrator MUST fan out these
three in parallel (one turn, three Task calls with `mode: background`):

- `test-writer` (any new code paths)
- `test-runner` (existing tests)
- `code-reviewer` (the diff)

They have **no inter-dependencies**. Sequential = wasted wallclock.

## 5. Output budgets

- Each agent's output **must fit in `output_max_tokens` (default 2000)**.
- Don't repeat the spec back — link to it.
- Use tables, not paragraphs. Code blocks only when essential.
- Truncate big payloads:
  - CloudWatch logs: tail 200 lines max
  - PR diffs > 500 lines: per-file summary, don't quote
  - Maven test output: failures only

## 6. Token budget hard limits

The `token-budget.py` Stop-hook warns when a single agent run exceeds:

| Resource | Limit |
|----------|-------|
| Total tool calls | 60 |
| Bash calls | 25 |
| MCP calls | 40 |

If you hit a budget: STOP and emit a partial result with
`status: blocked, blocker: "exceeded budget X"`. Do **not** continue silently.

## 7. MCP response caching

The `cache-mcp.py` PreToolUse hook returns a cached response if the same
MCP call was made recently with identical args. Typical hit rate ~40%.

**To benefit:** call MCP tools with stable, deterministic arguments —
don't include timestamps or random IDs in the request payload.

## 8. Audit trail (free observability)

Every tool call lands in:
- `.codemie/audit/bash-<date>.jsonl`
- `.codemie/audit/mcp-<date>.jsonl`

Use `py .claude/scripts/usage-report.py` to see per-agent token spend
per Jira ticket — feed this back into model selection decisions.
