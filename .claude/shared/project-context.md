# EdgeFabric — Shared Project Context (CACHED snippet)

> Included by every agent. Keep this file STABLE so prompt caching pays off.
> Anything that changes per task belongs in the agent's body, not here.

## What EdgeFabric is
Distributed cache + load-balancer platform. Java 21, Spring Boot 3.4.x, Maven multi-module.

## Modules

| Module | Port | Role |
|--------|------|------|
| `loadbalancer` | 8080 | HTTP load balancer; routes via consistent hashing |
| `caching` | 8082 | Cache nodes; gossip-based cluster membership |
| `registry` | — | Service registry (deployed separately on EC2) |
| `consistent-hashing` | — | Shared hash-ring library |
| `testing_edgefabric` | — | Integration / E2E tests |
| `ui` | — | Frontend dashboard |

## Branching
| Branch | Purpose |
|--------|---------|
| `main` | Production-ready |
| `develop` | Integration |
| `feature/<JIRA>-…` | New features |
| `bugFix/<JIRA>-…` | Bug fixes |
| `hotfix/<JIRA>-…` | Branched from main |
| `release/vX.Y.Z` | Release prep — triggers full deploy |

## Deployment
- Images on **Docker Hub** (`anubhavpratap/edgefabric-*:v1`), NOT ECR
- Jenkins → SSM RunCommand → EC2 (tag `Role=hermes-loadbalancer` / `hermes-cache-node`)
- Cache nodes self-register into Cloud Map (`srv-6lnd44knosnojplq`)

## Quality gates
- SonarQube project `EPM-ICMP-HEREMES`, line coverage ≥ 80% (`coverage.minimum`)
- Jenkins pipeline must PASS before merge
- PRs target `develop`; `main` requires manual approval

## Required env vars
`JIRA_*`, `JENKINS_*`, `SONAR_*`, `AWS_*`, `CLOUDMAP_SERVICE_ID`, `DOCKER_HUB_USERNAME`.
