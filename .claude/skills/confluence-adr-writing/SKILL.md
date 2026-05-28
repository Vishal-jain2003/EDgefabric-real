---
name: confluence-adr-writing
description: How to write an Architecture Decision Record for the EdgeFabric (EF) Confluence space. Load this whenever the task mentions ADR, architecture decision, design doc, Confluence page, or invokes the solution-architect agent.
---

# Skill: Confluence ADR Writing

## Where ADRs live

- Confluence space: **EF**
- Parent page: "Architecture Decision Records" (search by title)
- Naming: `ADR-<NNN>: <decision in present tense>`
  (e.g. `ADR-024: Use phi-accrual failure detection over fixed-timeout`)

## Required structure

Use this exact section order — the `solution-architect` agent and downstream
readers depend on it.

```
# ADR-NNN: <Title>

## Status
Proposed | Accepted | Superseded by ADR-XXX | Deprecated

## Context
2–4 sentences. What forces are in play? What's broken / missing today?
Link to the Jira ticket: [EF-42](jira-url)

## Decision
One sentence. Active voice, present tense. "We will ..."

## Strategies considered
Exactly 3. For each:
- **Strategy N — <name>**
  - Approach (1 paragraph)
  - Pros (bullets)
  - Cons (bullets)
  - Cost / complexity score (1–5)

## Scoring
| Criterion | Weight | S1 | S2 | S3 |
|-----------|--------|----|----|----|
| Performance | 0.3 | … | … | … |
| Operational complexity | 0.3 | … | … | … |
| Time to ship | 0.2 | … | … | … |
| Reversibility | 0.2 | … | … | … |
| **Weighted total** | | … | … | … |

## Chosen strategy
Name + 1-paragraph justification referring to the score.

## Consequences
- Positive (bullets)
- Negative / risks (bullets)
- Follow-up work (link new Jira tickets)

## References
- Code: `.codemie/codebase/modules/<module>.md`
- Related ADRs
- External docs / RFCs
```

## Tone & style

- Present tense, active voice. "We will ..." not "It is recommended that ..."
- No marketing language. No "robust", "seamless", "leverage".
- Numbers over adjectives. "p99 latency drops 40 ms" not "much faster".
- Link, don't quote — link to source files via the codebase index URL.

## Mandatory cross-links

After publishing:

1. Comment on the Jira ticket with the ADR URL via
   `atlassian-add_issue_comment`.
2. Save a local copy to `.codemie/specs/<TICKET>/spec.md` (the developer
   agents read this, not Confluence).
3. Update the parent ADR index page to include the new ADR.

## Length budget

- Context: ≤ 100 words
- Each strategy: ≤ 150 words
- Whole ADR: ≤ 1200 words

If you're over budget, you're explaining instead of deciding. Move details
into the linked code or follow-up tickets.

## Anti-patterns

- ❌ Listing more than 3 strategies (decision fatigue)
- ❌ "We considered everything and there was no other option" (no real comparison)
- ❌ Embedding code blocks > 20 lines (link to file:line instead)
- ❌ Vague status ("In progress") — must be one of the 4 enums
- ❌ Forgetting to link the Jira ticket back
