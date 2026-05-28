import os
import re
import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
from dotenv import load_dotenv
from pathlib import Path

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

JIRA_URL               = os.getenv("JIRA_URL", "").rstrip("/")
JIRA_TOKEN             = os.getenv("JIRA_TOKEN")
CONFLUENCE_URL         = os.getenv("CONFLUENCE_URL", "https://kb.epam.com").rstrip("/")
CONFLUENCE_API_TOKEN   = os.getenv("CONFLUENCE_API_TOKEN")
JIRA_PROJECT_KEY       = os.getenv("JIRA_PROJECT_KEY", "EF")
CONFLUENCE_SPACE_KEY   = os.getenv("CONFLUENCE_SPACE_KEY", "EPMICMP")
CONFLUENCE_DOCS_SPACE  = os.getenv("CONFLUENCE_DOCS_SPACE", "EPMICMP")
CONFLUENCE_BACKLOG_PAGE_ID = os.getenv("CONFLUENCE_BACKLOG_PAGE_ID", "2732958957")

app = Server("jira-server")

# ── HTTP helpers ──────────────────────────────────────────────────────────────

def _jira_headers():
    return {"Authorization": f"Bearer {JIRA_TOKEN}", "Content-Type": "application/json"}

def _confluence_headers():
    return {"Authorization": f"Bearer {CONFLUENCE_API_TOKEN}", "Content-Type": "application/json"}

def jira_get(path: str, params: dict = {}):
    r = httpx.get(f"{JIRA_URL}/rest/api/2/{path}", params=params,
                  headers=_jira_headers(), timeout=30, follow_redirects=True)
    r.raise_for_status()
    return r.json()

def jira_post(path: str, payload: dict):
    r = httpx.post(f"{JIRA_URL}/rest/api/2/{path}", json=payload,
                   headers=_jira_headers(), timeout=30, follow_redirects=True)
    r.raise_for_status()
    return r.json() if r.content else {}

def jira_put(path: str, payload: dict):
    r = httpx.put(f"{JIRA_URL}/rest/api/2/{path}", json=payload,
                  headers=_jira_headers(), timeout=30, follow_redirects=True)
    r.raise_for_status()
    return r.json() if r.content else {}

def confluence_get(path: str, params: dict = {}):
    r = httpx.get(f"{CONFLUENCE_URL}/rest/api/{path}", params=params,
                  headers=_confluence_headers(), timeout=30, follow_redirects=True)
    r.raise_for_status()
    return r.json()

def confluence_post(path: str, payload: dict):
    r = httpx.post(f"{CONFLUENCE_URL}/rest/api/{path}", json=payload,
                   headers=_confluence_headers(), timeout=30, follow_redirects=True)
    r.raise_for_status()
    return r.json()

def confluence_put(path: str, payload: dict):
    r = httpx.put(f"{CONFLUENCE_URL}/rest/api/{path}", json=payload,
                  headers=_confluence_headers(), timeout=30, follow_redirects=True)
    r.raise_for_status()
    return r.json()

# ── Formatting helpers ────────────────────────────────────────────────────────

def _extract_text(adf_node) -> str:
    """Recursively extract plain text from Atlassian Document Format (ADF)."""
    if not adf_node:
        return ""
    if isinstance(adf_node, str):
        return adf_node
    node_type = adf_node.get("type", "")
    text = adf_node.get("text", "")
    content = adf_node.get("content", [])
    result = text
    if node_type in ("hardBreak", "rule"):
        result += "\n"
    for child in content:
        result += _extract_text(child)
    if node_type in ("paragraph", "heading", "listItem", "bulletList", "orderedList"):
        result += "\n"
    return result

