---
description: Create a Jira story with acceptance criteria (Gate 1)
argument-hint: "feature description"
---

Create a new Jira story for the feature described below, link it to an epic, and stop for human
review (Gate 1). The Confluence KB page is created at the end of the SDLC via /full-sdlc.

**Feature description:** $ARGUMENTS

## Steps

1. **Find Best Epic**
   Use `mcp__atlassian__find_best_epic` with the full feature description to score all open epics
   and return the best semantic match:
   ```
   mcp__atlassian__find_best_epic(
     feature_description="<full $ARGUMENTS text>",
     project_key="EPMICMPHE"
   )
   ```
   Use the top-ranked epic (highest score). Include the epic key in the story description header
   as `Epic: <KEY> — <name>` and link it via `mcp__atlassian__link_issues`.
   If no open epics exist, proceed without an epic link.

2. **Create Jira Story**
   Use `mcp__atlassian__create_issue`:
   ```
   project: EPMICMPHE
   issuetype: Story
   summary: [concise feature title from description]
   description: |
     ## Overview
     [feature description]

     ## Out of Scope
     - [what is explicitly NOT included]

     ## Notes
     - [any technical constraints, dependencies, or risks]
   priority: Medium
   epicLink: [epic key if found]
   ```
   Note the returned issue key (e.g. `EPMICMPHE-42`).

   **IMPORTANT — Acceptance Criteria field:**
   Jira has a dedicated "Acceptance Criteria" field that is SEPARATE from the description.
   - Do NOT embed acceptance criteria inside the description field.
   - The `mcp__atlassian__update_issue` tool does NOT support setting the AC field directly.
   - You MUST generate the acceptance criteria and present them to the user with explicit
     instructions to copy-paste them into the Jira AC field manually.

3. **Present Acceptance Criteria to User**
   After creating the story, generate min 3 specific, testable ACs based on the feature.
   Present them in this exact format so the user can copy them into Jira:

   ```
   📋 ACCEPTANCE CRITERIA — paste these into the Jira AC field for EPMICMPHE-42:
   ─────────────────────────────────────────────────────────────
   AC1: [specific, testable criterion]
   AC2: [specific, testable criterion]
   AC3: [specific, testable criterion]
   [add more as needed]
   ─────────────────────────────────────────────────────────────
   👉 Open https://[JIRA_BASE_URL]/browse/EPMICMPHE-42
      → Edit the story → paste into the "Acceptance Criteria" field → Save
   ```

4. **STOP — Human Gate 1**
   Print the following and stop:
   ```
   ╔══════════════════════════════════════════════════════════════╗
   ║              STORY REVIEW GATE 1 — AWAITING APPROVAL         ║
   ╠══════════════════════════════════════════════════════════════╣
   ║  Jira: EPMICMPHE-42 — [story title]                         ║
   ║  URL:  [JIRA_BASE_URL]/browse/EPMICMPHE-42                  ║
   ╠══════════════════════════════════════════════════════════════╣
   ║  ⚠️  ACTION REQUIRED: Paste the Acceptance Criteria above    ║
   ║      into the Jira AC field before approving.                ║
   ╠══════════════════════════════════════════════════════════════╣
   ║  Once AC is saved in Jira:                                   ║
   ║    "approved" → run /full-sdlc EPMICMPHE-42                  ║
   ║    "revision needed: [feedback]" → update the story          ║
   ╚══════════════════════════════════════════════════════════════╝
   ```

   Do NOT create a git branch or Confluence page yet.
   The KB page will be created automatically at the end of /full-sdlc (Stage 7).
