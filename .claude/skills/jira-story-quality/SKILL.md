---
name: jira-story-quality
description: How to write a Jira story that passes the scrum-master gate — Definition of Ready, acceptance criteria format, sizing, epic linking. Load this whenever creating, updating, or reviewing a Jira story for EPMICMPHE.
---

# Skill: Jira Story Quality (Definition of Ready)

The `scrum-master` agent runs 7 hard gates and 5 warning checks against
every story before the SDLC can proceed. Write your story to pass all of them
on the first try.

## Hard gates (BLOCK if any fail)

| # | Gate | Pass criterion |
|---|------|----------------|
| 1 | Story exists | Real Jira key, not deleted |
| 2 | Issue type | `Story` (not Task / Bug / Epic) |
| 3 | Sprint membership | Currently in an open sprint |
| 4 | Status | `To Do` or `Selected for Development` (not `In Progress`/`Done`) |
| 5 | Description quality | ≥ 50 words, mentions context + outcome |
| 6 | Acceptance criteria | ≥ 1 Gherkin scenario (Given/When/Then) |
| 7 | No active blockers | No "Is blocked by" link to an open issue |

## Warning gates (proceed but log)

- Assignee set (warn if unassigned)
- Story points estimated (warn if null)
- Epic link present (warn if orphan)
- Priority set (warn if `Lowest`/null)
- Summary quality (warn if < 5 words or all uppercase)

## Acceptance criteria format

Use Gherkin. Each scenario must be independently testable.

```gherkin
Scenario: New cache node joins the cluster
  Given a 3-node cache cluster running v1.4.0
  When a new node starts and registers in Cloud Map
  Then the LB ring rebuilds within 5 seconds
  And no client requests fail during the rebuild
  And the new node owns ~25% of the keyspace
```

For non-trivial stories include 3–5 scenarios covering happy path, edge
cases, and failure modes.

## Description template

```
## Why
1–2 sentences on the user / operational pain.

## What
What changes in observable behavior. NOT implementation.

## Out of scope
Explicit list — protects against scope creep at review time.

## Links
- ADR: (added later by solution-architect)
- Related tickets: EF-XX, EF-YY
- Confluence runbook: (if applicable)
```

## Sizing (Fibonacci)

| Points | Meaning |
|--------|---------|
| 1 | Trivial config change, < 1 hour |
| 2 | Simple endpoint or bug fix |
| 3 | Service method + tests + docs |
| 5 | New module-internal feature |
| 8 | Cross-module change (LB + cache) |
| 13 | Needs an ADR — too big, split it |

If you're tempted to estimate 13 → use the `solution-architect` skill
first to break it into 5+8 or 3+5+5.

## Epic linking

Every story should link to an epic. Use `atlassian-find_best_epic` with
the description text — it scores all open epics by keyword overlap and
domain signals.

```python
atlassian-find_best_epic(feature_description="...")
# → returns top epic + ranked shortlist + reasoning
```

Then attach: `atlassian-link_issues(inward=story, outward=epic, link_type="Epos-Story Link")`.

## Duplicate detection (mandatory)

Before creating, search for near-duplicates:

```jql
project = EPMICMPHE
  AND statusCategory != Done
  AND (summary ~ "<keywords>" OR description ~ "<keywords>")
ORDER BY updated DESC
```

If anything > 70% similar surfaces → **confirm with the user** before
creating a new story. Otherwise you trip the duplicate gate later.
