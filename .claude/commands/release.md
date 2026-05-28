---
description: Execute a full release with version bump, Jenkins build, Docker push, and AWS deploy
argument-hint: "(optional) version e.g. v1.2.0"
---

Execute a full release of EdgeFabric: version bump, changelog, config validation, Docker push,
Jenkins pipeline monitoring, SonarQube gate, AWS deploy, performance testing, and Jira close.

**Release version (optional):** $ARGUMENTS

## Steps

1. **Determine Version**
   ```bash
   LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0")
   echo "Last tag: $LAST_TAG — suggest: [next patch]"
   ```
   If no version provided, auto-use the suggested next patch — do NOT ask for confirmation.

2. **Create Release Branch from develop**
   ```bash
   VERSION=[version]   # e.g. v1.2.0
   git checkout develop
   git pull origin develop
   git checkout -b release/${VERSION}
   ```

3. **Version Bump and Changelog**
   ```bash
   ROOT=$(git rev-parse --show-toplevel)
   cd "$ROOT"
   mvn versions:set -DnewVersion=${VERSION#v} -DgenerateBackupPoms=false \
     -Dmaven.repo.local="$ROOT/.m2repo-lb-test"
   git log ${LAST_TAG}..HEAD --pretty=format:"- %s (%h)" --no-merges | head -50
   ```
   Commit the version bump:
   ```bash
   git add $(find . -name "pom.xml")
   git commit -m "chore: bump version to ${VERSION}"
   git push -u origin release/${VERSION}
   ```

4. **Config Validation**
   Invoke `docker-image-builder` agent — Phase 1 only:
   - All 9 invariants must pass
   - If any fail → fix and re-validate before proceeding

5. **Docker Build and Push**
   Invoke `docker-image-builder` agent — Phase 2 only:
   - Build: loadbalancer, cache-node, registry
   - Smoke test each image before pushing
   - Push to Docker Hub with tag `:v1`

6. **Monitor Release Pipeline**
   Record baseline build number for `release/${VERSION}` branch, then invoke `pipeline-monitor` agent:
   - BASELINE_BUILD: last build before the version-bump push
   - Branch: `release/${VERSION}`
   - Invocation context: release branch — fix on same branch if pipeline fails
   - Max 3 retries
   - STOP if all 3 fail — do not deploy

7. **SonarQube Gate (mandatory)**
   ```bash
   curl -s -u "$SONAR_TOKEN:" \
     "$SONAR_HOST_URL/api/qualitygates/project_status?projectKey=EPM-ICMP-HEREMES&branch=release/${VERSION}"
   ```
   If status != OK → STOP. Fix quality issues first. Never deploy without a passing gate.

8. **Deploy to Production**
   Run the `/deploy` command (do NOT invoke `aws-ssm-deployer` directly — all production deploys
   must flow through the single `/deploy` entry point to avoid double-deploy risk).
   The deploy command handles:
   - Phase 1: Discover EC2 instances (1 LB + 3 cache nodes)
   - Phase 2: Deploy LB (docker pull + restart)
   - Phase 3: Deploy cache nodes (sequential, IMDSv2, 15s between nodes)
   - Phase 4: Health verification (3/3 Cloud Map nodes, LB nodeCount=3)
   - Phase 5: CloudWatch monitoring (5 min post-deploy)
   - Performance smoke test (post-deploy health check)

9. **Performance Testing**
   Invoke `performance-tester` agent:
   - 8 k6 scenarios: Load, Stress, Spike, Soak, Scalability, Breakpoint, Volume, Failover
   - Print full enterprise results table

10. **Merge release to main and develop**
    ```bash
    git checkout main && git pull origin main
    git merge --no-ff release/${VERSION} -m "release: merge ${VERSION} to main"

    git checkout develop && git pull origin develop
    git merge --no-ff release/${VERSION} -m "chore: merge ${VERSION} release back to develop"

    git tag -a ${VERSION} -m "Release ${VERSION}"
    git push origin main develop ${VERSION}
    ```

11. **Close Release Jira Issues**
    ```
    mcp__atlassian__search_issues(jql="project = EPMICMPHE AND status = 'In Review'")
    ```
    For each result:
    ```
    mcp__atlassian__update_issue_status(status_name="Done")
    mcp__atlassian__add_issue_comment(comment="Released in ${VERSION} — deployed to production.")
    ```

12. **Update Confluence Release Notes**
    ```
    mcp__atlassian__confluence_create_page(
      space_key="EPMICMP",
      parent_id="2808363846",
      title="Release ${VERSION} — EdgeFabric",
      content="<version, changelog, performance results, deployment status>"
    )
    ```

13. **Summary**
    ```
    ═══════════════════════════════════════
    RELEASE ${VERSION} COMPLETE
    ═══════════════════════════════════════
    ✓ Version bumped in all pom.xml files
    ✓ Config validation: 9/9 passed
    ✓ Docker images pushed: loadbalancer:v1, cache-node:v1, registry:v1
    ✓ Jenkins pipeline: PASSED
    ✓ SonarQube: PASSED
    ✓ Deployed to production (3/3 nodes healthy)
    ✓ Performance: p99 PUT <Xms> | p99 GET <Xms> | errors <X%>
    ✓ Git tag: ${VERSION}
    ✓ Jira issues closed: N
    ✓ Release notes: <Confluence URL>
    ```

## Safety Rules
- Never skip the SonarQube gate for a release
- Never deploy without a successful Docker smoke test (docker-image-builder Phase 2)
- Never skip pipeline monitoring — never deploy from a failed pipeline
- Always create a git tag
- If any step fails, stop and clearly report what failed

## Memory write events

> Follow `.claude/shared/memory-protocol.md` for protocol. Use `--agent release --tags "release,<env>"`.
> **Stage 0 explicit read** (no Jira key in prompt — auto-hook misses it):
> `py .claude/scripts/recall.py recall --agent release --topic "Release <version>" --limit 10`

| Event | `--topic` | `--body` (example) |
|-------|-----------|--------------------|
| Version bumped | `Release v1.4.0 version-bump` | `pom.xml caching/loadbalancer/registry from 1.3.0 to 1.4.0` |
| Branch cut | `Release v1.4.0 branch` | `release/v1.4.0 from develop sha=abc1234` |
| Quality gate | `Release v1.4.0 quality-gate <PASS\|FAIL>` | `sonar PASSED; coverage 84%; 0 BLOCKER` |
| Docker push | `Release v1.4.0 docker-push` | `loadbalancer:v1.4.0 cache-node:v1.4.0 registry:v1.4.0 → docker.io/anubhavpratap` |
| Deploy | `Release v1.4.0 deployed` | `ap-south-1 lb=2 cache=3 registry=1 ssm-cmd=xyz` |
| Smoke test | `Release v1.4.0 smoke <PASS\|FAIL>` | `health 200 OK on all nodes; perf p95 PUT=42ms GET=12ms` |
| Tag pushed | `Release v1.4.0 tag` | `git tag v1.4.0 sha=def5678 pushed to origin` |
| Final outcome | `release RESULT <RELEASED\|FAILED>` | `v1.4.0 live; release notes Confluence page <id>` |
