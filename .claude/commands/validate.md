---
description: Validate config consistency and SonarQube quality gate for the current branch
argument-hint: "(optional) extra context"
---

Validate configuration consistency and SonarQube quality gate for the current branch.

**Extra context (optional):** $ARGUMENTS

## Steps

1. **Config Consistency Check**
   Invoke the `docker-image-builder` agent Phase 1 only (config validation):
   ```
   Run docker-image-builder agent with instruction: "Run Phase 1 config validation only. Do not build images."
   ```

   Checks 8 invariants:
   - LB port = 8080
   - Cache port = 8082
   - Gossip port = 7946
   - CLUSTER_DNS consistency
   - Docker image names match anubhavpratap/ org
   - Cloud Map ID = srv-6lnd44knosnojplq
   - caching/start.sh uses IMDSv2 + exec java
   - Maven repo paths

2. **SonarQube Quality Gate**
   Use Fetch MCP to check the current branch gate status:
   ```
   GET ${SONAR_HOST_URL}api/qualitygates/project_status?projectKey=EPM-ICMP-HEREMES&branch=[current_branch]
   Headers: Authorization: Bearer ${SONAR_TOKEN}
   ```

   Parse `.projectStatus.status`:
   - `"OK"` → ✅ gate passed
   - `"ERROR"` → ❌ fetch issues and list them

   Fetch new issues if gate failed:
   ```
   GET ${SONAR_HOST_URL}api/issues/search?componentKeys=EPM-ICMP-HEREMES&resolved=false&branch=[branch]&severities=BLOCKER,CRITICAL&ps=20
   Headers: Authorization: Bearer ${SONAR_TOKEN}
   ```

3. **Coverage Summary**
   Run coverage check via `test-runner` agent with `mode=coverage`.
   Print the module-by-module coverage table with thresholds.

4. **Summary Report**
   ```
   === Validation Summary ===
   Config:     ✅ 8/8 invariants passed  (or ❌ N failed — list them)
   SonarQube:  ✅ Quality gate OK        (or ❌ gate FAILED — list blockers)
   Coverage:   ✅ All modules ≥ threshold (or ❌ modules below threshold)

   Next step: [proceed to /submit-pr | fix the listed issues]
   ```