def _format_issue(issue: dict) -> str:
    fields = issue.get("fields", {})
    description_raw = fields.get("description") or {}
    description = (
        _extract_text(description_raw).strip()
        if isinstance(description_raw, dict)
        else str(description_raw or "")
    )

    result  = f"Issue: {issue['key']} — {fields.get('summary', 'No title')}\n"
    result += f"  Type:     {fields.get('issuetype', {}).get('name', 'N/A')}\n"
    result += f"  Status:   {fields.get('status', {}).get('name', 'N/A')}\n"
    result += f"  Priority: {fields.get('priority', {}).get('name', 'N/A')}\n"
    result += f"  Assignee: {(fields.get('assignee') or {}).get('displayName', 'Unassigned')}\n"
    result += f"  Story Points: {fields.get('customfield_10016', 'N/A')}\n"

    if description:
        result += f"\nDescription:\n{description}\n"

    ac_raw = fields.get("customfield_10021") or fields.get("customfield_10034") or {}
    ac = _extract_text(ac_raw).strip() if isinstance(ac_raw, dict) else str(ac_raw or "")
    if ac:
        result += f"\nAcceptance Criteria:\n{ac}\n"

    epic_link = (
        fields.get("customfield_10014")
        or fields.get("customfield_10008")
        or (fields.get("parent") or {}).get("key", "")
    )
    if epic_link:
        result += f"\nEpic: {epic_link}\n"

    return result

def _strip_html(html: str) -> str:
    plain = re.sub(r"<[^>]+>", " ", html)
    return re.sub(r"\s{2,}", "\n", plain).strip()

# ── Epic scoring ──────────────────────────────────────────────────────────────

# Domain → signals: words in a feature description that imply this domain
_DOMAIN_SIGNALS: dict[str, list[str]] = {
    "observability": [
        "monitor", "metric", "stat", "hit rate", "miss rate", "memory", "health",
        "slo", "sla", "alert", "latency", "throughput", "kpi", "telemetry",
        "tracing", "insight", "report", "gauge", "counter", "dashboard",
    ],
    "security": [
        "auth", "tls", "ssl", "encrypt", "token", "jwt", "rbac", "rate limit",
        "throttl", "access control", "secret", "firewall", "cors", "csrf",
        "permission", "acl", "credential",
    ],
    "cache": [
        "cache", "ttl", "evict", "expir", "entry", "store", "hit", "miss",
        "get", "put", "delete", "key", "value", "lru", "lfu",
    ],
    "api": [
        "endpoint", "rest", "/api", "http", "request", "response",
        "json", "payload", "status code",
    ],
    "load balanc": [
        "route", "routing", "proxy", "upstream", "consistent hash",
        "distribute", "weight", "round robin", "forward",
    ],
    "cluster": [
        "gossip", "cluster", "membership", "node", "peer",
        "failure detect", "heartbeat", "join", "leave",
    ],
    "replication": [
        "replica", "shard", "partition", "quorum", "consistency",
        "replicat", "raft", "paxos", "synchron",
    ],
    "scaling": [
        "scale", "rebalance", "autoscale", "elastic",
        "add node", "remove node", "hot spot",
    ],
    "frontend": [
        "ui", "dashboard", "angular", "operator", "admin", "frontend",
        "browser", "panel", "visual",
    ],
    "deployment": [
        "deploy", "release", "docker", "aws", "ec2", "production", "rollout",
    ],
    "ci": [
        "pipeline", "test", "jenkins", "build", "integration", "e2e", "chaos",
    ],
    "durability": [
        "persist", "recovery", "backup", "durability", "crash", "restore", "wal",
    ],
}

_STOPWORDS = {
    "and", "or", "the", "a", "an", "in", "on", "for", "of", "to", "with",
    "is", "are", "was", "that", "this", "it", "as", "at", "by", "from",
    "be", "have", "has", "had", "not", "but", "if", "so", "can", "do",
    "we", "i", "you", "all", "any", "get", "set", "new", "per",
}

