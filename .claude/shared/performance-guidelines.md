# Performance & Cost Guidelines for Agents (CACHED snippet)

Read this once at the start of any non-trivial run. Every agent should follow
these rules to minimise wallclock and $$.

## 1. Skip-if-unchanged
Before doing real work, run:
```bash
py .claude/scripts/skip-if-unchanged.py <agent-name> <relevant files...>
```
If it prints `SKIP`, exit 0 with a one-line "no changes since last run" note.

Examples:
- `code-reviewer`: hash `git diff --name-only HEAD~1`
- `test-runner`: hash module pom.xml + src/main/java
- `docker-image-builder`: hash Dockerfile + target/*.jar

## 2. Reuse cached context
- Jira issue: read `.codemie/context/<KEY>.json` BEFORE calling `mcp__atlassian__get_issue`
- Codebase: ALWAYS use `.codemie/codebase/` (see `codebase-index-usage.md`)
- Handoff: read prior `.codemie/handoff/stage-*.json` instead of re-deriving

## 3. Parallelise where safe
The `sdlc-orchestrator` MUST fan out these in parallel after `java-implementer` finishes:
- `test-writer`
- `test-runner` (on previously-existing tests)
- `code-reviewer` (on the diff)

They do not depend on each other. Use the Task tool with multiple `mode: background` calls in one turn.

## 4. Be terse
- Output must fit in `output_max_tokens` (default 2000)
- Don't repeat the spec back; link to it
- Use tables, not paragraphs
- Code blocks only when essential

## 5. Token budget
A Stop-hook (`token-budget.py`) warns if a single run exceeds:
- 60 total tool calls
- 25 bash calls
- 40 MCP calls
If you hit the budget, STOP and emit a partial result rather than continuing.

## 6. Truncate big payloads
- CloudWatch logs: tail 200 lines max
- PR diffs > 500 lines: summarise per file, don't quote
- Maven test output: keep failures only

## 7. Model self-awareness
Your model is set in your frontmatter (`model: haiku|sonnet|opus`). If you find
yourself doing high-stakes architectural reasoning on `haiku`, escalate by
recording a `needs_escalation: true` flag in your handoff JSON instead of
guessing.
