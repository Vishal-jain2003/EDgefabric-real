---
description: Implement a Jira story using TDD — write tests first, implement, create MR (requires approved spec)
argument-hint: "JIRA_KEY [optional ADR_URL_or_page_id]"
---

## When to use this command

**Use `/tdd-implement`** when:
- An **approved spec already exists** in `.codemie/specs/<TICKET>/` (architecture was done via `/design-story` or `/full-sdlc` Gate 2 already passed)
- You want TDD + CI/CD only — no Jira/epic creation, no architecture strategies, no deployment
- You are resuming implementation after architecture was approved in a previous session

**Use `/full-sdlc` instead** when:
- Starting from a **raw Jira ticket** with no spec yet
- You need the full pipeline: sprint gates → architecture strategies → ADR → TDD → deploy
- You want the orchestrator to manage all stages end-to-end

---

TDD implementation loop — assumes architecture is already approved and a local spec exists in
`.codemie/specs/`. Writes tests first, implements, validates coverage and SonarQube, pushes,
and creates an MR (Gate 3).

**Arguments:** $ARGUMENTS

## Steps

1. **Parse Arguments**
   Extract Jira key from $ARGUMENTS. Fallback: extract from current branch name.
   ```bash
   JIRA_KEY=$(echo "$ARGUMENTS" | grep -oP '[A-Z]+-\d+' | head -1)
   [ -z "$JIRA_KEY" ] && JIRA_KEY=$(git branch --show-current | grep -oP '[A-Z]+-\d+')
   ```

2. **Verify Branch**
   Must be on `feature/*` or `bugFix/*`. Abort if on `develop` or `main`.

3. **Check Spec Exists**
   Look for `.codemie/specs/${JIRA_KEY}/`.
   - Found → proceed to Step 4.
   - Not found → STOP: "No spec found for ${JIRA_KEY}. Run /full-sdlc ${JIRA_KEY} for the full flow including architecture, or run /design-story ${JIRA_KEY} to generate the spec first."

4. **Scrum Master Gate Check**
   Even with an approved spec, the Jira story must still pass readiness gates — status may have
   changed, blockers may have been added, or the story may have been resolved since the spec was
   created. Invoke the scrum-master agent:

   ```
   Agent(subagent_type="scrum-master", prompt="${JIRA_KEY}")
   ```

   Read the agent response and find the final `SCRUM_MASTER_RESULT:` line.

   - `SCRUM_MASTER_RESULT: BLOCKED` → STOP immediately.
     Print: "Scrum master gates failed for ${JIRA_KEY}. Fix the issues in Jira before implementing."
   - `SCRUM_MASTER_RESULT: APPROVED` → continue.

5. **Read Approved Spec**
   Read the spec file at `.codemie/specs/${JIRA_KEY}/`.
   Extract: affected modules, new classes, API contracts, implementation task checklist.

6. **TDD Red Phase — Write Failing Tests**
   Invoke `test-writer` agent:
   - Services → `@ExtendWith(MockitoExtension.class)` unit tests
   - Controllers → `@WebMvcTest` integration tests
   - Use `@MockitoBean` (NOT `@MockBean` — Spring Boot 3.4+)
   - Tests must compile but FAIL (no implementation yet)
   - Run: `mvn test-compile -pl <module>`

7. **TDD Green Phase — Implement**
   Invoke `java-implementer` agent:
   - Read spec at `.codemie/specs/${JIRA_KEY}/`
   - Implement minimal code to make tests pass
   - Architecture: Controller → Service → Model (strict layers)
   - Run: `mvn compile -pl <module>` — fix all errors before returning

8. **Make Tests GREEN**
   Invoke `test-runner` agent `mode=unit`:
   - Run: `mvn test -pl <module> -Dgossip.port=0`
   - Fix implementation (never test assertions) until BUILD SUCCESS

9. **Write Missing Integration Tests + Run**
   Invoke `test-runner` agent `mode=integration`:
   - Check each changed controller for missing `@WebMvcTest` scenarios
   - Write: happy path + all 4xx + exception handler mappings
   - Run integration tests until BUILD SUCCESS

10. **Coverage Gate (HARD — do NOT push if this fails)**
    Invoke `test-runner` agent `mode=coverage`:
    - Thresholds: loadbalancer≥80%, caching≥80%, consistent-hashing≥90%, registry≥75%
    - If below threshold → return to Step 6 with list of uncovered methods

11. **Code Review**
    Invoke `code-reviewer` agent:
    - Fix all CRITICAL and MAJOR issues before proceeding
    - Re-review after fixes

12. **SonarQube Gate**
    ```bash
    mvn sonar:sonar -Dsonar.projectKey=EPM-ICMP-HEREMES \
      -Dsonar.host.url="$SONAR_HOST_URL" -Dsonar.token="$SONAR_TOKEN"
    ```
    Poll gate (max 70 min). If FAILED → fix BLOCKER/CRITICAL → re-run (max 2 cycles).