def _score_epic(epic_name: str, epic_key: str, description: str) -> tuple[int, list[str]]:
    """Return (score, reasons) for how well an epic matches a feature description."""
    score = 0
    reasons: list[str] = []
    desc = description.lower()
    name = epic_name.lower()

    # 1. Words from epic name found verbatim in description (weight 3)
    name_tokens = [
        w.strip("()&–-/") for w in name.split()
        if len(w) > 3 and w.strip("()&–-/") not in _STOPWORDS
    ]
    for token in name_tokens:
        if token and token in desc:
            score += 3
            reasons.append(f"'{token}' (epic name word) in description")

    # 2. Domain signal matching: for each domain whose substring appears in epic name,
    #    count how many of its signals appear in the description (weight 2 each, capped at 8)
    for domain, signals in _DOMAIN_SIGNALS.items():
        if domain in name:
            matched = [s for s in signals if s in desc]
            if matched:
                pts = min(len(matched) * 2, 8)
                score += pts
                reasons.append(f"domain '{domain}' — signals: {matched[:4]}")

    # 3. Reverse: count description words that appear in epic name (weight 2)
    desc_tokens = [
        w.strip(".,;:!?()") for w in desc.split()
        if len(w) > 4 and w.strip(".,;:!?()") not in _STOPWORDS
    ]
    for token in desc_tokens:
        if token and token in name and token not in [r.split("'")[1] for r in reasons if "'" in r]:
            score += 2
            reasons.append(f"'{token}' (description word) in epic name")

    return score, reasons


# ── Tool registry ─────────────────────────────────────────────────────────────

