---
description: Compact the current session into a structured summary + durable memory, freeing the context window
---

Invoke the **context-compactor** agent now.

Goal: replace the current bloated context with a ≤ 800-token briefing written to
`.codemie/handoff/<TICKET>-context-summary.md`, persist durable decisions into the
recall memory store (`~/.claude/memory/edgefabric.db`), and print the briefing to stdout.

After it returns, treat the printed briefing as the only authoritative context for
follow-up work in this session.
