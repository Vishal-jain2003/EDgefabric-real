#!/usr/bin/env python3
# PreToolUse guard — blocks writes to confidential files and detects hardcoded secrets.
import sys
import json
import re
import os


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
            "additionalContext": f"[SECURITY WARNING] {message}"
        }
    }))
    sys.exit(0)


try:
    data = json.load(sys.stdin)
    tool_input = data.get("tool_input", {})
    content = tool_input.get("content", "")
    new_string = tool_input.get("new_string", "")   # Edit tool uses new_string
    file_path = tool_input.get("file_path", "")
except Exception:
    sys.exit(0)

norm_path = file_path.replace("\\", "/") if file_path else ""
filename = os.path.basename(norm_path)
check_content = content or new_string

# ── Block writes to confidential/system files ─────────────────────────────────

BLOCKED_FILES = [
    (r'(^|/)\.env(\b|\.|$)', "Writing to .env files is blocked. Credentials must stay in mcp-servers/.env only — never propagate them into other files."),
    (r'\.(pem|key|p12|jks|pfx|pkcs12)$', "Writing to private key or certificate files is blocked."),
    (r'(^|/)(credentials|secrets)\.(json|yml|yaml|xml)$', "Writing to credentials/secrets files is blocked."),
    (r'(^|/)\.git/config$', "Writing to .git/config is blocked. Use git config commands instead."),
    (r'(^|/)\.claude/hooks/guard-', "Agents cannot modify security guard scripts. These are tamper-protected."),
    (r'(^|/)\.claude/hooks/audit-', "Agents cannot modify audit scripts. These are tamper-protected."),
    (r'(^|/)audit-logs/', "Audit logs are append-only and cannot be modified by agents."),
    (r'(^|/)\.ssh/', "Writing to .ssh/ directory is blocked."),
    (r'(^|/)\.gnupg/', "Writing to .gnupg/ directory is blocked."),
    (r'(^|/)mcp-servers/\.env$', "Writing directly to the .env secrets file is blocked. Edit it manually."),
    (r'(^|/)\.github/workflows/', 'Writing to GitHub Actions workflow files is blocked. CI/CD pipeline changes require human review.'),
    (r'(^|/)(deploy|release|entrypoint)\.sh$', 'Writing to deployment/release scripts is blocked. These require human review.'),
]

for pattern, reason in BLOCKED_FILES:
    if re.search(pattern, norm_path, re.IGNORECASE):
        deny(reason)


# Warn on writes to pom.xml (version/dependency changes can break builds)
if re.search(r'(^|/)pom\.xml$', norm_path, re.IGNORECASE):
    warn("pom.xml modification detected. Verify version numbers and dependency changes are intentional.")

# ── Warn on sensitive config files ───────────────────────────────────────────

SENSITIVE_CONFIGS = [
    (r'application-(prod|production|staging)\.(yml|yaml|properties)$', "production config"),
    (r'bootstrap\.(yml|yaml)$', "bootstrap config"),
    (r'^Jenkinsfile$', "CI/CD pipeline definition"),
]

for pattern, label in SENSITIVE_CONFIGS:
    if re.search(pattern, filename, re.IGNORECASE):
        warn(f"{filename} is a {label}. Verify no credentials or environment-specific secrets are being introduced.")

# ── Skip test files (intentionally use fake values) ───────────────────────────

if re.search(r'(src[/\\]test|Test\.java|IT\.java|Spec\.java)', norm_path):
    sys.exit(0)

if not check_content:
    sys.exit(0)

# ── Detect hardcoded secrets ──────────────────────────────────────────────────

SECRET_PATTERN = re.compile(
    r'(api[_-]?key|api[_-]?token|password|passwd|secret|private[_-]?key|'
    r'access[_-]?token|auth[_-]?token|client[_-]?secret|bearer)\s*[=:]\s*'
    r'["\'](?!\$\{)[A-Za-z0-9+/._@\-]{10,}["\']',
    re.IGNORECASE
)

if SECRET_PATTERN.search(check_content):
    deny(
        f"Possible hardcoded secret detected in {filename or 'file'}. "
        "Use environment variable references (${VAR_NAME}) instead of literal values."
    )

# ── Detect private key blocks ─────────────────────────────────────────────────

if re.search(r'-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----', check_content):
    deny(f"Private key material detected in {filename or 'file'}. Never embed private keys in source files.")

sys.exit(0)
