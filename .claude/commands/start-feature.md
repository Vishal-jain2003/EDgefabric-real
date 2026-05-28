---
description: Create a Jira story and feature branch in one step
argument-hint: "feature description"
---

Start a new feature: create a Jira story, create a git branch, and transition to In Progress.
The Confluence KB page is created at the end of the SDLC via /full-sdlc (Stage 7).

**Feature description:** $ARGUMENTS

## Steps

1. **Find Best Epic**
   Use `mcp__atlassian__find_best_epic` with the full feature description to score all open epics
   and pick the best semantic match:
   ```
   mcp__atlassian__find_best_epic(
     feature_description="<full $ARGUMENTS text>",
     project_key="EPMICMPHE"
   )
   ```
   Use the top-ranked epic (highest score). If no open epics exist, proceed without an epic link.

2. **Create Jira Story**
   Use `mcp__atlassian__create_issue`:
   ```
   project: EPMICMPHE
   issuetype: Story
   summary: [feature description]
   description: |
     ## Overview
     [feature description]

     ## Acceptance Criteria
     - [ ] [AC1]
     - [ ] [AC2]
     - [ ] [AC3]
   priority: Medium
   epicLink: [epic key if found]
   ```
   Note the returned issue key (e.g. `EPMICMPHE-42`).

3. **Create Feature Branch**
   ```bash
   ISSUE_KEY=EPMICMPHE-42
   git checkout develop
   git pull origin develop
   git checkout -b feature/${ISSUE_KEY}-[5-word-slug]
   ```
   Slugify: lowercase, hyphens, max 5 words from the feature description.

4. **Transition Jira to In Progress**
   Use `mcp__atlassian__update_issue_status` to move from "To Do" → "In Progress".

5. **Summary**
   ```
   === Feature Started ===
   Jira:   EPMICMPHE-42 — [title]
   URL:    [JIRA_BASE_URL]/browse/EPMICMPHE-42
   Branch: feature/EPMICMPHE-42-[slug]
   Status: In Progress

   Next step: run /full-sdlc EPMICMPHE-42 for the full SDLC.
   The Confluence KB page will be created automatically at the end.
   ```
