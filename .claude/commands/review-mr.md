---
description: Review a GitLab MR for bugs, errors, security issues, and EdgeFabric architecture violations
argument-hint: "MR_ID (e.g. 42)"
---

Fetch a GitLab MR by ID, review all changed files for bugs, errors, security issues, and
EdgeFabric-specific violations. Produces a structured report with severity levels.

**MR ID:** $ARGUMENTS

## Steps

1. **Parse MR ID**
   ```bash
   MR_ID=$(echo "$ARGUMENTS" | grep -oP '\d+' | head -1)
   ```
   If not found → STOP: "Please provide an MR ID (e.g. /review-mr 42)."

2. **Fetch MR Details**
   ```
   mcp__gitlab__get_merge_request(mr_iid=${MR_ID})
   ```
   Extract: title, source branch, target branch, author, created date, description.
   Print:
   ```
   Reviewing MR !<MR_ID>: "<title>"
   Branch: <source> → <target>
   Author: <author>
   ```

3. **Fetch Changed Files**
   ```
   mcp__gitlab__list_merge_request_diffs(mr_iid=${MR_ID})
   ```
   List all changed files with their diffs.

4. **Fetch Full File Content for Changed Files**
   For each changed Java file, fetch the full file content (not just the diff) so the reviewer
   has complete context:
   ```
   mcp__gitlab__get_file(file_path=<path>, ref=<source_branch>)
   ```

5. **Invoke code-reviewer Agent**
   Pass all diffs and full file contents to the `code-reviewer` agent:

   ```
   Agent(
     subagent_type="code-reviewer",
     prompt="""
     Review the following MR changes for MR !<MR_ID>: "<title>"
     Source branch: <source_branch>

     Changed files and diffs:
     <all diffs>

     Full file content for context:
     <full files>

     Review criteria:
     1. BUGS — logic errors, null pointer risks, incorrect conditions, wrong return values
     2. SECURITY — hardcoded credentials, missing @Valid, SQL/log injection, exposed internals
     3. PERFORMANCE — N+1 HTTP calls in loops, Thread.sleep() in production, blocking calls
     4. ARCHITECTURE — Controller accessing store directly (must go through Service),
        raw RuntimeException (must use custom exceptions), @Autowired field injection,
        missing layer separation
     5. TESTS — @MockBean instead of @MockitoBean (Spring Boot 3.4+), missing exception path
        coverage, assertions that test the mock not the logic
     6. CODE QUALITY — cyclomatic complexity >10, duplicate blocks >15 lines, unused imports,
        methods doing too many things

     Severity levels:
     - CRITICAL: must fix before merge (bugs, security vulns, data loss risk)
     - MAJOR: should fix before merge (architecture violations, missing error handling)
     - MINOR: nice to fix (style, small improvements)

     Return a structured report with file:line references for every issue.
     """
   )
   ```

6. **Post Review Comment to MR**
   After the code-reviewer returns its report, post it as an MR comment:
   ```
   mcp__gitlab__create_merge_request_note(
     mr_iid=${MR_ID},
     note="""
   ## Code Review — Automated Analysis

   <full report from code-reviewer>

   ---
   *Reviewed by code-reviewer agent*
     """
   )
   ```

7. **Print Summary**
   ```
   === MR !<MR_ID> Review Complete ===
   Files reviewed: <N>
   CRITICAL: <count>
   MAJOR:    <count>
   MINOR:    <count>

   <if CRITICAL or MAJOR exist>
   ❌ NOT READY TO MERGE — fix CRITICAL/MAJOR issues first.

   <if no CRITICAL or MAJOR>
   ✅ READY TO MERGE — no blocking issues found.
   ===================================
   ```

## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol. Use `--agent review-mr --tags "review,<module>"`.
> **Stage 0 explicit read** by author/topic to detect recurring reviewer findings on the same code area:
> `py .claude/scripts/recall.py recall --agent review-mr --topic "<MR title or touched module>" --limit 10`

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Static analysis run | `Review static-analysis MR-!87` | `spotbugs=2 pmd=5 checkstyle=0 sonarlint=3` |
| Verdict | `Review verdict MR-!87 <READY\|NOT_READY>` | `0 CRITICAL 1 MAJOR 4 MINOR; touched=caching/...` |
| Auto-fixes applied | `Review auto-fix MR-!87` | `3 MINOR fixed in-place: null-check, log placeholder, unmod-list` |
| Followups opened | `Review followups MR-!87` | `Jira EPMICMPHE-201,202 opened for non-blocking MAJORs` |
| Final outcome | `review-mr RESULT MR-!87 <READY\|NOT_READY>` | `posted to GitLab; reviewer-evidence in MR description` |
