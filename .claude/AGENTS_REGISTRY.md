# EdgeFabric Agent & Command Registry (auto-generated)

> Run `py .claude/scripts/build-agent-registry.py` to refresh.

## Agents

| Agent | Model | Size | Description |
|-------|-------|------|-------------|
| `aws-ssm-deployer` | sonnet | 12 KB | AWS deployment and log investigation agent — discovers EC2 instances by tag, deploys via SSM (no SSH), monitors health via Cloud Map, streams CloudWatch logs, and investigates gossip/quorum anomalies. Merges cloud-deploy + log-investigator. |
| `code-reviewer` | sonnet | 20 KB | - |
| `context-compactor` | haiku | 3 KB | - |
| `dead-code-cleaner` | haiku | 6 KB | - |
| `deployment-verifier` | haiku | 11 KB | - |
| `docker-image-builder` | haiku | 10 KB | Build pipeline agent — validates config consistency across all config files (Phase 1), builds and pushes Docker images via Docker Hub (Phase 2, release/main branches ONLY). Jenkins pipeline monitoring is handled by the pipeline-monitor agent. |
| `java-implementer` | sonnet | 9 KB | - |
| `performance-tester` | sonnet | 31 KB | Senior Performance Test Engineer / SRE agent for EdgeFabric AWS releases — runs 8 enterprise-grade test types (Load, Stress, Spike, Soak, Scalability, Breakpoint, Volume, Failover/Resilience) from a dedicated EC2 load generator in the same region and AZ as the deployed services. Uses k6 via AWS SSM. Invoke after a release/* branch has been deployed to EC2. Produces a full enterprise report with p50/p90/p95/p99, throughput, error rate, JVM tuning suggestions, autoscaling thresholds, and RCA guidance. |
| `pipeline-monitor` | haiku | 13 KB | - |
| `scrum-master` | haiku | 24 KB | - |
| `sdlc-orchestrator` | opus | 37 KB | - |
| `solution-architect` | opus | 14 KB | - |
| `test-runner` | haiku | 24 KB | - |
| `test-writer` | sonnet | 8 KB | - |

## Slash Commands

| Command | Description |
|---------|-------------|
| `/bugfix` | Fix a non-emergency bug found in dev/QA — reproduce, root-cause, regression-test, fix, MR |
| `/check-quality` | Run SonarQube analysis and display a full quality report for the current branch |
| `/clean-branches` | List stale branches, cross-check Jira status, and delete safe ones |
| `/compact` | Compact the current session into a structured summary + durable memory, freeing the context window |
| `/create-doc` | Create or update a Confluence documentation page for a feature or topic |
| `/create-story` | Create a Jira story with acceptance criteria (Gate 1) |
| `/deploy` | Deploy EdgeFabric services to an AWS environment via SSM |
| `/design-story` | Generate architecture strategies and write an ADR to Confluence for a Jira story |
| `/full-sdlc` | Run the full agentic SDLC for a Jira ticket — from requirements to deployed code |
| `/hotfix` | Execute an emergency hotfix workflow from bug report to production |
| `/recall` | Recall prior decisions from memory for the current ticket or a topic |
| `/release` | Execute a full release with version bump, Jenkins build, Docker push, and AWS deploy |
| `/review-mr` | Review a GitLab MR for bugs, errors, security issues, and EdgeFabric architecture violations |
| `/start-feature` | Create a Jira story and feature branch in one step |
| `/tdd-implement` | Implement a Jira story using TDD — write tests first, implement, create MR (requires approved spec) |
| `/validate` | Validate config consistency and SonarQube quality gate for the current branch |
