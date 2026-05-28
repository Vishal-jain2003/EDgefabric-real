---
description: Fix a non-emergency bug found in dev/QA — reproduce, root-cause, regression-test, fix, MR
argument-hint: "JIRA_KEY | \"bug description\" [--source=sonar|review|qa|user|ci]"
---

## When to use this command

**Use `/bugfix`** when:
- A defect is found in **`develop`, a feature branch, QA, code-review, SonarQube, or a CI run** — i.e. it is *not* live in production
- You want a **lighter** workflow than `/full-sdlc` (no architecture stage, no ADR, no story-point gate) but **stricter** than ad-hoc fixing (mandatory reproduce + RCA + regression test)
- The fix is local to existing modules — no new architecture decisions

**Use `/hotfix` instead** when the bug is **live in production** (branches from `main`, fast-tracks deploy).
**Use `/full-sdlc` instead** when the work is a new feature, not a defect.

---

Bug-fix workflow: triage → reproduce → RCA → regression test (RED) → fix (GREEN) → review → CI → MR.
Branches from **`develop`**, uses **`bugFix/*`** prefix, Jira issuetype=**Bug**.

**Arguments:** $ARGUMENTS

---

## Stages

### Stage 0 — Parse & classify

Extract from `$ARGUMENTS`:
- `JIRA_KEY` — if it matches `[A-Z]+-\d+`, treat as existing bug ticket
- Else → free-text description, you will create the Jira Bug in Stage 1
- `--source=` flag → records where the bug was discovered (default: `user`). Used for the Jira label.

**Recall prior bugs (memory read).** Before doing anything else, query durable memory for
similar past defects so you don't re-RCA something already solved:

```powershell
py .claude/scripts/recall.py recall --agent bugfix --topic "<one-line bug summary>" --limit 5 --max-tokens 600
# If JIRA_KEY known, also:
py .claude/scripts/recall.py recall --topic "<JIRA_KEY>" --limit 10
```

Print the recalled bullets verbatim under a `## Prior memory` heading so the user can see
prior root causes / introducing commits / regression-test names. If a hit is a near-duplicate,
mention it on the Jira bug (`atlassian-add_issue_comment`) before continuing.

If recall fails (DB locked / missing), log `[recall] read failed (non-fatal)` and continue.

Decide severity by source + impact:
| Source | Default severity |
|--------|------------------|
| `sonar` (BLOCKER/CRITICAL) | High |
| `review` (CRITICAL finding) | High |
| `ci` (failing pipeline) | High |
| `qa` (functional defect) | Medium |
| `user` / freeform | Medium (override if data-loss / security → High) |

### Stage 1 — Create or fetch the Jira Bug

If `JIRA_KEY` provided → `atlassian-get_issue(JIRA_KEY)` and verify `issuetype == Bug`.
Else → `atlassian-create_issue(project="EPMICMPHE", issuetype="Bug", priority=<from-stage-0>, summary=..., description=...)`.

Description **must contain** these sections (the agent fills them, not the user):
```
## Overview
<one-line symptom>

## Steps to reproduce
1. ...
2. ...

## Expected behaviour
...

## Actual behaviour
...

## Source
<sonar|review|qa|user|ci>  +  link if applicable

## Affected module(s)
<loadbalancer | caching | registry | consistent-hashing | ui>

## First seen
<branch / commit / sonar-rule / MR-link>
```

Add label `bug-workflow` so these tickets are filterable.
`atlassian-update_issue_status(JIRA_KEY, "In Progress")`.

### Stage 2 — Branch

```powershell
git checkout develop
git pull origin develop
git checkout -b bugFix/<JIRA_KEY>-short-slug
```
Abort if branch already exists with uncommitted work — ask the user.

### Stage 3 — Reproduce (MANDATORY — no fix without a failing case)

You must produce **objective evidence** the bug exists on `develop` *before* writing any fix.

Pick the cheapest valid reproduction:

| Bug type | Reproduction technique |
|----------|------------------------|
| Logic / API bug | Failing JUnit test (preferred) |
| Concurrency / gossip | Integration test with `@SpringBootTest` + Testcontainers |
| Performance regression | k6 script via `performance-tester` agent on `develop` baseline |
| UI bug | Playwright/Cypress script or manual screenshot in `ui/` |
| Sonar finding | Cite the exact rule + file:line — counts as proof |
| Static-analysis (SpotBugs/PMD) | Tool report counts as proof |
| Pipeline failure | The failing job log counts as proof |

