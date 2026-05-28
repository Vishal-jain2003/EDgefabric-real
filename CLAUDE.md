# EdgeFabric2 — Project Context

## Codebase memory (READ FIRST)
Before grepping or scanning the repo, consult the auto-generated codebase index:
- `.codemie/codebase/OVERVIEW.md` — TL;DR of modules, ports, and how to navigate
- `.codemie/codebase/modules/<module>.md` — controllers, endpoints, services, configs, all types
- `.codemie/codebase/symbols.json` — O(1) class/interface/enum/record name → file:line lookup
- `.codemie/codebase/MANIFEST.json` — full machine-readable file metadata

Or call the **codebase** MCP server: `codebase_overview`, `codebase_module(name)`,
`find_symbol(name)`, `find_files(pattern)`, `list_endpoints(module?)`, `codebase_stats`.

Only fall back to grep / view / glob if the index does not answer your question.
The index is kept fresh by a pre-commit hook (`py scripts/install-hooks.py` after clone)
and a CI gate (`py mcp-servers/codebase_indexer.py --check`).

## What This Project Is
EdgeFabric is a distributed caching and load-balancing platform built with Java 21 / Spring Boot.

### Modules
| Module | Port | Description |
|--------|------|-------------|
| `loadbalancer/` | 8080 | HTTP load balancer using consistent hashing to route to cache nodes |
| `caching/` | 8082 | Distributed cache nodes with gossip-based cluster membership |
| `registry/` | — | Service registry — deployed separately on EC2, not in docker-compose |
| `consistent-hashing/` | — | Shared library for consistent hash ring |
| `ui/` | — | Frontend dashboard |

## Tech Stack
- Java 21, Spring Boot 3.x, Maven (multi-module parent pom)
- Docker, Docker Compose (used for local dev and E2E tests on develop)
- Gossip protocol (port 7946/udp), Failure detection (port 7000/udp)

## Docker Images (Docker Hub — not ECR)
- Load Balancer: `anubhavpratap/edgefabric-loadbalancer:v1`
- Cache Node: `anubhavpratap/edgefabric-cache-node:v1`
- Registry: `anubhavpratap/edgefabric-registry:v1`

## Git Branching Strategy
| Branch | Purpose |
|--------|---------|
| `main` | Production-ready code |
| `develop` | Integration branch — all features merge here |
| `feature/PROJ-XX-description` | New features |
| `bugFix/PROJ-XX-description` | Bug fixes |
| `hotfix/PROJ-XX-description` | Urgent production fixes (branch from main) |
| `release/vX.Y.Z` | Release preparation — triggers full deploy |

## Commit Message Format
```
feat: add TTL expiry for cache entries
fix: correct gossip port binding on startup
refactor: extract consistent hash ring to shared library
test: add integration tests for load balancer routing
chore: bump version to 1.2.0
docs: update README with cluster setup instructions
```

