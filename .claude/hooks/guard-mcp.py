#!/usr/bin/env python3
# PreToolUse guard — blocks critical MCP tool operations that must not run autonomously.
import sys
import json
import re


def deny(reason):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason
        }
    }))
    sys.exit(0)


def warn(message):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": f"[MCP GUARD] {message}"
        }
    }))
    sys.exit(0)


try:
    data = json.load(sys.stdin)
    tool = data.get("tool_name", "")
    inp  = data.get("tool_input", {})
except Exception:
    sys.exit(0)

if not tool:
    sys.exit(0)

PROTECTED_BRANCHES = re.compile(r'^(main|master|develop)$', re.IGNORECASE)

# ── GitLab ────────────────────────────────────────────────────────────────────

# Merging MRs is a one-way human gate — never autonomous
if tool == "mcp__gitlab__merge_merge_request":
    deny(
        "Merging a merge request requires explicit human approval. "
        "Agents must not merge autonomously. Ask the user to review and merge."
    )

# Deleting branches
if tool == "mcp__gitlab__delete_branch":
    branch = inp.get("branch") or inp.get("branch_name", "")
    if PROTECTED_BRANCHES.match(branch):
        deny(f"Deleting the protected branch '{branch}' is never allowed.")
    else:
        warn(f"About to delete branch '{branch}'. Confirm this is intentional.")

# Committing directly to protected branches — must go via MR
if tool == "mcp__gitlab__commit_files":
    branch = inp.get("branch", "")
    if PROTECTED_BRANCHES.match(branch):
        deny(
            f"Direct commits to '{branch}' are not allowed. "
            "Push to a feature branch and raise an MR instead."
        )

# Triggering pipelines manually
if tool == "mcp__gitlab__trigger_pipeline":
    ref = inp.get("ref", "")
    if PROTECTED_BRANCHES.match(ref):
        warn(f"Manually triggering a pipeline on '{ref}'. Confirm this is intentional.")

# ── Jenkins ───────────────────────────────────────────────────────────────────

if tool == "mcp__jenkins__trigger_build":
    job = inp.get("job_name", "")
    if re.search(r'(release/|/main\b|/master\b)', job, re.IGNORECASE):
        deny(
            f"Triggering the production pipeline '{job}' requires explicit human approval. "
            "Production releases must never be triggered autonomously."
        )
    elif re.search(r'/develop\b', job, re.IGNORECASE):
        warn(
            f"Triggering the develop pipeline '{job}'. "
            "This will run E2E tests and Docker builds. Confirm this is intentional."
        )

# ── AWS (via MCP) ─────────────────────────────────────────────────────────────

if tool.startswith("mcp__aws__"):
    action = tool.split("__")[-1] if "__" in tool else ""
    if re.search(r'(terminate|delete|destroy|remove|deregister)', action, re.IGNORECASE):
        deny(
            f"Destructive AWS operation '{tool}' requires explicit human approval. "
            "Agents must never terminate or delete AWS resources autonomously."
        )

# ── Atlassian / Jira ──────────────────────────────────────────────────────────

if tool == "mcp__atlassian__update_issue_status":
    status = str(inp.get("status") or inp.get("transition_name") or "")
    if re.search(r'\b(done|closed|resolved|rejected|cancelled|wont.?fix)\b', status, re.IGNORECASE):
        warn(
            f"About to mark Jira issue as '{status}'. "
            "Verify this is the correct final status before proceeding."
        )

# ── Notion ────────────────────────────────────────────────────────────────────

# Block deleting Notion pages
if tool == "mcp__notion__API-delete-a-block":
    warn("About to delete a Notion block/page. Confirm this is intentional.")

sys.exit(0)