Save evidence under `.codemie/bugs/<JIRA_KEY>/repro.md` (free-form: stack trace, screenshot path, k6 output, SonarQube link).

If you cannot reproduce → **STOP**. Comment on Jira "Cannot reproduce — need: <list>" and transition to `Blocked`. Do not invent a fix.

### Stage 4 — Root-cause analysis (RCA)

Find the **actual cause**, not the symptom. Use `git log -S` / `git blame` / `git bisect` if needed:

```powershell
# Find when the buggy line was introduced
git log -S "<buggy expression>" --source --all
git blame -L <line>,<line> <file>
```

Write findings to `.codemie/bugs/<JIRA_KEY>/rca.md`:
```
## Root cause
<one paragraph — the real reason, not the symptom>

## Introducing commit
<sha>  by <author>  on <date>  (PR #<n> if known)

## Why existing tests didn't catch it
<gap analysis — drives the regression test you'll write in Stage 5>

## Blast radius
- Affected components: <list>
- Other code paths that share this defect: <list or "none">
- Data corruption risk: <yes/no — if yes, describe cleanup>
```

If RCA reveals the bug is **architectural** (touches > 2 modules, changes a contract, requires a new component) → **STOP**. Comment on Jira "Requires architecture work, escalating to /full-sdlc" and exit.

### Stage 5 — Regression test (TDD Red)

Delegate to `test-writer` agent with this exact instruction:
> Write the **smallest** test that reproduces JIRA_KEY. The test MUST FAIL on the current code (verify by running it now). Place the test in the matching module's `src/test/java/`. Use a name that includes the Jira key, e.g. `EPMICMPHE_150_TtlExpiryRegressionTest`. Do not write any production-code fix.

After the agent returns, **you** verify:
```powershell
mvn -pl <module> test -Dtest=<NewTestClass> -q
```
The test must FAIL (RED). If it passes → the test does not actually exercise the bug; reject and have the agent rewrite.

For Sonar/SpotBugs/PMD bugs where a unit test is artificial, the regression "test" is: **a CI gate that re-runs the static tool and fails if the rule is violated**. Verify the rule still trips on `develop`.

### Stage 6 — Fix (TDD Green)

Delegate to `java-implementer` agent:
> Make `<NewTestClass>` pass. Touch only the module(s) named in the RCA blast radius. Do not refactor unrelated code. Do not weaken any existing test. Honour `.claude/shared/java-conventions.md` and the new C.1 (framework-redundant code) rule from `.claude/agents/code-reviewer.md`.

After return, run:
```powershell
mvn -pl <module> test -q              # regression test must now PASS
mvn -pl <module> verify -q            # full module suite must still PASS
```

If any pre-existing test breaks → STOP. Either the fix is wrong or the broken test was wrong. Investigate, do not silently update assertions.

### Stage 7 — Self code-review (the upgraded reviewer)

```
/review-mr  --staged
```
The upgraded `code-reviewer` agent (sonnet, Effective-Java + SOLID + C.1 framework-redundant + null-safety + redundancy + SpotBugs/PMD/Checkstyle/SonarLint) runs on the diff.

- 0 CRITICAL → continue
- ≥1 CRITICAL → fix in this same branch, re-run reviewer
- MAJOR/MINOR → fix if quick, else open follow-up Jira tasks linked to the bug

### Stage 8 — Quality gates

```powershell
mvn -pl <module> verify -q
# SonarQube scan happens in CI; locally just check the rule that flagged the bug is now clean:
```
- `sonarqube-get_issues(severity="MAJOR")` after CI scan completes — the rule that triggered the bug (if Sonar-sourced) must no longer fire.
- Coverage on the changed files: ≥ 80% line, ≥ 70% branch (project default).

### Stage 9 — Commit, push, MR

