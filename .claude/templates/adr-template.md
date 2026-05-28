# ADR-{{JIRA_KEY}}: {{STORY_SUMMARY}}

**Date:** {{DATE}}
**Status:** Proposed
**Author:** EdgeFabric AI Architect
**Jira:** {{JIRA_BASE_URL}}/browse/{{JIRA_KEY}}

## Context

{{2-3 paragraphs: problem space, existing system constraints, why a decision is needed}}

## Decision Drivers

{{Bullet list of key requirements derived from the acceptance criteria}}

## Considered Options

### Strategy 1: {{Name}}

**Summary:** One-sentence description.

**What changes:**
- Module X: specific change
- Module Y: specific change

**Pros:**
- bullet

**Cons / Risks:**
- bullet

**Estimated effort:** S / M / L (S=1-2 days, M=3-5 days, L=1-2 weeks)

**Test surface:** Which tests need to be added/changed

---

### Strategy 2: {{Name}}

{{same structure}}

---

### Strategy 3: {{Name}}

{{same structure}}

---

## Scoring Matrix

| Criterion | Weight | Strategy 1 | Strategy 2 | Strategy 3 |
|-----------|--------|-----------|-----------|-----------|
| Correctness / meets ACs | 30% | X/10 | X/10 | X/10 |
| Simplicity / maintainability | 25% | X/10 | X/10 | X/10 |
| Testability | 20% | X/10 | X/10 | X/10 |
| Performance impact | 15% | X/10 | X/10 | X/10 |
| Implementation risk | 10% | X/10 | X/10 | X/10 |
| **Weighted total** | | **X.X** | **X.X** | **X.X** |

## Decision

**Chosen:** Strategy N — {{Name}}

**Rationale:** Why this strategy was chosen over the alternatives (2-3 sentences).

## Consequences

**Positive:** What gets better

**Negative / Trade-offs:** What gets harder or worse

**Risks:** What could go wrong and mitigations

## Implementation Notes

**Files to change:** Specific file paths

**New classes needed:** Class names and their responsibilities

**Tests to write:** Test class names and what they cover

**Config changes:** Any docker-compose, application.yml, Jenkinsfile changes

---
*This page will be updated with implementation results after the MR is merged.*
