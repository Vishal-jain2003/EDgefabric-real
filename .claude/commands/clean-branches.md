---
description: List stale branches, cross-check Jira status, and delete safe ones
argument-hint: "(optional) extra context"
---

Clean up stale remote branches — list candidates, cross-check Jira status, and delete safe ones.

**Extra context (optional):** $ARGUMENTS

## Steps

1. **List Remote Branches**
   Use GitLab MCP `list_branches` to get all remote branches with last commit date.

   Filter candidates (idle > 14 days AND not main/develop/release/*):
   ```
   branches where last_commit_date < (today - 14 days)
   AND name NOT IN [main, develop]
   AND name NOT LIKE release/*
   AND name NOT LIKE hotfix/*
   ```

2. **Cross-check Jira Status**
   For each candidate branch, extract the Jira key:
   ```
   feature/EPMICMPHE-42-description → EPMICMPHE-42
   bugFix/EPMICMPHE-55-fix → EPMICMPHE-55
   ```

   Use Atlassian MCP `mcp__atlassian__get_issue` for each key.

   Classify:
   - **SAFE to delete**: Jira status = Done/Closed AND no open MR
   - **KEEP**: Jira status = In Progress/In Review OR open MR exists
   - **ORPHAN**: No Jira key found in branch name, idle > 30 days

3. **Check Open MRs**
   Use GitLab MCP `list_merge_requests` with state=opened to get all open MRs.
   Cross-reference branch names — any branch with an open MR is NOT safe to delete.

4. **Display Candidates**
   ```
   === Branch Cleanup Candidates ===

   SAFE TO DELETE (Jira Done + no open MR):
   - feature/EPMICMPHE-38-ttl-expiry      [idle 21d] [Jira: Done]
   - bugFix/EPMICMPHE-41-gossip-fix       [idle 18d] [Jira: Done]

   KEEP (Jira open or has MR):
   - feature/EPMICMPHE-42-quorum-writes   [idle 3d]  [Jira: In Progress]
   - feature/EPMICMPHE-44-webclient       [idle 7d]  [MR: !23 open]

   ORPHAN (no Jira key, idle > 30d):
   - test-branch-old                      [idle 45d] [no Jira key]
   ```

5. **Auto-Delete Safe Branches**
   Print: "Auto-deleting [N] safe branches (Jira Done + no open MR) and [M] orphans (idle >30d)..."
   Proceed immediately — do NOT ask for confirmation.

6. **Delete Branches**
   For each confirmed branch, use GitLab MCP `delete_branch`:
   ```
   DELETE /projects/:id/repository/branches/:branch
   ```

   Print each deletion as it happens. Add a comment to each Jira issue:
   "Feature branch [branch-name] deleted — Jira status: Done."

7. **Summary**
   ```
   === Cleanup Complete ===
   Deleted: N branches
   Kept: M branches
   Orphans removed: K
   ```
