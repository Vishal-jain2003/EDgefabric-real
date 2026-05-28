---
description: Deploy EdgeFabric services to an AWS environment via SSM
argument-hint: "environment: dev | staging | prod"
---

Deploy EdgeFabric services to an AWS environment.

**Target environment:** $ARGUMENTS (e.g., `dev`, `staging`, `prod`. Defaults to `dev` if not specified.)

## Steps

1. **Validate Environment**
   Parse $ARGUMENTS to determine the target environment. Valid values: `dev`, `staging`, `prod`.
   If not provided, default to `dev`.
   For `prod` deployments, print a warning banner but proceed automatically -- do NOT ask for confirmation.

2. **Check Current Branch and Build**
   ```bash
   git branch --show-current
   git log --oneline -5
   ```
   For `prod` deployments, verify we are on `main` or a `release/*` branch.
   For `staging`, verify we are on `develop` or `release/*`.

3. **Build Maven Artifacts**
   ```bash
   # Build loadbalancer JAR
   cd loadbalancer && mvn clean package -DskipTests && cd ..
   ```

4. **Build Docker Images**
   ```bash
   GIT_TAG=$(git rev-parse --short HEAD)
   ENV_TAG=ENV_NAME-$GIT_TAG
   docker build -t anubhavpratap/edgefabric-loadbalancer:$ENV_TAG ./loadbalancer
   docker build -t anubhavpratap/edgefabric-cache-node:$ENV_TAG ./caching
   ```

5. **Build Docker Images and Deploy**
   Use the **aws-ssm-deployer agent** to:
   - Login to Docker Hub
   - Tag images with the environment-specific tag
   - Push both images to Docker Hub
   - Deploy via SSM to the appropriate EC2 instances
   - Wait for services to reach stable state

6. **Verify Deployment**
   Use the **aws-ssm-deployer agent** to:
   - Check running containers on EC2 instances via SSM
   - Tail CloudWatch logs for 30 seconds to check for startup errors
   - Confirm cache nodes re-register in Cloud Map

7. **Update Jira (for staging/prod deployments)**
   If deploying to staging or prod, use Atlassian MCP `mcp__atlassian__add_issue_comment` to add a comment
   to the active sprint's in-review issues: "Deployed to [ENVIRONMENT] -- image tag: $ENV_TAG"

8. **Performance Testing (post-deployment)**
   After successful deployment verification, run performance tests:

   **For `prod` and `staging` environments:**
   Invoke the `performance-tester` agent to run the full k6 enterprise performance suite:
   - Find load generator EC2 (same AZ as LB, tag: `Role=hermes-load-generator`)
   - Run all 8 scenarios: Load, Stress, Spike, Soak, Scalability, Breakpoint, Volume, Failover
   - Generate the enterprise performance report with SLA pass/fail verdicts
   - Post performance summary to Jira

   **For `dev` environment:**
   Run a lightweight smoke performance check via the `aws-ssm-deployer` agent Phase 6 (smoke mode):
   - 10 PUT + 10 GET cycles to verify basic latency
   - Report avg latency and confirm SLA compliance at low load
   - Recommend full performance test if latency exceeds expectations

   If no load generator EC2 is found (tag: `Role=hermes-load-generator`):
   - Print exact setup instructions for creating one
   - Skip performance testing but warn that SLA compliance is NOT validated

9. **Summary**
   Report: environment, image tags deployed, EC2/SSM deployment status, performance results, any errors found in logs.
   Include:
   - Deployment status: healthy / unhealthy
   - Performance: p95 PUT / p95 GET / error rate (from perf test)
   - SLA verdict: PASS / FAIL / NOT TESTED

## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol. Use `--agent deploy --tags "deploy,<env>,<role>"`.
> **Stage 0 explicit read** (deploys may run without a Jira key):
> `py .claude/scripts/recall.py recall --agent deploy --topic "deploy <env>" --limit 10`
> Pay special attention to recalled "deploy <env> FAILED" entries — they may indicate a recurring failure mode.

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Targets resolved | `Deploy <env> targets` | `role=hermes-cache-node count=3 ids=i-aaa,i-bbb,i-ccc` |
| SSM command sent | `Deploy <env> ssm-sent` | `cmd-id=abc123 image=cache-node:v1.4.0` |
| SSM completed | `Deploy <env> ssm <SUCCESS\|FAILED>` | `3/3 instances Success; durations 22s,24s,21s` |
| Health check | `Deploy <env> health <PASS\|FAIL>` | `200 OK on all nodes; cluster size=3 stable` |
| CloudMap | `Deploy <env> cloudmap` | `srv-6lnd44 instances registered=3` |
| Perf smoke | `Deploy <env> perf <PASS\|FAIL\|SKIPPED>` | `p95 PUT=42ms GET=12ms err=0%; SLA PASS` |
| Final outcome | `deploy RESULT <env> <DEPLOYED\|FAILED\|ROLLED_BACK>` | `v1.4.0 healthy; rollback: re-deploy v1.3.0 via /deploy` |