13. **Record Baseline + Commit and Push**
    Record Jenkins baseline build number before pushing using the Jenkins MCP tool:
    ```
    BRANCH=$(git branch --show-current)
    Call mcp__jenkins__get_build_status(job_name="EPM-ICMP/EPM-ICMP-JAN2026/EPM-ICMP-EDGEFABRIC/EPM-ICMP-EFHERMES/EPM-ICMP-HermesPipeline/<BRANCH>")
    Parse "Build #:" from response → store as BASELINE (use 0 if no builds yet)
    ```
    Then push:
    ```bash
    git add <specific files — never git add -A>
    git commit -m "feat(<module>): ${JIRA_KEY} <description>"
    git push -u origin "$BRANCH"
    ```

14. **Monitor Feature Pipeline**
    Invoke `pipeline-monitor` agent:
    - BASELINE_BUILD: value captured before push
    - Branch: current feature branch
    - Invocation context: feature branch — fix on same branch, never commit to develop
    - Max 3 retries
    - STOP and escalate to human if all 3 fail

15. **Create MR**
    ```
    mcp__gitlab__create_merge_request(
      source_branch=<current branch>,
      target_branch="develop",
      title="${JIRA_KEY}: <Jira summary>",
      description="## Summary\n...\n## Quality Evidence\n- [x] Coverage met\n- [x] SonarQube OK\n- [x] Feature pipeline PASSED\nCloses ${JIRA_KEY}"
    )
    ```

16. **Monitor MR Pipeline**
    Invoke `pipeline-monitor` agent:
    - BASELINE_BUILD: feature pipeline build number (from Step 14)
    - Branch: same feature branch
    - Max 3 retries

17. **Update Jira — STOP Gate 3**
    ```
    mcp__atlassian__update_issue_status(issue_key="${JIRA_KEY}", status_name="In Review")
    mcp__atlassian__add_issue_comment(issue_key="${JIRA_KEY}", comment="MR: <url> | Feature: ✅ | MR pipeline: ✅")
    ```
    Print:
    ```
    ✅ MR created: !<iid>
    Feature pipeline: ✅ PASSED
    MR pipeline:      ✅ PASSED
    Coverage:         ✅ All modules above threshold

    ⏸ GATE 3: Review and approve the MR in GitLab.
       Reply "merged" to update docs and close the ticket.
    ```

    Wait for "merged" reply, then proceed to Step 18.

18. **Update Confluence page + Close Jira**
    Read the Confluence page ID from the local spec (saved by solution-architect):
    ```bash
    SPEC_FILE=$(ls .codemie/specs/${JIRA_KEY}/*.md 2>/dev/null | head -1)
    CONFLUENCE_PAGE_ID=$(grep "Confluence Page ID:" "$SPEC_FILE" 2>/dev/null | grep -oP '\d{5,}')
    ```

    If `CONFLUENCE_PAGE_ID` is non-empty:
    - Fetch current page version: `mcp__atlassian__confluence_get_page(page_id="${CONFLUENCE_PAGE_ID}")`
    - Call `mcp__atlassian__update_confluence_page` to append implementation details
      (API reference, test summary, pipeline results) to the existing ADR page.
    - Preserve all existing ADR content — only append below a `---` separator.

    If empty (spec was created without running /full-sdlc first):
    - Print: "⚠️  No Confluence page ID in spec — skipping page update."

    Then close the ticket:
    ```
    mcp__atlassian__add_issue_comment(issue_key="${JIRA_KEY}", comment="✅ MR merged. Confluence page updated: <url>")
    mcp__atlassian__update_issue_status(issue_key="${JIRA_KEY}", status_name="Done")
    ```
    Print: "🎉 ${JIRA_KEY} complete — MR merged, docs updated, Jira closed!"

## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol. Use `--agent tdd-implement --tags "tdd,<module>"`.
> No explicit Stage 0 read needed — `prompt-context.py` auto-recalls by ticket.

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Spec loaded | `TDD spec loaded <JIRA_KEY>` | `.codemie/specs/<KEY>/spec.md (rev 3) ADR=<page-id>` |
| Tests RED | `TDD red <JIRA_KEY>` | `5 tests added in caching/.../*Test.java; all fail as expected` |
| Tests GREEN | `TDD green <JIRA_KEY>` | `impl in caching/.../X.java +120 -8; all 5 tests pass` |
| Coverage gate | `TDD coverage <PASS\|FAIL> <JIRA_KEY>` | `line=86% branch=72% touched-files=4` |
| Sonar gate | `TDD sonar <PASS\|FAIL> <JIRA_KEY>` | `0 BLOCKER 0 CRITICAL 1 MAJOR (followup)` |
| MR created | `TDD MR <JIRA_KEY>` | `MR !87 -> develop sha=def5678` |
| Pipeline | `TDD pipeline <PASS\|FAIL> <JIRA_KEY>` | `Jenkins #412 GREEN after 1 retry` |
| Final outcome | `tdd-implement RESULT <MERGED\|BLOCKED> <JIRA_KEY>` | `MR !87 merged, Jira closed, Confluence updated` |
