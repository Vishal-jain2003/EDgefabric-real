---
description: Recall prior decisions from memory for the current ticket or a topic
---

Run:

```bash
py .claude/scripts/recall.py recall --use-ctx-ticket --topic "$ARGUMENTS" --limit 5 --max-tokens 500
```

If no ticket is on the current branch, ask the user for the topic or ticket.
Print the recalled bullets verbatim — do not paraphrase.