Commit message format (project convention from CLAUDE.md):
```
fix: <one-line summary>

Root cause: <one sentence from rca.md>
Regression test: <test class name>
Jira: <JIRA_KEY>

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

```powershell
git push -u origin bugFix/<JIRA_KEY>-...
```

Create MR via `gitlab-create_merge_request`:
- **Source:** `bugFix/<JIRA_KEY>-...`
- **Target:** `develop`
- **Title:** `fix(<module>): <summary> [<JIRA_KEY>]`
- **Description template:**
  ```
  ## Bug
  <link to Jira>

  ## Root cause
  <copy from rca.md>

  ## Fix
  <one paragraph>

  ## Regression test
  - <test class> — fails on develop, passes here

  ## Reviewer evidence
  - SpotBugs: <pass/fail + delta>
  - PMD: <pass/fail + delta>
  - SonarQube rule <RULE_ID>: cleared
  - Coverage on touched files: <%>

  ## Risk
  Blast radius from RCA: <list>
  Rollback plan: revert this MR
  ```

### Stage 10 — Pipeline & deploy

- `pipeline-monitor` agent watches Jenkins; auto-fixes flakes / formatting; max 3 retries.
- On green: post the bug-fix report to Jira (`atlassian-add_issue_comment`) with MR link, pipeline link, regression-test class name.
- **Do not auto-deploy.** Bug fixes ride the next regular `develop → release/*` train. (For prod-fires, the user should have used `/hotfix` instead.)

---

## Handoff JSON

Each stage writes `.codemie/handoff/bugfix-<JIRA_KEY>-stage-<n>.json` per `.claude/shared/handoff-schema.md`. Final stage emits:
```json
{
  "ticket": "EPMICMPHE-150",
  "result": "FIXED" | "ESCALATED" | "BLOCKED",
  "branch": "bugFix/EPMICMPHE-150-...",
  "mr": "https://gitlab.../merge_requests/42",
  "rca_summary": "...",
  "regression_test": "EPMICMPHE_150_TtlExpiryRegressionTest",
  "static_analysis_delta": { "spotbugs": -1, "pmd": 0, "sonar": -1 }
}
```

## Hard rules (the orchestrator must enforce)

1. **No fix without reproduction.** Stage 3 evidence file must exist before Stage 6 starts.
2. **No fix without RCA.** Stage 4 file must exist before Stage 6 starts.
3. **Regression test must be RED before Stage 6, GREEN after.** Verified by running `mvn test`, not by claim.
4. **Existing tests must not regress.** If they do, the fix is wrong — do not adjust their assertions.
5. **Branch from `develop`, never `main`.** If from `main`, the user wanted `/hotfix`.
6. **Architecture changes are out of scope.** Escalate to `/full-sdlc`.
7. **Sonar/SpotBugs/PMD-sourced bugs must clear the original rule.** Cite the rule ID in the MR description.

## Memory rules

> Follow `.claude/shared/memory-protocol.md` for the protocol (env vars, failure handling,
> body/topic rules, auto-compact). This table lists only the events specific to `/bugfix`.

The Stage 0 explicit topic-recall (above) and the per-stage writes below are MANDATORY.

## Memory write events

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Stage 1 done | `Bug created EPMICMPHE-150` | `priority=High source=sonar module=caching rule=squid:S2259` |
| Stage 3 done | `Reproduced EPMICMPHE-150` | `repro at .codemie/bugs/EPMICMPHE-150/repro.md (JUnit fail in CachingApplicationTests)` |
| Stage 4 done | `RCA EPMICMPHE-150` | `cause=missing @EnableScheduling on CachingConfig; introduced abc1234 by alice` |
| Stage 4 escalation | `ESCALATED EPMICMPHE-150 to /full-sdlc` | `reason=touches loadbalancer+caching+registry, contract change` |
| Stage 5 done | `Regression test EPMICMPHE-150` | `test=EPMICMPHE_150_TtlExpiryRegressionTest fails on develop` |
| Stage 6 done | `Fix EPMICMPHE-150 GREEN` | `1 file changed: caching/.../CachingConfig.java +1 -0` |
| Stage 7 done | `Reviewer EPMICMPHE-150` | `0 CRITICAL, 1 MAJOR auto-fixed, 2 MINOR followups opened` |
| Stage 8 done | `Quality gates EPMICMPHE-150` | `sonar rule squid:S2259 cleared; coverage 87%/74%` |
| Stage 9 done | `MR EPMICMPHE-150` | `MR !87 -> develop, sha=def5678` |
| Stage 10 done | `Pipeline EPMICMPHE-150` | `Jenkins #412 GREEN; ride next release/* train` |
| Final outcome | `bugfix RESULT FIXED` (or `BLOCKED` / `ESCALATED`) | `summary one-liner; pointer to handoff json` |

`--agent bugfix --tags "bugfix,<source>,<module>"` for every call.
