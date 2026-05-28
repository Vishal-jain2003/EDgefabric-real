#!/usr/bin/env python3
# PreToolUse guard — blocks dangerous bash commands before they execute.
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
            "additionalContext": f"[SAFETY WARNING] {message}"
        }
    }))
    sys.exit(0)


try:
    data = json.load(sys.stdin)
    cmd = data.get("tool_input", {}).get("command", "")
except Exception:
    sys.exit(0)

if not cmd:
    sys.exit(0)

# ── Git ──────────────────────────────────────────────────────────────────────

if re.search(r'git\s+push.*(--force|-f)\b', cmd):
    deny("Force push is never allowed. Push normally and raise an MR to develop.")

if re.search(r'git\s+push\s+(origin\s+)?(main|develop|master)\b', cmd):
    deny("Direct push to main/develop is not allowed. Use a feature/* or bugFix/* branch and raise an MR.")

if re.search(r'git\s+reset\s+--hard', cmd):
    deny("git reset --hard can lose uncommitted work. Use git stash, git restore, or git revert instead.")

if re.search(r'git\s+branch\s+.*-D\b', cmd):
    deny("Force-deleting a branch (-D) is not allowed. Ensure the branch is merged first.")

if re.search(r'git\s+clean\s+.*-f\b', cmd):
    deny("git clean -f permanently deletes untracked files. This action is blocked.")

# ── AWS ──────────────────────────────────────────────────────────────────────

if re.search(r'aws\s+ec2\s+terminate-instances', cmd):
    deny("EC2 instance termination requires explicit human confirmation. Agents must never terminate instances autonomously.")

if re.search(r'aws\s+(ec2|iam|rds|eks|ecs)\s+delete-', cmd):
    deny("Destructive AWS delete operations require explicit human confirmation. Never run these autonomously.")

if re.search(r'aws\s+s3\s+(rm|delete)\b.*--recursive', cmd):
    deny("Recursive S3 deletion requires explicit human confirmation.")

# ── File system ───────────────────────────────────────────────────────────────

rm_match = re.search(r'\brm\s+-[rRfF]{1,3}\s+(\S+)', cmd)
if rm_match:
    target = rm_match.group(1)
    if not re.match(r'^(/tmp|/var/tmp|\$TMPDIR|\$TEMP|\.m2repo-lb-test|target/|\.m2test/)', target):
        deny("rm -rf on non-temporary directories is blocked. Delete files explicitly by name.")

# ── Secret exposure ───────────────────────────────────────────────────────────

if re.search(r'\b(cat|type)\s+\S*\.(env|pem|key|p12|jks|pfx|pkcs12)\b', cmd, re.IGNORECASE):
    deny("Reading credential files (.env, .pem, .key, etc.) is blocked to prevent secret exposure in logs.")

if re.search(r"(^|[;&|]\s*)\s*(printenv|env)\s*($|#)", cmd, re.MULTILINE):
    warn("Printing all environment variables may expose secrets in logs. Be specific about which variable you need.")

# ── Supply chain ──────────────────────────────────────────────────────────────

if re.search(r'(curl|wget).*\|\s*(bash|sh|python3?|node|ruby|perl)\b', cmd, re.IGNORECASE):
    deny("Piping remote scripts directly to an interpreter is blocked (supply chain risk). Download the file first, inspect it, then run it.")

# ── Docker ────────────────────────────────────────────────────────────────────

if re.search(r'docker(-compose)?\s+(down\s+.*-v\b|volume\s+rm)', cmd):
    warn("This command deletes Docker volumes and ALL stored data. Confirm this is intentional before continuing.")


# ── Maven release ─────────────────────────────────────────────────────────────

if re.search(r'(^|[;&|`]\s*)\s*mvn.*release:(prepare|perform|rollback|clean)', cmd, re.MULTILINE | re.IGNORECASE):
    deny("mvn release:* requires explicit human approval. Maven releases modify versions, create git tags, and deploy artifacts. Never run autonomously.")

# ── Version tags ──────────────────────────────────────────────────────────────

if re.search(r'(^|[;&|`]\s*)\s*git\s+tag\s+.*v[0-9]+', cmd, re.MULTILINE):
    deny("Creating release version tags requires explicit human approval. Tags trigger production pipelines.")

# ── Package publishing ────────────────────────────────────────────────────────

if re.search(r"(^|[;&|`]\s*)\s*(npm|yarn|pnpm)\s+publish", cmd, re.MULTILINE | re.IGNORECASE):
    deny("Publishing packages (npm/yarn publish) requires explicit human approval.")

# ── Kubernetes ────────────────────────────────────────────────────────────────

if re.search(r"(^|[;&|`]\s*)\s*kubectl\s+(delete|exec|drain|cordon|taint)", cmd, re.MULTILINE | re.IGNORECASE):
    deny("Destructive kubectl operations require explicit human approval. Never run kubectl delete/exec/drain autonomously.")

if re.search(r"(^|[;&|`]\s*)\s*kubectl\s+apply", cmd, re.MULTILINE | re.IGNORECASE):
    warn("kubectl apply will modify cluster state. Confirm the target context is correct.")

# ── Docker push ───────────────────────────────────────────────────────────────

if re.search(r"(^|[;&|`]\s*)\s*docker\s+push", cmd, re.MULTILINE | re.IGNORECASE):
    warn("Pushing a Docker image to a registry. Confirm the tag and registry are correct.")

sys.exit(0)