@app.list_tools()
async def list_tools():
    return [
        # ── Existing tools ──────────────────────────────────────────────────
        Tool(
            name="get_issue",
            description="Get full details of a Jira issue including description and acceptance criteria",
            inputSchema={
                "type": "object",
                "properties": {
                    "issue_key": {"type": "string", "description": "Jira issue key e.g. EF-42"}
                },
                "required": ["issue_key"],
            },
        ),
        Tool(
            name="list_sprint_issues",
            description="List all issues in the current active sprint for the project",
            inputSchema={
                "type": "object",
                "properties": {
                    "project_key": {
                        "type": "string",
                        "description": f"Jira project key. Defaults to {JIRA_PROJECT_KEY}",
                    },
                    "status": {
                        "type": "string",
                        "description": "Filter by status: 'To Do', 'In Progress', 'Done'. Omit for all.",
                    },
                },
            },
        ),
        Tool(
            name="get_epic_stories",
            description="Get all stories/tasks under a given epic",
            inputSchema={
                "type": "object",
                "properties": {
                    "epic_key": {"type": "string", "description": "Jira epic key e.g. EF-10"}
                },
                "required": ["epic_key"],
            },
        ),
        Tool(
            name="add_issue_comment",
            description="Post a comment on a Jira issue (e.g. deployment report, review findings)",
            inputSchema={
                "type": "object",
                "properties": {
                    "issue_key": {"type": "string"},
                    "comment": {"type": "string", "description": "Plain text comment to post"},
                },
                "required": ["issue_key", "comment"],
            },
        ),
        Tool(
            name="update_issue_status",
            description="Transition a Jira issue to a new status (e.g. move to In Progress or Done)",
            inputSchema={
                "type": "object",
                "properties": {
                    "issue_key": {"type": "string"},
                    "status_name": {
                        "type": "string",
                        "description": "Target status name e.g. 'In Progress', 'In Review', 'Done'",
                    },
                },
                "required": ["issue_key", "status_name"],
            },
        ),
        # ── New Jira tools ──────────────────────────────────────────────────
        Tool(
            name="search_issues",
            description=(
                "Search Jira issues using JQL. Use for sprint checks, epic lookups, "
                "duplicate detection, or any custom query."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "jql": {
                        "type": "string",
                        "description": (
                            "JQL query string e.g. "
                            "'project = EPMICMPHE AND sprint in openSprints()'"
                        ),
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Max issues to return. Default 50.",
                        "default": 50,
                    },
                    "fields": {
                        "type": "string",
                        "description": (
                            "Comma-separated field names to include. "
                            "Default: summary,status,issuetype,priority,assignee"
                        ),
                    },
                },
                "required": ["jql"],
            },
        ),
        Tool(
            name="create_issue",
            description=(
                "Create a new Jira issue (Story, Bug, Task, Epic, Sub-task). "
                "Returns the new issue key and URL."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "summary": {"type": "string", "description": "Issue title / summary"},
                    "description": {
                        "type": "string",
                        "description": "Plain text description (markdown allowed)",
                    },
                    "issue_type": {
                        "type": "string",
                        "description": "Issue type: Story | Bug | Task | Epic | Sub-task. Default: Story",
                        "default": "Story",
                    },
                    "priority": {
                        "type": "string",
                        "description": "Priority: Highest | High | Medium | Low | Lowest. Default: Medium",
                        "default": "Medium",
                    },
                    "project_key": {
                        "type": "string",
                        "description": f"Jira project key. Defaults to {JIRA_PROJECT_KEY}",
                    },
                    "labels": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Optional list of labels",
                    },
                },
                "required": ["summary"],
            },
        ),
        Tool(
            name="update_issue",
            description=(
                "Update fields on an existing Jira issue. "
                "Only the fields you provide are changed."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "issue_key": {"type": "string", "description": "Jira issue key e.g. EF-42"},
                    "summary": {"type": "string", "description": "New summary"},
                    "description": {"type": "string", "description": "New plain-text description"},
                    "priority": {
                        "type": "string",
                        "description": "New priority: Highest | High | Medium | Low | Lowest",
                    },
                    "labels": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Replace labels with this list",
                    },
                },
                "required": ["issue_key"],
            },
        ),
        Tool(
            name="link_issues",
            description=(
                "Create a link between two Jira issues. "
                "Use link_type='Epos-Story Link' to attach a story to an epic."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "inward_issue_key": {
                        "type": "string",
                        "description": "The issue that is linked FROM (e.g. the story)",
                    },
                    "outward_issue_key": {
                        "type": "string",
                        "description": "The issue that is linked TO (e.g. the epic)",
                    },
                    "link_type": {
                        "type": "string",
                        "description": "Link type name. Default: 'Epos-Story Link'",
                        "default": "Epos-Story Link",
                    },
                },
                "required": ["inward_issue_key", "outward_issue_key"],
            },
        ),
        Tool(
            name="find_best_epic",
            description=(
                "Find the best matching open epic for a feature description. "
                "Scores ALL open epics using keyword overlap and domain signals, "
                "then returns the top match with its reasoning and a ranked shortlist."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "feature_description": {
                        "type": "string",
                        "description": (
                            "Full feature description or user story text. "
                            "The more detail, the better the match."
                        ),
                    },
                    "project_key": {
                        "type": "string",
                        "description": f"Jira project key. Defaults to {JIRA_PROJECT_KEY}",
                    },
                },
                "required": ["feature_description"],
            },
        ),
        # ── Confluence tools ────────────────────────────────────────────────
        Tool(
            name="get_confluence_page",
            description="Get the content of a Confluence page by title (KB docs, architecture, runbooks)",
            inputSchema={
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "Exact or partial page title to search for",
                    },
                    "space_key": {
                        "type": "string",
                        "description": f"Confluence space key. Defaults to {CONFLUENCE_SPACE_KEY}",
                    },
                },
                "required": ["title"],
            },
        ),
        Tool(
            name="get_confluence_page_by_id",
            description=(
                f"Get a Confluence page by its numeric page ID. "
                f"Use page_id='{CONFLUENCE_BACKLOG_PAGE_ID}' for the Product Backlog page "
                f"(space RD). This is the most reliable way to fetch a known page."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "page_id": {
                        "type": "string",
                        "description": f"Numeric Confluence page ID. Product Backlog = {CONFLUENCE_BACKLOG_PAGE_ID}",
                    }
                },
                "required": ["page_id"],
            },
        ),
        Tool(
            name="list_documentation_pages",
            description=(
                f"List all pages in the EdgeFabric documentation Confluence space ({CONFLUENCE_DOCS_SPACE}). "
                "Use this to discover available architecture docs, runbooks, and design decisions."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "space_key": {
                        "type": "string",
                        "description": f"Confluence space key. Defaults to {CONFLUENCE_DOCS_SPACE}",
                    },
                    "search_term": {
                        "type": "string",
                        "description": "Optional keyword to filter pages by title",
                    },
                },
            },
        ),
        Tool(
            name="create_confluence_page",
            description="Create a new Confluence KB page in the EdgeFabric space",
            inputSchema={
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Page title"},
                    "content": {
                        "type": "string",
                        "description": "Page body in plain text or basic HTML/Confluence storage format",
                    },
                    "space_key": {
                        "type": "string",
                        "description": f"Confluence space key. Defaults to {CONFLUENCE_SPACE_KEY}",
                    },
                    "parent_id": {
                        "type": "string",
                        "description": "Optional parent page ID to nest this page under",
                    },
                },
                "required": ["title", "content"],
            },
        ),
        Tool(
            name="update_confluence_page",
            description=(
                "Update the content of an existing Confluence page by ID. "
                "Automatically fetches the current version number — you do not need to provide it."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "page_id": {
                        "type": "string",
                        "description": "Numeric Confluence page ID",
                    },
                    "title": {
                        "type": "string",
                        "description": "New page title (leave blank to keep existing title)",
                    },
                    "content": {
                        "type": "string",
                        "description": "New page body in plain text or Confluence storage format (HTML)",
                    },
                    "append": {
                        "type": "boolean",
                        "description": (
                            "If true, append content to existing page body instead of replacing it. "
                            "Default: false"
                        ),
                        "default": False,
                    },
                },
                "required": ["page_id", "content"],
            },
        ),
    ]


