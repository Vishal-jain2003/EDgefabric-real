---
description: Create or update a Confluence documentation page for a feature or topic
argument-hint: "JIRA_KEY or topic description"
---

Create or update a Confluence documentation page for a feature or topic under the Hermes KB space.

**Topic or Jira issue key:** $ARGUMENTS

## Steps

1. **Resolve the Subject**
   If $ARGUMENTS is a Jira issue key (e.g. `EPMICMPHE-42`):
   - Use Atlassian MCP `mcp__atlassian__get_issue` to fetch the issue summary, description, and status
   - Use the summary as the page title

   If $ARGUMENTS is a free-text description:
   - Use it directly as the page title and overview

2. **Create or Update the Page**
   Attempt `confluence_create_page` directly — do not search first.
   If the API returns a title-conflict error, then call `mcp__atlassian__update_confluence_page` on the existing page ID returned in the error.

   Use Atlassian MCP with the feature documentation template:
   ```
   space_key: EPMICMP
   parent_id: [CONFLUENCE_PARENT_PAGE_ID or 2808363846]
   title: "[ISSUE_KEY or topic title]"
   body: |
     h2. Overview
     [feature/topic description]

     h2. Jira Story
     [JIRA_BASE_URL]/browse/ISSUE_KEY   (if applicable)

     h2. Technical Design
     [describe components affected, architecture decisions — or placeholder if new]

     h2. API Changes
     [new or modified endpoints — or placeholder]

     h2. How to Test
     [step-by-step test instructions — or placeholder]

     h2. GitLab MR
     [link to MR if available, or current branch name]

     h2. Status
     [In Progress / Done — based on Jira status]
   ```

4. **Link Back to Jira**
   If a Jira issue key was provided, use Atlassian MCP `mcp__atlassian__add_issue_comment`:
   ```
   issue_key: ISSUE_KEY
   comment: "KB Doc [created/updated]: [Confluence page URL]"
   ```

5. **Summary**
   Report: page title, URL, whether it was created or updated.
