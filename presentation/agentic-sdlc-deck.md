---
marp: true
theme: default
paginate: true
size: 16:9
header: "EdgeFabric · Agentic SDLC"
footer: "Anubhav Pratap Singh · 2026"
style: |
  section { font-size: 26px; }
  h1 { color: #6f42c1; }
  h2 { color: #0366d6; }
  table { font-size: 22px; }
  code { background: #f6f8fa; padding: 2px 6px; border-radius: 4px; }
  .small { font-size: 20px; }
---

<!-- _class: lead -->
# 🤖 From Chatbot to Agentic SDLC
## What I built on top of a "simple" LLM agent

**EdgeFabric** — A distributed cache + load balancer
shipped end-to-end by a team of **14 specialised AI agents**

---

## The "Simple Agent" baseline

A vanilla LLM coding assistant gives you:

- ✅ One LLM, one chat, one context window
- ✅ Generic file read/write/grep
- ✅ Generic shell access
- ❌ **No memory** beyond the current chat
- ❌ **No specialisation** — same prompt for everything
- ❌ **No external systems** — can't see Jira, Jenkins, AWS, SonarQube
- ❌ **No guardrails** — can run destructive commands, leak secrets
- ❌ **No process** — no SDLC, no quality gates, no handoffs

> Great for snippets. Useless for shipping a real product.

---

## What I built

A **multi-agent SDLC platform** with **5 layers** the simple agent doesn't have:

| Layer | What it adds |
|-------|--------------|
| 🧠 **Specialist agents** | 14 role-specific agents, each with own model, tools, prompt |
| 🔌 **MCP tool servers** | 7 custom servers wiring Jira / GitLab / Jenkins / AWS / Sonar / Confluence / Codebase |
| 🛡️ **Hooks & guardrails** | 12 deterministic hooks running before/after every tool call |
| 📚 **Persistent memory** | Codebase index, recall store, handoff JSONs, session logs |
| 🎯 **Slash commands** | 15 one-line workflows (`/full-sdlc EF-42`) |

---

## 🧠 Layer 1 — The Agent Roster (14 agents)

| Agent | Model | Job |
|-------|-------|-----|
| `sdlc-orchestrator` | **opus** | Top-level conductor — chains everything below |
| `scrum-master` | haiku | Gate-0.5: validates Jira readiness (7 blockers, 5 warnings) |
| `solution-architect` | **opus** | 3 strategies + ADR to Confluence + local spec |
| `test-writer` | sonnet | TDD Red — writes failing tests first |
| `java-implementer` | sonnet | TDD Green — production code |
| `test-runner` | haiku | Runs unit / integration / e2e / coverage / perf / chaos |
| `code-reviewer` | haiku | High-signal-only review |
| `dead-code-cleaner` | haiku | Removes unused / duplicate code |

---

## 🧠 Agent Roster (cont.)

| Agent | Model | Job |
|-------|-------|-----|
| `docker-image-builder` | haiku | Validates configs + pushes to Docker Hub |
| `pipeline-monitor` | haiku | Watches Jenkins, auto-fixes, retries (max 3) |
| `aws-ssm-deployer` | sonnet | Deploys via SSM, no SSH |
| `deployment-verifier` | haiku | EC2 + CloudMap + health checks |
| `performance-tester` | sonnet | k6 load / stress / spike / soak / breakpoint / volume / failover |
| `context-compactor` | haiku | Compacts session at 90% ctx, persists lessons |

**Key insight:** right model for the job → opus for design, sonnet for code, haiku for I/O work.
Cost-optimised, not "throw GPT-4 at everything".

---

## 🔌 Layer 2 — Custom MCP Servers

A simple agent only sees files. My agents see **the whole stack**.

| Server | Lines | Capability |
|--------|-------|------------|
| `jira_server.py` | 42K | Stories, sprints, epics, comments, status, find-best-epic |
| `gitlab_server.py` | 27K | Branches, MRs, pipelines, commits, jobs, diffs |
| `aws_server.py` | 14K | EC2 by tag, SSM commands, CloudMap, CloudWatch logs |
| `codebase_indexer.py` | 14K | O(1) symbol lookup, module summaries, REST endpoints |
| `jenkins_server.py` | 10K | Trigger builds, fetch logs, status |
| `sonarqube_server.py` | 7K | Quality gate, issues, metrics, hotspots |
| Confluence (in jira) | – | KB pages, ADRs, runbooks |

---

## 🛡️ Layer 3 — Hooks & Guardrails

Deterministic Python scripts that run **outside the LLM** for every tool call.

| Hook | When | What it prevents / does |
|------|------|------------------------|
| `guard-bash.py` | PreToolUse Bash | Blocks `rm -rf`, name-based kills, secret leaks |
| `guard-writes.py` | PreToolUse Edit/Write | Blocks writes outside repo, protected files |
| `guard-mcp.py` | PreToolUse mcp__* | Enforces per-agent MCP allow-lists |
| `cache-mcp.py` | PreToolUse mcp__* | **Returns cached result if same query hit recently** |
| `post-compile.py` | PostToolUse Edit | Auto-runs `mvn compile` after Java edits |
| `post-config-check.py` | PostToolUse | Validates config consistency (compose ↔ pom ↔ Jenkins) |
| `token-budget.py` | Stop | Tracks token spend per agent / per ticket |
| `session-log.py` | Stop | Persists turn-by-turn audit log |

---

## 🛡️ Why hooks matter

The simple agent: *"I'll just run `rm -rf node_modules`"* → gone.

My agent: same intent → **`guard-bash.py` blocks it before the shell sees it.**

- ✅ Deterministic safety (Python, not LLM judgment)
- ✅ MCP response **caching** = ~40% fewer API calls
- ✅ Every tool call **audited** to JSONL
- ✅ Token budget **enforced** per ticket

---

## 📚 Layer 4 — Persistent Memory

Simple agent forgets everything when chat ends.

My agents have **4 memory tiers**:

1. **Codebase Index** (`.codemie/codebase/`)
   Auto-generated `OVERVIEW.md`, per-module pages, `symbols.json` for O(1) class lookup.
   Refreshed via pre-commit hook + CI gate.

2. **Recall Store** (`.claude/scripts/recall.py`)
   Durable lessons keyed by Jira ticket / topic — queryable across sessions.

3. **Handoff JSONs** (`.codemie/handoff/stage-N-*.json`)
   Each agent writes a structured handoff the next agent reads as its only context.

4. **Session Store** (SQLite)
   Every turn, every file edited, every PR/commit — full history, FTS5 searchable.

---

## 🎯 Layer 5 — Slash Commands (15)

One line → entire workflow.

```
/full-sdlc EF-42        →  scrum-master → architect → test-writer →
                            implementer → test-runner → reviewer →
                            docker-builder → pipeline-monitor →
                            ssm-deployer → verifier  (with auto-compact)

/start-feature          →  Jira story + Git branch in one step
/design-story EF-42     →  3 strategies + ADR + Confluence page
/tdd-implement EF-42    →  Red → Green → Refactor → MR
/review-mr !57          →  Bug / security / architecture review
/release v1.4.0         →  Bump → build → push → deploy
/hotfix "cache crash"   →  Branch from main → fix → ship
/check-quality          →  SonarQube full report
/clean-branches         →  List stale branches, cross-check Jira, delete safe ones
/recall "auth"          →  Pull prior decisions on auth
/compact                →  Summarise session, free context
```

---

## 🔄 Real Workflow — `/full-sdlc EF-42`

```
 Jira EF-42  ─►  scrum-master    (Gate 0.5: ready?)            ❌ → STOP
                       ▼ APPROVED
                solution-architect (3 strategies → ADR)         ⏸  Human Gate 2
                       ▼ APPROVED
                test-writer  →  java-implementer  →  test-runner   (TDD loop)
                       ▼
                code-reviewer  →  dead-code-cleaner
                       ▼
                docker-image-builder  →  pipeline-monitor (auto-fix x3)
                       ▼
                aws-ssm-deployer  →  deployment-verifier
                       ▼
                performance-tester (8 k6 test types)
                       ▼
                context-compactor  →  recall store
```

**Two human gates** (story approval, ADR approval) — everything else autonomous.

---

## 📊 Simple Agent vs My Agentic SDLC

| Capability | Simple Agent | My System |
|-----------|:------------:|:---------:|
| Multi-step coding | ✅ | ✅ |
| Specialised roles | ❌ | ✅ 14 agents |
| Right-sized models (cost) | ❌ | ✅ opus / sonnet / haiku per role |
| Sees Jira / GitLab / Jenkins / AWS / Sonar | ❌ | ✅ 7 MCP servers |
| Codebase index (O(1) lookup) | ❌ | ✅ |
| Cross-session memory | ❌ | ✅ recall + session store |
| Deterministic safety guards | ❌ | ✅ 12 hooks |
| MCP response caching | ❌ | ✅ ~40% fewer calls |
| Token budget tracking | ❌ | ✅ per agent / ticket |
| Auto-fix CI failures | ❌ | ✅ pipeline-monitor (3 retries) |
| End-to-end SDLC in one command | ❌ | ✅ `/full-sdlc EF-42` |
| Performance test suite | ❌ | ✅ 8 k6 test types |
| Auto-compact at 90% context | ❌ | ✅ context-compactor |

---

## 💡 What this unlocks

- 🚀 **End-to-end ticket delivery** with **2 human gates** instead of 20 manual steps
- 💰 **Cost control** — haiku does cheap I/O, opus reserved for design
- 🛡️ **Safety by construction** — bad commands die in Python, not in prod
- 📈 **Observability** — every action logged, every cost tracked
- 🧠 **Institutional memory** — decisions outlive the chat session
- 🔁 **Self-healing CI** — pipeline-monitor patches its own failures

---

<!-- _class: lead -->
# Demo

`/full-sdlc EPMICMPHE-102`

Watch 14 agents ship a feature
from Jira → architecture → code → tests → MR → Docker → AWS → verified.

---

<!-- _class: lead -->
# Thank you 🙏

**Anubhav Pratap Singh**
EdgeFabric · Agentic SDLC Platform

Questions?
