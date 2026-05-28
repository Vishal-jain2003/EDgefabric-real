---
description: Generate architecture strategies and write an ADR to Confluence for a Jira story
argument-hint: "JIRA_KEY (e.g. EPMICMPHE-42)"
---

Run the solution architect agent for the given Jira issue. Produces 3 implementation strategies, writes an ADR to Confluence, and stops for human review (Gate 2).

**Jira issue key:** $ARGUMENTS

## Steps

1. **Validate Input**
   If no Jira key is provided, extract it from the current branch:
   ```bash
   git branch --show-current | grep -oP '[A-Z]+-\d+'
   ```
   If still not found, ask: "Please provide the Jira issue key (e.g. EPMICMPHE-42)."

2. **Create Feature Branch**
   ```bash
   ISSUE_KEY=[extracted key]
   # Fetch story title to slugify
   # e.g. EPMICMPHE-42 "Add TTL expiry" → feature/EPMICMPHE-42-add-ttl-expiry
   git checkout develop
   git pull origin develop
   git checkout -b feature/${ISSUE_KEY}-[5-word-slug]
   ```
   Slugify: lowercase, replace spaces with hyphens, max 5 words.

3. **Invoke solution-architect Agent**
   Run the `solution-architect` agent with the Jira issue key as input.

   **STRICT GATE CHECKS** (no bypass available):
   - GATE 1: Ticket MUST be in current sprint
   - GATE 2: Status MUST be "To Do" or "In Progress"
   - If either gate fails, the agent will exit immediately

   The agent will:
   - Perform strict gate checks (sprint + status)
   - Fetch the Jira story and acceptance criteria
   - Explore the codebase
   - Generate 3 strategies with scoring table
   - Write the ADR to Confluence
   - Link the ADR back to Jira
   - Stop and print Gate 2 message

4. **Do Not Proceed Further**
   After the solution-architect agent stops at Gate 2, this skill is complete.
   The next step for the user is:
   - Review the ADR in Confluence
   - Reply "approved" or "approved strategy N" to proceed with /full-sdlc

## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol. Use `--agent design-story --tags "adr,design"`.
> **Stage 0 explicit read** by topic — designers want to know prior architectural decisions on the same theme:
> `py .claude/scripts/recall.py recall --agent design-story --topic "<feature one-liner>" --limit 8`
> Also recall by ticket for any prior design attempts on this story.

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Strategies generated | `ADR strategies <JIRA_KEY>` | `3 strategies: A=in-process B=sidecar C=external; recommended=B` |
| ADR written | `ADR written <JIRA_KEY>` | `Confluence page-id=12345; local spec .codemie/specs/<KEY>/spec.md` |
| Jira linked | `ADR linked <JIRA_KEY>` | `comment posted with ADR url; status=In Review` |
| Gate 2 STOP | `Gate 2 STOP <JIRA_KEY>` | `awaiting human approval — reply 'approved' or 'approved strategy N'` |
| Final outcome | `design-story RESULT <JIRA_KEY>` | `ADR delivered; recommended strategy=B` |