## CI/CD & Tools
- **Jira**: Sprint tracking — `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`, `JIRA_PROJECT_KEY`
- **Jenkins**: CI/CD — `JENKINS_URL`, `JENKINS_USER`, `JENKINS_TOKEN` (base path: /jenkins)
- **SonarQube**: Quality gate — `SONAR_HOST_URL`, `SONAR_TOKEN`, `SONAR_PROJECT_KEY=EPM-ICMP-HEREMES`
- **AWS**: EC2 + SSM + Cloud Map — `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `CLOUDMAP_SERVICE_ID`
- **Docker Hub**: Image registry — `DOCKER_HUB_USERNAME=anubhavpratap`

## Deployment Architecture (NO ECS, NO ECR)
- Images are built by Jenkins and pushed to **Docker Hub**
- Deployment uses **AWS SSM** — Jenkins sends shell commands directly to EC2 instances
- EC2 instances discovered by tag: `Role=hermes-loadbalancer` / `Role=hermes-cache-node`
- Cache nodes self-register their private IP into **AWS Cloud Map** (`srv-6lnd44knosnojplq`)
- Load balancer resolves cache node IPs via Cloud Map DNS (`cache-nodes.cache-cluster.internal`)

## Jenkins Pipeline Stages by Branch
| Branch | Stages run |
|--------|-----------|
| `feature/*` | Checkout → Build (parallel) → SonarQube → Quality Gate |
| `develop` | + Package (parallel) → E2E Tests (Docker Compose) |
| `release/*` | + Docker Build/Push to Hub → SSM Deploy to EC2 |
| `main` | + Docker Build/Push to Hub (prod deploy TBD) |

## Pipeline Rules
- PRs to `develop`: Jenkins build PASS + SonarQube quality gate PASS
- PRs to `main`: same + manual approval
- Tests run in Jenkins (`mvn verify`), skipped in Docker builds (`-DskipTests`)
- SonarQube project key: `EPM-ICMP-HEREMES`
- Quality gate timeout: 70 minutes

## Required Environment Variables
```bash
# Jira
JIRA_BASE_URL=https://jiraeu.epam.com/
JIRA_EMAIL=anubhav_pratapsingh@epam.com
JIRA_API_TOKEN=...
JIRA_PROJECT_KEY=EPMICMPHE

# Jenkins  (base path: /jenkins)
JENKINS_URL=https://jenkinshyd.epam.com/
JENKINS_USER=...
JENKINS_TOKEN=...

# SonarQube
SONAR_HOST_URL=https://sonarhyd.epam.com/
SONAR_TOKEN=...
SONAR_PROJECT_KEY=EPM-ICMP-HEREMES

# AWS (EC2 + SSM, no ECS/ECR)
AWS_REGION=ap-south-1
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
CLOUDMAP_SERVICE_ID=srv-6lnd44knosnojplq

# Docker Hub
DOCKER_HUB_USERNAME=anubhavpratap
```

## Structured Logging Standards

EdgeFabric uses **structured JSON logging** via Logstash Logback Encoder for machine-readable logs that enable automated analysis and Agentic ops.

### Log Format
All logs are output as JSON with the following schema:
```json
{
  "timestamp": "2026-05-12T10:23:45.123Z",
  "level": "INFO",
  "service": "edgefabric-loadbalancer",
  "traceId": "abc123",
  "message": "Cache PUT completed successfully",
  "nodeId": "node-1",
  "clusterId": "prod-cluster",
  "operation": "PUT",
  "duration": 45,
  "statusCode": 201,
  "result": "SUCCESS",
  "tenant": "acme-corp",
  "key": "user:session:xyz"
}
```

### Required Fields (automatically included)
- `timestamp` - ISO 8601 timestamp (UTC)
- `level` - Log level (INFO, WARN, ERROR, DEBUG)
- `service` - Service name from `spring.application.name`
- `traceId` - Distributed trace ID (when available)
- `message` - Human-readable log message

### Context Fields (via MDC)
Add these fields using `StructuredLogContext`:
- `nodeId` - Node identifier for distributed tracing
- `clusterId` - Cluster identifier
- `operation` - Operation type (GET, PUT, DELETE, GOSSIP_SYNC, etc.)
- `duration` - Operation duration in milliseconds
- `statusCode` - HTTP status code or result code
- `result` - Operation outcome (SUCCESS, FAILURE, TIMEOUT, etc.)
- `tenant` - Tenant identifier for multi-tenancy
- `key` - Cache key being operated on

### Error Logging Fields
For exceptions, include:
- `errorType` - Exception class name
- `errorMessage` - Exception message
- `stackTrace` - Full stack trace (automatically included by Logback)

### Usage Examples

#### Simple operation logging with context:
```java
try (var logCtx = StructuredLogContext.create()
        .operation("PUT")
        .tenant(tenant)
        .key(key)) {
    
    // Perform operation
    gatewayService.put(tenant, key, data, expiresAt, contentType);
    
    logCtx.duration(duration).statusCode(201).result("SUCCESS");
    logger.info("Cache PUT completed successfully");
}
```

#### Error logging with structured context:
```java
try (var logCtx = StructuredLogContext.create()
        .errorType(exc.getClass().getSimpleName())
        .errorMessage(exc.getMessage())
        .statusCode(500)) {
    logger.error("Operation failed", exc);
}
```

#### Adding custom context fields:
```java
try (var logCtx = StructuredLogContext.create()
        .operation("GOSSIP_SYNC")
        .nodeId(nodeId)
        .clusterId(clusterId)
        .put("syncType", "FULL")
        .put("peerCount", "3")) {
    logger.info("Gossip sync initiated");
}
```

### Configuration
Structured logging is configured in `logback-spring.xml` for each module using `LogstashEncoder`. The configuration:
- Uses async appenders to prevent I/O blocking
- Includes MDC keys automatically in JSON output
- Shortens stack traces for readability
- Sets service name from Spring properties

### Testing Structured Logs
Verify logs are JSON-parseable:
```bash
docker logs edgefabric-loadbalancer 2>&1 | tail -1 | jq '.'
```

Expected output:
```json
{
  "timestamp": "2026-05-12T10:23:45.123Z",
  "level": "INFO",
  "service": "edgefabric-loadbalancer",
  "message": "...",
  ...
}
```

