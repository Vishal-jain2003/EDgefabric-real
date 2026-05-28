---
description: Run the full agentic SDLC for a Jira ticket — from requirements to deployed code
argument-hint: "JIRA_KEY (e.g. EPMICMPHE-42)"
---

## When to use this command

**Use `/full-sdlc`** when:
- Starting from a **raw Jira ticket** — no spec, no branch, no architecture decision yet
- You want the complete end-to-end pipeline managed automatically
- You need sprint gates, architecture strategies, ADR, TDD, CI/CD, and optionally deployment

**Use `/tdd-implement` instead** when:
- You **already have an approved spec** in `.codemie/specs/<TICKET>/`
- Architecture was approved in a previous session and you just need to implement
- You want a faster loop without re-running planning stages

---

Run the complete EdgeFabric agentic SDLC for a Jira ticket using the orchestrator agent.
Chains all agents in order: architecture → TDD implementation → quality → CI/CD → deploy → docs.

**Jira ticket:** $ARGUMENTS

## Steps

1. **Parse Jira Key**
   Extract from $ARGUMENTS.
   ```bash
   JIRA_KEY=$(echo "$ARGUMENTS" | grep -oP '[A-Z]+-\d+' | head -1)
   ```
   If not found → STOP: "Please provide a Jira issue key (e.g. EPMICMPHE-42)."

2. **Invoke sdlc-orchestrator agent**
   Run the `sdlc-orchestrator` agent with the Jira key.

   The sdlc-orchestrator runs the complete SDLC:
   - Stage 0:   Sync develop
   - Stage 0.5: Sprint + status + duplicate/semantic checks (hard gates)
   - Stage 1:   solution-architect → ADR + local spec → Gate 2 (human approval)
   - Stage 1.5: Create feature branch (immediately after Gate 2 approval, before any code is written)
   - Stage 2:   test-writer (RED) → java-implementer (GREEN) → test-runner (unit + integration + coverage + E2E)
   - Stage 3:   code-reviewer → fix CRITICAL/MAJOR → optional dead-code-cleaner → SonarQube gate
   - Stage 4:   commit + push → docker-image-builder (config validation) → pipeline-monitor → MR → Gate 3 (human approval)
   - Stage 4.5: Confluence page updated → Jira Done
   - Stage 5:   pipeline-monitor watches develop post-merge
   - Stage 6:   docker-image-builder (Docker push) + deployment-verifier (AWS health check) [release/* only]

   Use this exact invocation:
   ```
   Agent(
     subagent_type="sdlc-orchestrator",
     prompt="""
     Run the complete EdgeFabric agentic SDLC for Jira ticket <JIRA_KEY>.

     Project context:
     - Working directory: C:\Users\AnubhavPratapSingh\Documents\edgefabric2
     - Git branch strategy: feature/<TICKET>-<slug> branches from develop
     - Jira project key: EPMICMPHE
     - Jenkins base path: /jenkins
     - SonarQube project key: EPM-ICMP-HEREMES
     - Docker Hub username: anubhavpratap
     - AWS region: ap-south-1

     Execute all stages in order:
     - Stage 0:   Sync develop
     - Stage 0.5: Sprint + status + duplicate/semantic checks (hard gates — NO bypass)
     - Stage 1:   solution-architect → ADR + local spec → Gate 2 (stop for human approval)
     - Stage 1.5: Create feature branch immediately after Gate 2 approval
     - Stage 2:   test-writer (RED) → java-implementer (GREEN) → test-runner (unit + integration + coverage + E2E)
     - Stage 3:   code-reviewer → fix CRITICAL/MAJOR → optional dead-code-cleaner → SonarQube gate
     - Stage 4:   commit + push → docker-image-builder (config validation) → pipeline-monitor → MR → Gate 3 (stop for human approval)
     - Stage 4.5: Confluence page updated → Jira Done  [runs after Gate 3]
     - Stage 5:   pipeline-monitor watches develop post-merge
     - Stage 6:   docker-image-builder (Docker push) + deployment-verifier (AWS health check)  [release/* only]

     Stop at Gate 2 (after solution-architect produces spec) for human approval before Stage 1.5.
     Stop at Gate 3 (after MR pipelines are green) for human approval before merging.
     """
   )
   ```
