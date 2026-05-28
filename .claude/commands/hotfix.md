---
description: Execute an emergency hotfix workflow from bug report to production
argument-hint: "bug description"
---

Execute an emergency hotfix workflow: create a Jira bug, branch from main, fix, test, and fast-track to production.

**Bug description:** $ARGUMENTS

## Steps

1. **Create Jira Bug (High Priority)**
   ```
   mcp__atlassian__create_issue(
     project="EPMICMPHE", issuetype="Bug", priority="High",
     summary="<bug description>",
     description="<Overview + Steps to reproduce + Expected vs Actual>"
   )
   ```
   Note the issue key (e.g. EPMICMPHE-99).

2. **Branch from main (not develop)**
   ```bash
   git checkout main
   git pull origin main
   git checkout -b hotfix/ISSUE_KEY-short-description
   ```
   Hotfixes branch from `main` so they can be deployed to production directly.

3. **Transition Jira to In Progress**
   ```
   mcp__atlassian__update_issue_status(issue_key="ISSUE_KEY", status_name="In Progress")
   ```

4. **Implement the Fix**
   Minimal change only — do not refactor surrounding code.
   Run: `mvn test -pl loadbalancer,caching,consistent-hashing` — fix any failures before proceeding.

5. **Commit and Push**
   ```bash
   git add [specific changed files — never git add -A]
   git commit -m "fix: ISSUE_KEY brief description of fix"
   git push -u origin hotfix/ISSUE_KEY-short-description
   ```

6. **Record Baseline + Monitor Hotfix Branch Pipeline**
   Record baseline before push, then invoke `pipeline-monitor` agent:
   - BASELINE_BUILD: last build number for this branch before push
   - Branch: `hotfix/ISSUE_KEY-short-description`
   - Invocation context: hotfix branch — fix on same branch
   - Max 3 retries

   Do NOT create MRs until the hotfix pipeline passes.

7. **Create GitLab MRs (to BOTH main AND develop)**
   ```
   # MR 1 — hotfix → main (production fix)
   mcp__gitlab__create_merge_request(
     source_branch="hotfix/ISSUE_KEY-short-description",
     target_branch="main",
     title="hotfix ISSUE_KEY: <summary>",
     description="## Hotfix\n**URGENT: Production fix — fast-track review required**\nJira: <JIRA_BASE_URL>/browse/ISSUE_KEY"
   )

   # MR 2 — hotfix → develop (backport)
   mcp__gitlab__create_merge_request(
     source_branch="hotfix/ISSUE_KEY-short-description",
     target_branch="develop",
     title="hotfix ISSUE_KEY: <summary> (backport to develop)",
     description="Backport of hotfix ISSUE_KEY to develop."
   )
   ```

8. **Monitor MR Pipelines**
   Each MR creation auto-triggers a Jenkins build on the same branch.

   Monitor MR 1 pipeline (hotfix → main):
   Invoke `pipeline-monitor` agent:
   - BASELINE_BUILD: hotfix branch pipeline build number (from Step 6)
   - Branch: hotfix branch
   - Max 3 retries

   Monitor MR 2 pipeline (hotfix → develop):
   Invoke `pipeline-monitor` agent:
   - BASELINE_BUILD: MR 1 pipeline build number
   - Branch: hotfix branch
   - Max 3 retries

   Do NOT proceed to deploy until BOTH MR pipelines pass.

9. **After Merge — Deploy to Production**
   Run `/deploy prod` to deploy the fix to production.

10. **Close Jira Issue**
    ```
    mcp__atlassian__update_issue_status(issue_key="ISSUE_KEY", status_name="Done")
    mcp__atlassian__add_issue_comment(
      issue_key="ISSUE_KEY",
      comment="Hotfix deployed to production.\nMR (main): <url>\nMR (develop): <url>\nDeployed: <timestamp>"
    )
    ```

## Important Notes
- Hotfixes ALWAYS branch from `main`, never from `develop`
- Must create MRs to BOTH `main` AND `develop` to keep branches in sync
- Keep changes minimal — hotfixes must be small and focused
- Never skip pipeline monitoring before creating MRs

## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol, env vars, failure handling,
> body/topic rules, and auto-compact. Use `--agent hotfix --tags "hotfix,prod,<module>"`.
> No explicit Stage 0 read needed — `prompt-context.py` auto-recalls by ticket.

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Bug created | `Hotfix bug created EPMICMPHE-99` | `priority=High customer-impact=active first-seen=prod` |
| Branch cut from main | `Hotfix branch hotfix/EPMICMPHE-99` | `from main sha=abc1234` |
| Reproduce | `Hotfix reproduced EPMICMPHE-99` | `reproducer at .codemie/bugs/.../repro.md` |
| RCA | `Hotfix RCA EPMICMPHE-99` | `cause=...; introduced sha=...; blast=loadbalancer only` |
| Regression test | `Hotfix regression test EPMICMPHE-99` | `test=Hotfix99Test fails on main` |
| Fix done | `Hotfix fix EPMICMPHE-99 GREEN` | `1 file changed: caching/.../X.java` |
| MR to main | `Hotfix MR-main EPMICMPHE-99` | `MR !88 -> main, sha=def5678` |
| MR to develop | `Hotfix MR-develop EPMICMPHE-99` | `MR !89 -> develop (sync)` |
| Deploy | `Hotfix deployed EPMICMPHE-99` | `prod ap-south-1 instances=3 ssm-cmd=xyz` |
| Final outcome | `hotfix RESULT FIXED` (or `ROLLED_BACK`) | `prod restored t=12m; rollback plan: revert MR !88` |
