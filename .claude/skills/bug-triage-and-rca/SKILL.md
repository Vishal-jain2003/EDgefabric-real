---
name: bug-triage-and-rca
description: How to triage a bug, reproduce it, find root cause, and write a regression test before any fix. Auto-load when handling defects, bugs, regressions, "why is this failing", "fix this NPE", SonarQube findings, SpotBugs/PMD findings, failing CI jobs, QA defects, or anything in a bugFix/* branch.
---

# Bug triage and root-cause analysis

The discipline every EdgeFabric bug fix follows. Independent of which agent is doing the work.

## The non-negotiable order

1. **Triage** — classify source + severity
2. **Reproduce** — produce objective evidence the bug exists
3. **Root-cause** — find the real cause (not the symptom)
4. **Regression test (RED)** — write a failing test
5. **Fix (GREEN)** — make it pass without breaking anything
6. **Verify** — full module suite + static analysis + Sonar rule cleared

**Never invert this order.** A "fix" without a reproducer is a guess.

## 1. Triage matrix

| Source of bug report | Severity default | Branch from | Workflow |
|----------------------|------------------|-------------|----------|
| Production incident | Critical | `main` | `/hotfix` |
| Failing pipeline on `develop` | High | `develop` | `/bugfix` |
| SonarQube BLOCKER/CRITICAL | High | `develop` | `/bugfix` |
| SonarQube MAJOR / SpotBugs / PMD | Medium | `develop` | `/bugfix` |
| QA / functional defect | Medium | `develop` | `/bugfix` |
| Code review CRITICAL finding | High | same MR branch | inline fix on MR |
| User-reported behaviour | Medium (escalate if data-loss/security) | `develop` | `/bugfix` |

## 2. Reproduction techniques (cheapest first)

| Bug type | Technique |
|----------|-----------|
| Pure logic | JUnit 5 unit test |
| Service-layer interaction | `@SpringBootTest(webEnvironment = MOCK)` slice |
| Gossip / cluster behaviour | `@SpringBootTest` + Testcontainers spinning ≥ 3 cache nodes; use `Awaitility` for eventual-consistency assertions |
| Consistent-hash routing | Standalone test against `consistent-hashing` module — no Spring needed |
| HTTP contract | `MockMvc` (controller) or `WebTestClient` (full stack) |
| Performance regression | `performance-tester` agent runs k6 against `develop` baseline + branch; compare p95/throughput |
| UI rendering | Playwright script in `ui/` |
| Static-analysis finding | The tool report itself is the proof — no test needed at this stage |
| CI flake (intermittent) | Run target test in a loop: `mvn -Dtest=X -Dsurefire.rerunFailingTestsCount=0 test -DforkCount=1 -DreuseForks=false` × 50 |

**Rule:** if you cannot reproduce in ≤ 2 hours of effort, mark Jira `Blocked` with the list of what you need (logs, env access, customer data shape). Do not guess.

## 3. Root-cause analysis playbook

Symptom-vs-cause heuristic: the cause is rarely at the line that throws the exception. It's usually upstream — at the line that produced the bad state.

### Tools

```powershell
# When was the buggy line introduced?
git log -S "<exact buggy expression>" --source --all --oneline

# Who last touched it and why?
git blame -L <start>,<end> path\to\File.java

# Bisect across a known-good and known-bad commit
git bisect start
git bisect bad   <current-sha>
git bisect good  <last-known-good-sha>
# git will check out commits; at each one run the regression test
git bisect run mvn -pl <module> test -Dtest=<RegressionTest> -q
```

### Five-Whys for EdgeFabric

Stop at a why that points to a **code** or **design** decision, not a generic answer ("the system was overloaded").

Example:
1. *Why did the cache return stale data?* — TTL expiry didn't fire.
2. *Why didn't TTL expiry fire?* — The expiry sweeper thread was not scheduled.
3. *Why was it not scheduled?* — `@EnableScheduling` was removed from `CachingConfig` in commit `abc123`.
4. *Why was it removed?* — Author thought scheduling moved to a parent config.
5. *Why was that wrong?* — Parent config only enables scheduling in test profile.

→ Cause: missing `@EnableScheduling` on `CachingConfig` for non-test profiles. The fix is one line; the test is "verify the bean is present in default profile".

### Blast-radius checklist

For every RCA, fill in:
- Affected modules
- Other code paths that share the defect (often: copy-paste siblings) — search them with `grep` and fix in the same MR if cheap
- Data corruption: did the bug write bad state to disk / cache / DB? If yes, plan cleanup *before* fixing the code (otherwise the fix masks the corrupt data)
- Backwards-compat: does the fix change a wire/JSON contract? If yes → escalate to `/full-sdlc`

## 4. Writing the regression test

The test must:
- **Fail** on `develop` (verify by checking out `develop` and running it)
- **Pass** after the fix
- Be **named** with the Jira key: `EPMICMPHE_150_TtlExpiryRegressionTest`
- Live in the same module as the fix
- Test the **behaviour**, not the implementation. (If the fix is "add `@EnableScheduling`", the test asserts "expired entries are evicted within X seconds", not "the `@EnableScheduling` annotation is present".)
- Be the **smallest** test that demonstrates the bug. Resist the urge to add 5 related cases — open a follow-up Jira instead.

For static-analysis bugs (Sonar/SpotBugs/PMD) where a unit test is artificial:
- Regression "test" = the static rule itself, enforced in CI.
- Verify the rule trips on `develop` and clears on the branch. Cite the rule ID (e.g. `squid:S2259`) in the MR.

## 5. Anti-patterns the reviewer will reject

| Anti-pattern | Why it's wrong |
|--------------|----------------|
| Fix without a regression test | Bug will return |
| Catch-and-swallow the exception | Hides the cause |
| Wrap the symptom in `if (x == null) return;` | Symptom-fix, not cause-fix; see C.1 in code-reviewer |
| Add a `Thread.sleep()` to mask a race | Race is still there, just slower |
| Bump a timeout instead of fixing the slow code | Hides perf regressions |
| Edit the existing failing test's assertions to match new (wrong) behaviour | Erases the test's value |
| Change unrelated code "while I'm here" | Inflates blast radius, dilutes review |
| Fix > 1 bug in 1 MR | Reviewer can't reason about either; split |

## 6. Handoff artefacts

Every bug fix produces, in order, under `.codemie/bugs/<JIRA_KEY>/`:
- `repro.md` — Stage 3 evidence (stack trace, screenshot, k6 output, link to failing CI job)
- `rca.md` — Stage 4 (root cause, introducing commit, why tests missed it, blast radius)
- the regression test source file (in the module's `src/test/`, not in `.codemie/`)
- `handoff/bugfix-<JIRA_KEY>-stage-<n>.json` — per `.claude/shared/handoff-schema.md`

Without these artefacts the MR will not be merged — they are how the next agent (or the next human) understands what happened.

## 6.1 Durable memory (recall) — read on entry, write at every stage

The recall store at `~/.claude/memory/edgefabric.db` is the same memory tier `/full-sdlc` uses.
Every bug-fix run participates in it; without this, prior root causes are lost between sessions.

**Read on entry (Stage 0):**
```powershell
py .claude/scripts/recall.py recall --agent bugfix --topic "<one-line bug summary>" --limit 5
py .claude/scripts/recall.py recall --topic "<JIRA_KEY>" --limit 10
```
If a prior memory already names the same root cause + introducing commit, treat it as a
**duplicate**: link to the earlier Jira, copy the prior `rca.md`, write the regression test,
skip re-RCA. Comment the duplicate finding on the new Jira.

**Write after every stage end and at every escalation:**
```powershell
py .claude/scripts/recall.py remember `
  --agent bugfix --ticket "$JIRA_KEY" `
  --topic "<short event name>" --tags "bugfix,<source>,<module>" `
  --body "<one-line signal — paths, sha, test name, rule id>"
```
Body must be ≤ 200 chars. These are signals (for future search), not transcripts. See
`.claude/commands/bugfix.md` → "Memory rules" for the full event → topic/body table.

**Failure handling:** if recall calls fail (DB locked / missing / permission), log
`[recall] write failed (non-fatal)` and continue. Memory is best-effort — never abort the
fix because of it.

**Why this matters:**
- Detect duplicate bugs across sessions (same RCA → same fix)
- Accelerate triage of regressions (the prior memory says which test class proved it last time)
- Power `/recall <topic>` so the next dev/agent can find the prior decision
- Give the `context-compactor` and `sdlc-orchestrator` continuity across context windows

## 7. When to escalate out of `/bugfix`

Escalate to `/full-sdlc` (architecture work needed) when RCA reveals any of:
- Bug touches > 2 modules
- Fix changes a public API / wire contract
- Fix requires a new component (new bean, new MCP server, new EC2 role)
- Fix requires a data migration

Escalate to `/hotfix` when:
- Bug is reproducing **in production right now**
- Customer impact is active
