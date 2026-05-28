# Memory protocol — durable recall across all EdgeFabric workflows

> **Single source of truth.** All commands and agents that interact with the recall store
> at `~/.claude/memory/edgefabric.db` MUST follow this protocol. Workflow files reference
> this file rather than re-stating the rules. Per-workflow files only contribute their
> own **event → topic/body table**.

## Tooling

- Script: `.claude/scripts/recall.py` (commands: `remember`, `recall`, `stats`)
- Auto-injected on entry: `.claude/hooks/prompt-context.py` already calls
  `recall.py recall --ticket <KEY> --limit 5 --max-tokens 500` whenever the user prompt
  contains a Jira key. **You do not need to repeat this read** unless your workflow needs
  to recall by *topic* (similarity) rather than by *ticket* (exact match).
- Disable knob: `RECALL_DISABLED=1` env var skips all reads (writes still happen).

## When to READ explicitly

Add an explicit `recall.py recall` call in your Stage 0 only if:

1. The workflow needs **similarity / topic** lookup (e.g. "have we seen this RCA before?"),
   not just ticket-keyed recall, OR
2. The workflow may run **without a Jira key in the prompt** (e.g. `/release vX.Y.Z`,
   `/deploy <env>`, `/check-quality`), so the auto-hook misses it.

```powershell
py .claude/scripts/recall.py recall --agent <workflow> --topic "<one-line summary>" --limit 5 --max-tokens 600
# Optional: also recall by ticket if known
py .claude/scripts/recall.py recall --topic "<JIRA_KEY>" --limit 10
```

Print the recalled bullets verbatim under a `## Prior memory` heading so the user/operator
sees the prior decisions before any work begins.

## When to WRITE (mandatory)

Call `recall.py remember` at every meaningful event. The default cadence is:

- After every `✔ STAGE … COMPLETE` line
- At every gate verdict (`PASS` / `FAIL` / `BLOCKED`)
- At every escalation or hand-off to another workflow
- At the final outcome (`FIXED` / `RELEASED` / `DEPLOYED` / `MERGED` / `BLOCKED` / `ESCALATED`)

```powershell
py .claude/scripts/recall.py remember `
  --agent <workflow-or-agent-name> `
  --ticket "$JIRA_KEY"               `# optional but strongly preferred
  --topic "<short event name>"       `# searchable phrase, ≤ 60 chars
  --tags "<workflow>,<source>,<module>" `
  --body "<one-line signal — paths, sha, test name, rule id, version>"
```

### Body content rules

- **≤ 200 characters.** These are signals for future search, not transcripts.
- Include hard identifiers (sha, MR iid, build number, version, file path, test class, rule id).
- Avoid prose narration ("we then decided…"). Just the fact.
- One event = one `remember` call. Don't batch unrelated events.

### Topic content rules

- Start with the workflow name or stage so listings sort sensibly:
  `Gate 4 PASS`, `Stage RCA done`, `Release v1.4.0 deployed`, `Hotfix prod-fire NPE`.
- Include the `JIRA_KEY` or version number when applicable — searchable.

## Failure handling

Memory is **best-effort**. If a `recall.py` call fails (DB locked, missing, permission,
script crash):

```
[recall] read failed (non-fatal)    ← log this and continue
[recall] write failed (non-fatal)
```

**Never abort the workflow because of a memory error.** The workflow's primary job is the
fix/release/deploy — durable memory is value-add, not a gate.

## Auto-compact

After every `✔ STAGE … COMPLETE` and any time the workflow has delegated to ≥ 3 sub-agents
in a single stage, delegate to the `context-compactor` agent. The compactor itself calls
`recall.py remember` with durable lessons learned, then returns a compact briefing the next
stage can use as its only context.

## Why this matters

| Benefit | What it enables |
|---------|-----------------|
| Duplicate detection | Same RCA reported months apart surfaces the prior fix |
| Faster triage | `recall --topic <key>` returns prior test class names + introducing commits |
| Cross-session continuity | Decisions survive context window resets and re-runs |
| `/recall` UX | Users get the stored knowledge via `/recall <topic>` |
| Trend signals | Repeated entries (e.g. "Gate 4 FAIL — duplicate") expose process problems |

## Anti-patterns

- ❌ Inlining this protocol into a workflow file (link here instead — DRY)
- ❌ Writing transcripts (≤ 200 chars / entry, signals only)
- ❌ Aborting a workflow because `recall.py` returned non-zero
- ❌ Reading by ticket when `prompt-context.py` already did it (waste)
- ❌ Skipping writes "because nothing important happened" — every gate verdict is important
- ❌ Topics like `done` or `ok` (unsearchable) — always include workflow + stage + ticket/version

## What each workflow file MUST contribute

Just an **events table** — per-workflow signals worth remembering. Keep it short (3–10 rows):

```markdown
## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol, env vars, failure handling,
> auto-compact, and body/topic rules. This table lists only the events specific to
> this workflow.

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| ...   | ...       | ...                |
```