# ── Tool handlers ─────────────────────────────────────────────────────────────

@app.call_tool()
async def call_tool(name: str, arguments: dict):

    # ── get_issue ────────────────────────────────────────────────────────────
    if name == "get_issue":
        issue = jira_get(f"issue/{arguments['issue_key']}", {"expand": "renderedFields"})
        return [TextContent(type="text", text=_format_issue(issue))]

    # ── list_sprint_issues ────────────────────────────────────────────────────
    elif name == "list_sprint_issues":
        project = arguments.get("project_key", JIRA_PROJECT_KEY)
        status_filter = arguments.get("status", "")
        jql = f'project = "{project}" AND sprint in openSprints()'
        if status_filter:
            jql += f' AND status = "{status_filter}"'
        jql += " ORDER BY priority DESC"

        data = jira_get("search", {
            "jql": jql, "maxResults": 50,
            "fields": "summary,status,issuetype,priority,assignee,customfield_10016",
        })
        issues = data.get("issues", [])
        if not issues:
            return [TextContent(type="text", text="No issues found in current sprint.")]

        result = f"Sprint Issues for {project} ({len(issues)} total):\n\n"
        for issue in issues:
            f = issue["fields"]
            pts = f.get("customfield_10016", "?")
            result += f"  [{f['status']['name']}] {issue['key']} — {f['summary']}\n"
            result += (
                f"    Type: {f['issuetype']['name']} | Points: {pts} "
                f"| Assignee: {(f.get('assignee') or {}).get('displayName', 'Unassigned')}\n\n"
            )
        return [TextContent(type="text", text=result)]

    # ── get_epic_stories ──────────────────────────────────────────────────────
    elif name == "get_epic_stories":
        epic_key = arguments["epic_key"]
        jql = f'"Epic Link" = {epic_key} OR parent = {epic_key} ORDER BY created ASC'
        data = jira_get("search", {
            "jql": jql, "maxResults": 50,
            "fields": "summary,status,issuetype,priority,assignee",
        })
        issues = data.get("issues", [])
        if not issues:
            return [TextContent(type="text", text=f"No stories found under epic {epic_key}")]

        result = f"Stories under {epic_key} ({len(issues)}):\n\n"
        for issue in issues:
            f = issue["fields"]
            result += f"  [{f['status']['name']}] {issue['key']} — {f['summary']}\n"
            result += f"    Type: {f['issuetype']['name']} | Assignee: {(f.get('assignee') or {}).get('displayName', 'Unassigned')}\n\n"
        return [TextContent(type="text", text=result)]

    # ── search_issues ─────────────────────────────────────────────────────────
    elif name == "search_issues":
        jql = arguments["jql"]
        max_results = arguments.get("max_results", 50)
        fields = arguments.get(
            "fields", "summary,status,issuetype,priority,assignee,customfield_10016"
        )
        data = jira_get("search", {"jql": jql, "maxResults": max_results, "fields": fields})
        issues = data.get("issues", [])
        total = data.get("total", 0)

        if not issues:
            return [TextContent(type="text", text=f"No issues found.\nJQL: {jql}")]

        result = f"Search results ({len(issues)} of {total} total):\nJQL: {jql}\n\n"
        for issue in issues:
            f = issue["fields"]
            status = f.get("status", {}).get("name", "N/A")
            itype = f.get("issuetype", {}).get("name", "N/A")
            priority = f.get("priority", {}).get("name", "N/A")
            assignee = (f.get("assignee") or {}).get("displayName", "Unassigned")
            pts = f.get("customfield_10016", "?")
            result += f"  {issue['key']} [{status}] — {f.get('summary', '')}\n"
            result += f"    {itype} | {priority} | pts={pts} | {assignee}\n\n"
        return [TextContent(type="text", text=result)]

    # ── create_issue ──────────────────────────────────────────────────────────
    elif name == "create_issue":
        project = arguments.get("project_key", JIRA_PROJECT_KEY)
        issue_type = arguments.get("issue_type", "Story")
        priority = arguments.get("priority", "Medium")
        description = arguments.get("description", "")
        labels = arguments.get("labels", [])

        fields: dict = {
            "project": {"key": project},
            "issuetype": {"name": issue_type},
            "summary": arguments["summary"],
            "description": description,
        }
        if labels:
            fields["labels"] = labels

        # Try with the requested priority; fall back to no priority if the project
        # doesn't support it (Jira returns 400 with "priority not available" error).
        try:
            data = jira_post("issue", {"fields": {**fields, "priority": {"name": priority}}})
        except Exception:
            data = jira_post("issue", {"fields": fields})
        key = data.get("key", "")
        url = f"{JIRA_URL}/browse/{key}"
        result = f"Issue created ✅\n  Key:  {key}\n  URL:  {url}\n  Type: {issue_type}\n  Priority: {priority}\n"
        return [TextContent(type="text", text=result)]

    # ── update_issue ──────────────────────────────────────────────────────────
    elif name == "update_issue":
        issue_key = arguments["issue_key"]
        fields: dict = {}

        if "summary" in arguments:
            fields["summary"] = arguments["summary"]
        if "description" in arguments:
            fields["description"] = arguments["description"]
        if "priority" in arguments:
            fields["priority"] = {"name": arguments["priority"]}
        if "labels" in arguments:
            fields["labels"] = arguments["labels"]

        if not fields:
            return [TextContent(type="text", text="No fields to update — provide at least one of: summary, description, priority, labels")]

        jira_put(f"issue/{issue_key}", {"fields": fields})
        updated = ", ".join(fields.keys())
        return [TextContent(type="text", text=f"Issue {issue_key} updated ✅\n  Fields changed: {updated}")]

    # ── link_issues ───────────────────────────────────────────────────────────
    elif name == "link_issues":
        inward = arguments["inward_issue_key"]
        outward = arguments["outward_issue_key"]
        link_type = arguments.get("link_type", "Epos-Story Link")

        jira_post("issueLink", {
            "type": {"name": link_type},
            "inwardIssue": {"key": inward},
            "outwardIssue": {"key": outward},
        })
        return [TextContent(
            type="text",
            text=f"Link created ✅\n  {inward} --[{link_type}]--> {outward}",
        )]

    # ── find_best_epic ────────────────────────────────────────────────────────
    elif name == "find_best_epic":
        project = arguments.get("project_key", JIRA_PROJECT_KEY)
        feature_description = arguments["feature_description"]

        # Fetch ALL open epics (paginate if needed)
        all_epics: list[dict] = []
        start = 0
        while True:
            data = jira_get("search", {
                "jql": (
                    f'project = "{project}" AND issuetype = Epic '
                    f'AND status != Done ORDER BY updated DESC'
                ),
                "maxResults": 50,
                "startAt": start,
                "fields": "summary,status,key",
            })
            batch = data.get("issues", [])
            all_epics.extend(batch)
            if len(all_epics) >= data.get("total", 0) or not batch:
                break
            start += len(batch)

        if not all_epics:
            return [TextContent(type="text", text=f"No open epics found in project {project}.")]

        # Score every epic
        scored: list[dict] = []
        for epic in all_epics:
            key = epic["key"]
            name = epic["fields"]["summary"]
            score, reasons = _score_epic(name, key, feature_description)
            scored.append({"key": key, "name": name, "score": score, "reasons": reasons})

        scored.sort(key=lambda x: x["score"], reverse=True)
        best = scored[0]

        result  = f"Best Epic Match for:\n\"{feature_description[:200]}\"\n\n"
        result += f"{'='*60}\n"
        result += f"BEST MATCH: {best['key']} — {best['name']}\n"
        result += f"Score: {best['score']}\n"
        result += "Reasons:\n"
        for r in best["reasons"]:
            result += f"  - {r}\n"

        result += f"\n{'='*60}\n"
        result += f"All epics ranked ({len(scored)} total):\n\n"
        for rank, e in enumerate(scored, 1):
            marker = " <-- BEST" if rank == 1 else ""
            result += f"  {rank:2}. {e['key']:20} | score={e['score']:3} | {e['name']}{marker}\n"

        return [TextContent(type="text", text=result)]

    # ── add_issue_comment ─────────────────────────────────────────────────────
    elif name == "add_issue_comment":
        issue_key = arguments["issue_key"]
        comment = arguments["comment"]
        jira_post(f"issue/{issue_key}/comment", {"body": comment})
        return [TextContent(type="text", text=f"Comment posted on {issue_key} ✅")]

    # ── update_issue_status ───────────────────────────────────────────────────
    elif name == "update_issue_status":
        issue_key = arguments["issue_key"]
        status_name = arguments["status_name"].lower()

        t_data = jira_get(f"issue/{issue_key}/transitions")
        transitions = t_data.get("transitions", [])
        match = next((t for t in transitions if t["name"].lower() == status_name), None)
        if not match:
            available = [t["name"] for t in transitions]
            return [TextContent(
                type="text",
                text=f"Status '{status_name}' not found. Available: {available}",
            )]

        jira_post(f"issue/{issue_key}/transitions", {"transition": {"id": match["id"]}})
        return [TextContent(
            type="text",
            text=f"Issue {issue_key} moved to '{arguments['status_name']}' ✅",
        )]

    # ── get_confluence_page ───────────────────────────────────────────────────
    elif name == "get_confluence_page":
        title = arguments["title"]
        space = arguments.get("space_key", CONFLUENCE_SPACE_KEY)
        data = confluence_get("content/search", {
            "cql": f'space = "{space}" AND title ~ "{title}" AND type = page',
            "limit": 1,
            "expand": "body.storage",
        })
        results = data.get("results", [])
        if not results:
            return [TextContent(type="text", text=f"No Confluence page found matching '{title}' in space {space}")]

        page = results[0]
        plain = _strip_html(page.get("body", {}).get("storage", {}).get("value", ""))
        result  = f"Confluence Page: {page['title']}\n"
        result += f"URL: {CONFLUENCE_URL}{page['_links'].get('webui', '')}\n\n"
        result += plain[:6000]
        if len(plain) > 6000:
            result += "\n\n[... content truncated ...]"
        return [TextContent(type="text", text=result)]

    # ── get_confluence_page_by_id ─────────────────────────────────────────────
    elif name == "get_confluence_page_by_id":
        page_id = arguments["page_id"]
        page = confluence_get(f"content/{page_id}", {"expand": "body.storage,version"})
        plain = _strip_html(page.get("body", {}).get("storage", {}).get("value", ""))
        result  = f"Confluence Page: {page.get('title', 'Unknown')}\n"
        result += f"Space: {page.get('space', {}).get('key', 'N/A')}\n"
        result += f"Version: {page.get('version', {}).get('number', 'N/A')}\n"
        result += f"URL: {CONFLUENCE_URL}{page.get('_links', {}).get('webui', '')}\n\n"
        result += plain[:6000]
        if len(plain) > 6000:
            result += "\n\n[... content truncated ...]"
        return [TextContent(type="text", text=result)]

    # ── list_documentation_pages ──────────────────────────────────────────────
    elif name == "list_documentation_pages":
        space = arguments.get("space_key", CONFLUENCE_DOCS_SPACE)
        search_term = arguments.get("search_term", "")
        cql = f'space = "{space}" AND type = page'
        if search_term:
            cql += f' AND title ~ "{search_term}"'
        cql += " ORDER BY title ASC"

        data = confluence_get("content/search", {"cql": cql, "limit": 50, "expand": "ancestors"})
        results = data.get("results", [])
        if not results:
            return [TextContent(type="text", text=f"No pages found in space {space}")]

        result = f"Documentation Pages in '{space}' ({len(results)} found):\n\n"
        for page in results:
            ancestors = page.get("ancestors", [])
            breadcrumb = " > ".join(a["title"] for a in ancestors[-2:]) if ancestors else ""
            prefix = f"  [{breadcrumb}] " if breadcrumb else "  "
            result += f"{prefix}{page['title']}\n"
            result += f"    ID: {page['id']} | URL: {CONFLUENCE_URL}{page['_links'].get('webui', '')}\n\n"
        return [TextContent(type="text", text=result)]

    # ── create_confluence_page ────────────────────────────────────────────────
    elif name == "create_confluence_page":
        title = arguments["title"]
        content = arguments["content"]
        space = arguments.get("space_key", CONFLUENCE_SPACE_KEY)
        parent_id = arguments.get("parent_id")

        if not content.strip().startswith("<"):
            storage_content = "".join(
                f"<p>{line}</p>" if line.strip() else "<p />"
                for line in content.split("\n")
            )
        else:
            storage_content = content

        payload: dict = {
            "type": "page",
            "title": title,
            "space": {"key": space},
            "body": {"storage": {"value": storage_content, "representation": "storage"}},
        }
        if parent_id:
            payload["ancestors"] = [{"id": parent_id}]

        page = confluence_post("content", payload)
        page_id = page.get("id", "")
        web_url = CONFLUENCE_URL + page.get("_links", {}).get("webui", "")
        result  = f"Confluence page created ✅\n"
        result += f"  Title:   {title}\n"
        result += f"  Space:   {space}\n"
        result += f"  Page ID: {page_id}\n"
        result += f"  URL:     {web_url}\n"
        return [TextContent(type="text", text=result)]

    # ── update_confluence_page ────────────────────────────────────────────────
    elif name == "update_confluence_page":
        page_id = arguments["page_id"]
        new_content = arguments["content"]
        append = arguments.get("append", False)

        # Fetch current page to get version + existing title + body
        current = confluence_get(f"content/{page_id}", {"expand": "body.storage,version"})
        current_version = current.get("version", {}).get("number", 1)
        current_title = current.get("title", "")
        title = arguments.get("title") or current_title

        if append:
            existing_body = current.get("body", {}).get("storage", {}).get("value", "")
            if not new_content.strip().startswith("<"):
                extra = "".join(
                    f"<p>{line}</p>" if line.strip() else "<p />"
                    for line in new_content.split("\n")
                )
            else:
                extra = new_content
            storage_content = existing_body + "\n" + extra
        else:
            if not new_content.strip().startswith("<"):
                storage_content = "".join(
                    f"<p>{line}</p>" if line.strip() else "<p />"
                    for line in new_content.split("\n")
                )
            else:
                storage_content = new_content

        payload = {
            "version": {"number": current_version + 1},
            "title": title,
            "type": "page",
            "body": {"storage": {"value": storage_content, "representation": "storage"}},
        }
        confluence_put(f"content/{page_id}", payload)
        web_url = CONFLUENCE_URL + current.get("_links", {}).get("webui", "")
        result  = f"Confluence page updated ✅\n"
        result += f"  Title:   {title}\n"
        result += f"  Page ID: {page_id}\n"
        result += f"  Version: {current_version} -> {current_version + 1}\n"
        result += f"  Mode:    {'append' if append else 'replace'}\n"
        result += f"  URL:     {web_url}\n"
        return [TextContent(type="text", text=result)]

    return [TextContent(type="text", text=f"Unknown tool: {name}")]


if __name__ == "__main__":
    import asyncio

    async def main():
        async with stdio_server() as (read_stream, write_stream):
            await app.run(read_stream, write_stream, app.create_initialization_options())

    asyncio.run(main())
