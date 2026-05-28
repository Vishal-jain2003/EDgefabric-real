---
description: Run SonarQube analysis and display a full quality report for the current branch
argument-hint: "(optional) branch or context"
---

Run a SonarQube quality check on the current branch and display a full quality report.

**Branch or context (optional):** $ARGUMENTS

## Steps

1. **Identify Branch**
   ```bash
   BRANCH=$(git branch --show-current)
   echo "Checking quality for branch: $BRANCH"
   ```

2. **Run Maven SonarQube Analysis**
   ```bash
   ROOT=$(git rev-parse --show-toplevel)
   cd "$ROOT"
   mvn sonar:sonar \
     -Dsonar.host.url="$SONAR_HOST_URL" \
     -Dsonar.token="$SONAR_TOKEN" \
     -Dsonar.projectKey=EPM-ICMP-HEREMES \
     -Dsonar.branch.name="$BRANCH" \
     -Dmaven.repo.local="$ROOT/.m2repo-lb-test" \
     2>&1 | tail -30
   ```

3. **Fetch Quality Gate Status**
   Use Fetch MCP:
   ```
   GET ${SONAR_HOST_URL}api/qualitygates/project_status?projectKey=EPM-ICMP-HEREMES&branch=${BRANCH}
   Headers: Authorization: Bearer ${SONAR_TOKEN}
   ```
   Parse `.projectStatus.status` and `.projectStatus.conditions[]`.
   Print each condition: metric, actual value, threshold, status (OK/ERROR).

4. **Fetch New Issues**
   Use Fetch MCP:
   ```
   GET ${SONAR_HOST_URL}api/issues/search?componentKeys=EPM-ICMP-HEREMES&resolved=false&branch=${BRANCH}&types=BUG,VULNERABILITY,CODE_SMELL&ps=50
   Headers: Authorization: Bearer ${SONAR_TOKEN}
   ```
   Group by severity: BLOCKER, CRITICAL, MAJOR, MINOR.
   Show file:line for each BLOCKER and CRITICAL issue.

5. **Fetch Coverage Metrics**
   Use Fetch MCP:
   ```
   GET ${SONAR_HOST_URL}api/measures/component?component=EPM-ICMP-HEREMES&branch=${BRANCH}&metricKeys=coverage,line_coverage,branch_coverage,uncovered_lines,new_coverage
   Headers: Authorization: Bearer ${SONAR_TOKEN}
   ```

6. **Fetch Technical Debt**
   Use Fetch MCP:
   ```
   GET ${SONAR_HOST_URL}api/measures/component?component=EPM-ICMP-HEREMES&branch=${BRANCH}&metricKeys=sqale_index,sqale_rating,reliability_rating,security_rating
   Headers: Authorization: Bearer ${SONAR_TOKEN}
   ```

7. **Display Full Report**
   ```
   === SonarQube Quality Report ===
   Branch:        [branch]
   Gate:          ✅ OK  |  ❌ FAILED

   Conditions:
   ┌─────────────────────────┬──────────┬───────────┬────────┐
   │ Metric                  │ Actual   │ Threshold │ Status │
   ├─────────────────────────┼──────────┼───────────┼────────┤
   │ New Coverage            │ 82.3%    │ ≥ 80%     │ ✅     │
   │ New Bugs                │ 0        │ = 0       │ ✅     │
   │ New Vulnerabilities     │ 0        │ = 0       │ ✅     │
   │ New Code Smells         │ 3        │ ≤ 5       │ ✅     │
   └─────────────────────────┴──────────┴───────────┴────────┘

   Issues (new):
   - BLOCKER: 0
   - CRITICAL: 1 — [file:line description]
   - MAJOR: 3
   - MINOR: 5

   Technical Debt: 2h 15m (Rating: A)
   Coverage: 84.2% overall
   ```

8. **Decision**
   - If quality gate PASSED: "✅ Safe to merge. Run /submit-pr or /release."
   - If quality gate FAILED: List exactly what needs to be fixed. "Run /full-sdlc to fix or fix manually then re-run /check-quality."
   - If coverage below 80%: identify uncovered classes and suggest running `test-runner` agent with `mode=unit`.

9. Provide the SonarQube dashboard link: `${SONAR_HOST_URL}dashboard?id=EPM-ICMP-HEREMES&branch=${BRANCH}`
