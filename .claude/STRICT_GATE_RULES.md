# Strict Gate Enforcement for Jira Story Implementation

## Overview

All EdgeFabric agents that implement Jira stories enforce **STRICT gate checks** through the
`scrum-master` agent. The scrum-master is the single source of truth for story readiness —
no other agent should inline its own sprint/status/description/AC checks.

Gate checks are **centralized** in `.claude/agents/scrum-master.md`.

---

## The Scrum Master Agent

The `scrum-master` agent runs all gates in sequence and returns one of:
- `SCRUM_MASTER_RESULT: APPROVED` — story is ready, implementation may proceed
- `SCRUM_MASTER_RESULT: BLOCKED` — one or more gates failed, exit immediately

Callers invoke it as:
```
Agent(subagent_type="scrum-master", prompt="<JIRA_KEY>")
```
Then check the last line of its response for `SCRUM_MASTER_RESULT:`.

---

## Gate Tiers

### Tier 1 — BLOCKER Gates (hard stop, no bypass)

| Gate | Name | Rule |
|------|------|------|
| G1 | Story Exists | Story must be fetchable from Jira |
| G2 | Issue Type | Must be `Story`, `User Story`, or `Feature` |
| G3 | Sprint Membership | Must be in an open sprint |
| G4 | Status | Must be `To Do` or `In Progress` |
| G5 | Description Quality | Must exist, ≥50 chars, not a placeholder |
| G6 | Acceptance Criteria | Must exist, ≥3 criteria, not a placeholder |
| G7 | No Active Blockers | No linked "is blocked by" issues still open |

**On any BLOCKER failure:** the scrum-master emits a 🛑 message explaining what must be fixed
in Jira, then returns `SCRUM_MASTER_RESULT: BLOCKED`. The caller exits immediately — no bypass.

### Tier 2 — WARNING Gates (auto-proceed, logged)

| Gate | Name | Rule |
|------|------|------|
| W1 | Assignee Set | Story should be assigned to a developer |
| W2 | Story Points Set | Story should have a point estimate |
| W3 | Epic Link Set | Story should be linked to an epic |
| W4 | Priority Set | Story should have a priority (not None) |
| W5 | Summary Quality | Summary should be meaningful (≥15 chars, no vague patterns) |

Warnings are emitted as ⚠️  messages but do not stop execution.

### Tier 3 — DUPLICATE Gates (user confirmation required)

| Gate | Name | Rule |
|------|------|------|
| D1 | Exact Duplicate | Same JIRA_KEY not already in commits, merged MRs, or branches |
| D2 | Semantic Duplicate | No Done/Closed tickets or existing code with overlapping keywords |

On duplicate detection: agent pauses, presents evidence, and awaits user reply
(`"stop"` / `"force"` for D1, `"stop"` / `"proceed"` / `"proceed with context: <note>"` for D2).

---

## Blocked Statuses (G4)

All statuses except `To Do` and `In Progress` are **BLOCKED**:

| Status Category | Examples | Why Blocked |
|----------------|----------|-------------|
| Not Ready | Backlog, PO Review, Open | Not approved or sprint-planned |
| Already Underway | In Review, Code Review, Testing, QA | Work complete — under review |
| Complete | Done, Resolved, Closed | Work finished — do not re-implement |
| Paused / Invalid | On Hold, Blocked, Canceled | Requires SM/PO action first |

---

## Affected Callers

All callers delegate to the scrum-master agent. No inline gate checks exist in callers.

### 1. sdlc-orchestrator.md
- **Stage 0.5**: delegates entirely to scrum-master
- On `BLOCKED`: exits, writes session state as `blocked at Stage 0.5`

### 2. solution-architect.md
- **Phase 1**: delegates to scrum-master (unless `SKIP_GATES=true` is set by the orchestrator)
- On `BLOCKED`: exits immediately, prints guidance from the gate report

### 3. tdd-implement.md (command)
- **Step 4**: invokes scrum-master after confirming spec exists
- On `BLOCKED`: prints gate failure message and stops before any TDD work begins

### 4. Other Agents (No Changes Needed)
- **java-implementer** — works from approved specs, no Jira fetching
- **test-writer** — writes tests from specs, no Jira fetching
- **test-runner** — executes tests, no Jira fetching
- **deployment-verifier** — verifies deployments, no Jira fetching

---

## Why Each Gate Exists

| Gate | Why It Matters |
|------|---------------|
| G1 (Exists) | Prevents running the pipeline against a typo or deleted ticket |
| G2 (Type) | Epics need decomposition; bugs need root-cause analysis; tasks aren't user stories |
| G3 (Sprint) | Prevents unauthorized work on backlog items not yet sprint-planned |
| G4 (Status) | Prevents re-implementing already-done work or working on blocked/paused items |
| G5 (Description) | A story without context gives the developer nothing to build from |
| G6 (Acceptance Criteria) | Without ACs, "done" is undefined — the PR can never be confidently reviewed |
| G7 (Blockers) | Starting blocked work wastes effort — unblock first |
| W1 (Assignee) | Unassigned stories risk being double-worked or forgotten |
| W2 (Points) | Unestimated stories can't be tracked in sprint velocity |
| W3 (Epic) | Orphan stories can't be tracked in roadmap or release planning |
| W4 (Priority) | Unprioriatized stories make sprint ordering arbitrary |
| W5 (Summary) | Vague summaries create ambiguity about what is being built |
| D1 (Exact Dup) | Prevents re-implementing the same feature from scratch |
| D2 (Semantic Dup) | Prevents building the same functionality under a different ticket |

---

## Correct Workflow

```
1. PO creates ticket with full description + ≥3 ACs
2. SM adds ticket to current sprint and sets priority
3. Dev is assigned; story points estimated
4. Epic is linked
5. Dev runs /full-sdlc EPMICMPHE-42
6. scrum-master agent runs all gates — all PASS
7. Implementation proceeds
```

## Blocked Workflow Example

```
1. Ticket exists but description says "TBD"
2. Dev runs /design-story EPMICMPHE-42
3. scrum-master: ✅ G1 G2 G3 G4 — then...
4. 🛑 G5 BLOCKED: description is a placeholder "TBD"
5. Agent exits — SCRUM_MASTER_RESULT: BLOCKED
6. Dev asks PO to fill in the description
7. Dev re-runs /design-story — all gates pass this time
```

---

## Summary

**One Rule to Remember:**

> **ONLY implement stories where:**
> 1. The story exists and is a Story/User Story/Feature type
> 2. It is in the current sprint
> 3. Status is "To Do" or "In Progress"
> 4. It has a real description (not placeholder, ≥50 chars)
> 5. It has ≥3 acceptance criteria (not placeholder)
> 6. It has no active blockers
>
> **Everything else is BLOCKED with no bypass.**
> Warnings (assignee, points, epic, priority, summary quality) are noted but do not block.
