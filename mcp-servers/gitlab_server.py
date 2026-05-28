import os
import base64
import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
from dotenv import load_dotenv
from pathlib import Path

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

GITLAB_URL = os.getenv("GITLAB_URL", "").rstrip("/")
GITLAB_TOKEN = os.getenv("GITLAB_TOKEN")
PROJECT_ID = os.getenv("GITLAB_PROJECT_ID")

app = Server("gitlab-server")

# ── HTTP helpers ──────────────────────────────────────────────────────────────

def _safe_json(response: httpx.Response) -> dict | list:
    if response.status_code == 401:
        return {"error": "401 Unauthorized — check GITLAB_TOKEN"}
    if response.status_code == 403:
        return {"error": f"403 Forbidden: {response.url}"}
    if response.status_code == 404:
        return {"error": f"404 Not Found: {response.url}"}
    try:
        return response.json()
    except Exception:
        return {"error": f"Non-JSON response (HTTP {response.status_code}): {response.text[:200]}"}

def _headers():
    return {"PRIVATE-TOKEN": GITLAB_TOKEN}

def gitlab_get(endpoint: str, params: dict = {}):
    r = httpx.get(f"{GITLAB_URL}/api/v4/{endpoint}", params=params,
                  headers=_headers(), timeout=30)
    return _safe_json(r)

def gitlab_post(endpoint: str, data: dict = {}):
    r = httpx.post(f"{GITLAB_URL}/api/v4/{endpoint}", json=data,
                   headers=_headers(), timeout=30)
    return _safe_json(r)

def gitlab_put(endpoint: str, data: dict = {}):
    r = httpx.put(f"{GITLAB_URL}/api/v4/{endpoint}", json=data,
                  headers=_headers(), timeout=30)
    return _safe_json(r)

def gitlab_delete(endpoint: str):
    r = httpx.delete(f"{GITLAB_URL}/api/v4/{endpoint}", headers=_headers(), timeout=30)
    return r.status_code


# ── Tool registry ─────────────────────────────────────────────────────────────

@app.list_tools()
async def list_tools():
    return [
        Tool(
            name="get_pipeline_status",
            description="Get latest GitLab pipeline status for a branch",
            inputSchema={
                "type": "object",
                "properties": {
                    "branch": {
                        "type": "string",
                        "description": "Branch name e.g. develop, feature/agent-creation-poc",
                    }
                },
                "required": ["branch"],
            },
        ),
        Tool(
            name="list_pipelines",
            description="List recent pipelines for a branch with their status and URLs",
            inputSchema={
                "type": "object",
                "properties": {
                    "branch": {"type": "string", "description": "Branch name"},
                    "limit": {
                        "type": "integer",
                        "description": "Max pipelines to return. Default 10.",
                        "default": 10,
                    },
                },
                "required": ["branch"],
            },
        ),
        Tool(
            name="get_pipeline_jobs",
            description="Get all jobs in a pipeline with their status and log URLs",
            inputSchema={
                "type": "object",
                "properties": {"pipeline_id": {"type": "string"}},
                "required": ["pipeline_id"],
            },
        ),
        Tool(
            name="trigger_pipeline",
            description="Trigger a GitLab pipeline on a branch",
            inputSchema={
                "type": "object",
                "properties": {"branch": {"type": "string"}},
                "required": ["branch"],
            },
        ),
        Tool(
            name="create_merge_request",
            description="Create a GitLab merge request",
            inputSchema={
                "type": "object",
                "properties": {
                    "source_branch": {"type": "string"},
                    "target_branch": {"type": "string", "default": "develop"},
                    "title": {"type": "string"},
                    "description": {"type": "string"},
                },
                "required": ["source_branch", "title"],
            },
        ),
        Tool(
            name="get_merge_requests",
            description="Get merge requests filtered by state",
            inputSchema={
                "type": "object",
                "properties": {
                    "state": {"type": "string", "default": "opened"},
                },
            },
        ),
        Tool(
            name="get_mr_status",
            description="Get detailed status of a merge request including pipeline and approval state",
            inputSchema={
                "type": "object",
                "properties": {
                    "mr_iid": {
                        "type": "integer",
                        "description": "Merge request internal ID (the number shown as !42)",
                    }
                },
                "required": ["mr_iid"],
            },
        ),
        Tool(
            name="merge_merge_request",
            description=(
                "Merge an approved merge request. "
                "Optionally delete the source branch after merge."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "mr_iid": {
                        "type": "integer",
                        "description": "Merge request internal ID",
                    },
                    "merge_commit_message": {
                        "type": "string",
                        "description": "Optional custom merge commit message",
                    },
                    "should_remove_source_branch": {
                        "type": "boolean",
                        "description": "Delete source branch after merge. Default: true",
                        "default": True,
                    },
                    "squash": {
                        "type": "boolean",
                        "description": "Squash commits before merge. Default: false",
                        "default": False,
                    },
                },
                "required": ["mr_iid"],
            },
        ),
        Tool(
            name="get_mr_diff",
            description="Get the file diff / changes of a merge request",
            inputSchema={
                "type": "object",
                "properties": {"mr_iid": {"type": "integer"}},
                "required": ["mr_iid"],
            },
        ),
        Tool(
            name="add_mr_comment",
            description="Post a comment or review note on a merge request",
            inputSchema={
                "type": "object",
                "properties": {
                    "mr_iid": {"type": "integer"},
                    "note": {"type": "string", "description": "Comment text (Markdown supported)"},
                },
                "required": ["mr_iid", "note"],
            },
        ),
        Tool(
            name="create_branch",
            description="Create a new Git branch in the GitLab project",
            inputSchema={
                "type": "object",
                "properties": {
                    "branch_name": {
                        "type": "string",
                        "description": "New branch name e.g. feature/EF-42-cache-ttl",
                    },
                    "ref": {
                        "type": "string",
                        "description": "Source branch/commit to branch from. Default: develop",
                        "default": "develop",
                    },
                },
                "required": ["branch_name"],
            },
        ),
        Tool(
            name="list_branches",
            description=(
                "List branches in the GitLab project. "
                "Optionally filter by name pattern (supports * wildcard)."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "search": {
                        "type": "string",
                        "description": "Optional name filter e.g. 'feature/*' or 'EPMICMPHE-42'",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max branches to return. Default 50.",
                        "default": 50,
                    },
                },
            },
        ),
        Tool(
            name="delete_branch",
            description=(
                "Delete a Git branch from the GitLab project. "
                "Protected branches (main, develop) cannot be deleted."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "branch_name": {
                        "type": "string",
                        "description": "Branch name to delete",
                    }
                },
                "required": ["branch_name"],
            },
        ),
        Tool(
            name="commit_files",
            description="Commit one or more files to a GitLab branch in a single commit",
            inputSchema={
                "type": "object",
                "properties": {
                    "branch": {"type": "string", "description": "Target branch name"},
                    "commit_message": {"type": "string", "description": "Git commit message"},
                    "files": {
                        "type": "array",
                        "description": "List of files to create or update",
                        "items": {
                            "type": "object",
                            "properties": {
                                "path": {"type": "string", "description": "File path relative to repo root"},
                                "content": {"type": "string", "description": "Full file content"},
                                "action": {
                                    "type": "string",
                                    "description": "create | update | delete. Default: create",
                                },
                            },
                            "required": ["path", "content"],
                        },
                    },
                },
                "required": ["branch", "commit_message", "files"],
            },
        ),
        Tool(
            name="get_file_content",
            description="Read the content of a file from a specific branch in the GitLab repo",
            inputSchema={
                "type": "object",
                "properties": {
                    "file_path": {
                        "type": "string",
                        "description": "File path relative to repo root e.g. caching/src/main/java/.../Service.java",
                    },
                    "branch": {
                        "type": "string",
                        "description": "Branch to read from. Default: develop",
                        "default": "develop",
                    },
                },
                "required": ["file_path"],
            },
        ),
    ]


# ── Tool handlers ─────────────────────────────────────────────────────────────

@app.call_tool()
async def call_tool(name: str, arguments: dict):

    # ── get_pipeline_status ───────────────────────────────────────────────────
    if name == "get_pipeline_status":
        branch = arguments["branch"]
        pipelines = gitlab_get(
            f"projects/{PROJECT_ID}/pipelines",
            {"ref": branch, "per_page": 1, "order_by": "id", "sort": "desc"},
        )
        if not pipelines or isinstance(pipelines, dict):
            return [TextContent(type="text", text=f"No pipelines found for branch: {branch}")]
        p = pipelines[0]
        result  = f"Latest Pipeline on '{branch}':\n"
        result += f"  ID:      {p['id']}\n"
        result += f"  Status:  {p['status'].upper()}\n"
        result += f"  Created: {p['created_at']}\n"
        result += f"  URL:     {p['web_url']}\n"
        return [TextContent(type="text", text=result)]

    # ── list_pipelines ────────────────────────────────────────────────────────
    elif name == "list_pipelines":
        branch = arguments["branch"]
        limit = arguments.get("limit", 10)
        pipelines = gitlab_get(
            f"projects/{PROJECT_ID}/pipelines",
            {"ref": branch, "per_page": limit, "order_by": "id", "sort": "desc"},
        )
        if not pipelines or isinstance(pipelines, dict):
            return [TextContent(type="text", text=f"No pipelines found for branch: {branch}")]

        result = f"Pipelines on '{branch}' ({len(pipelines)} shown):\n\n"
        for p in pipelines:
            status = p["status"].upper()
            icon = "✅" if status == "SUCCESS" else "❌" if status == "FAILED" else "🔄" if status == "RUNNING" else "⏳"
            result += f"  {icon} #{p['id']} [{status}] created={p['created_at']}\n"
            result += f"     URL: {p['web_url']}\n\n"
        return [TextContent(type="text", text=result)]

    # ── get_pipeline_jobs ─────────────────────────────────────────────────────
    elif name == "get_pipeline_jobs":
        pipeline_id = arguments["pipeline_id"]
        jobs = gitlab_get(f"projects/{PROJECT_ID}/pipelines/{pipeline_id}/jobs", {"per_page": 100})

        if isinstance(jobs, dict) and "error" in jobs:
            return [TextContent(type="text", text=f"Error: {jobs['error']}")]
        if not jobs:
            return [TextContent(type="text", text=f"No jobs found for pipeline #{pipeline_id}")]

        result = f"Jobs in Pipeline #{pipeline_id}:\n\n"
        for job in jobs:
            status = job.get("status", "unknown").upper()
            icon = "✅" if status == "SUCCESS" else "❌" if status == "FAILED" else "🔄" if status == "RUNNING" else "⏭️" if status == "SKIPPED" else "⏳"
            duration = f"{int(job.get('duration') or 0)}s"
            result += f"  {icon} [{status}] {job['name']} (stage: {job['stage']}, {duration})\n"
            if status == "FAILED":
                result += f"     Log: {job.get('web_url', 'N/A')}\n"
        return [TextContent(type="text", text=result)]

    # ── trigger_pipeline ──────────────────────────────────────────────────────
    elif name == "trigger_pipeline":
        branch = arguments["branch"]
        pipeline = gitlab_post(f"projects/{PROJECT_ID}/pipeline", {"ref": branch})
        result  = f"Pipeline Triggered ✅\n"
        result += f"  Branch:      {branch}\n"
        result += f"  Pipeline ID: {pipeline.get('id')}\n"
        result += f"  Status:      {pipeline.get('status')}\n"
        result += f"  URL:         {pipeline.get('web_url')}\n"
        return [TextContent(type="text", text=result)]

    # ── create_merge_request ──────────────────────────────────────────────────
    elif name == "create_merge_request":
        data = {
            "source_branch": arguments["source_branch"],
            "target_branch": arguments.get("target_branch", "develop"),
            "title": arguments["title"],
            "description": arguments.get("description", "Auto-created by Claude agent"),
            "remove_source_branch": True,
        }
        mr = gitlab_post(f"projects/{PROJECT_ID}/merge_requests", data)
        result  = f"Merge Request Created ✅\n"
        result += f"  Title:  {mr.get('title')}\n"
        result += f"  IID:    !{mr.get('iid')}\n"
        result += f"  URL:    {mr.get('web_url')}\n"
        result += f"  Status: {mr.get('state')}\n"
        return [TextContent(type="text", text=result)]

    # ── get_merge_requests ────────────────────────────────────────────────────
    elif name == "get_merge_requests":
        mrs = gitlab_get(
            f"projects/{PROJECT_ID}/merge_requests",
            {"state": arguments.get("state", "opened")},
        )
        if not mrs or isinstance(mrs, dict):
            return [TextContent(type="text", text="No merge requests found")]

        result = f"Merge Requests ({len(mrs)}):\n\n"
        for mr in mrs:
            result += f"  !{mr['iid']} [{mr['state']}] {mr['title']}\n"
            result += f"    {mr['source_branch']} -> {mr['target_branch']}\n"
            result += f"    URL: {mr['web_url']}\n\n"
        return [TextContent(type="text", text=result)]

    # ── get_mr_status ─────────────────────────────────────────────────────────
    elif name == "get_mr_status":
        mr_iid = arguments["mr_iid"]
        mr = gitlab_get(f"projects/{PROJECT_ID}/merge_requests/{mr_iid}")
        pipeline = mr.get("head_pipeline") or {}

        result  = f"Merge Request !{mr_iid}\n"
        result += f"  Title:        {mr.get('title')}\n"
        result += f"  State:        {mr.get('state')}\n"
        result += f"  {mr.get('source_branch')} -> {mr.get('target_branch')}\n"
        result += f"  Merge Status: {mr.get('merge_status')}\n"
        result += f"  Approvals:    {mr.get('approvals_required', 0)} required / {mr.get('approvals_left', '?')} remaining\n"
        if pipeline:
            result += f"  Pipeline:     #{pipeline.get('id')} — {pipeline.get('status', 'N/A').upper()}\n"
        result += f"  URL:          {mr.get('web_url')}\n"
        return [TextContent(type="text", text=result)]

    # ── merge_merge_request ───────────────────────────────────────────────────
    elif name == "merge_merge_request":
        mr_iid = arguments["mr_iid"]
        data: dict = {
            "should_remove_source_branch": arguments.get("should_remove_source_branch", True),
            "squash": arguments.get("squash", False),
        }
        if "merge_commit_message" in arguments:
            data["merge_commit_message"] = arguments["merge_commit_message"]

        result_data = gitlab_put(f"projects/{PROJECT_ID}/merge_requests/{mr_iid}/merge", data)

        if isinstance(result_data, dict) and "error" in result_data:
            return [TextContent(type="text", text=f"Merge failed: {result_data['error']}")]

        state = result_data.get("state", "unknown")
        if state == "merged":
            result  = f"Merge Request !{mr_iid} merged ✅\n"
            result += f"  Merge commit: {(result_data.get('merge_commit_sha') or 'N/A')[:8]}\n"
            result += f"  URL: {result_data.get('web_url')}\n"
        else:
            result = f"Merge returned state: {state}\n{result_data}\n"
        return [TextContent(type="text", text=result)]

    # ── get_mr_diff ───────────────────────────────────────────────────────────
    elif name == "get_mr_diff":
        mr_iid = arguments["mr_iid"]
        diffs = gitlab_get(f"projects/{PROJECT_ID}/merge_requests/{mr_iid}/diffs")

        if not diffs or isinstance(diffs, dict):
            return [TextContent(type="text", text=f"No diff found for MR !{mr_iid}")]

        result = f"Diff for MR !{mr_iid} ({len(diffs)} file(s) changed):\n\n"
        for d in diffs[:10]:
            result += f"--- {d.get('old_path')}\n+++ {d.get('new_path')}\n"
            result += d.get("diff", "")[:1500]
            result += "\n\n"
        if len(diffs) > 10:
            result += f"[... {len(diffs) - 10} more files not shown ...]"
        return [TextContent(type="text", text=result)]

    # ── add_mr_comment ────────────────────────────────────────────────────────
    elif name == "add_mr_comment":
        mr_iid = arguments["mr_iid"]
        note = arguments["note"]
        result_data = gitlab_post(
            f"projects/{PROJECT_ID}/merge_requests/{mr_iid}/notes",
            {"body": note},
        )
        if "id" in result_data:
            return [TextContent(type="text", text=f"Comment posted on MR !{mr_iid} ✅")]
        return [TextContent(type="text", text=f"Failed to post comment: {result_data}")]

    # ── create_branch ─────────────────────────────────────────────────────────
    elif name == "create_branch":
        branch_name = arguments["branch_name"]
        ref = arguments.get("ref", "develop")
        branch = gitlab_post(
            f"projects/{PROJECT_ID}/repository/branches",
            {"branch": branch_name, "ref": ref},
        )
        if "name" in branch:
            result = (
                f"Branch created ✅\n"
                f"  Name:   {branch['name']}\n"
                f"  From:   {ref}\n"
                f"  Commit: {branch['commit']['id'][:8]}\n"
            )
        else:
            result = f"Branch creation failed: {branch}\n"
        return [TextContent(type="text", text=result)]

    # ── list_branches ─────────────────────────────────────────────────────────
    elif name == "list_branches":
        params: dict = {"per_page": arguments.get("limit", 50)}
        search = arguments.get("search", "")
        if search:
            params["search"] = search

        branches = gitlab_get(f"projects/{PROJECT_ID}/repository/branches", params)

        if not branches or isinstance(branches, dict):
            return [TextContent(type="text", text="No branches found")]

        result = f"Branches ({len(branches)} found):\n\n"
        for b in branches:
            protected = " [protected]" if b.get("protected") else ""
            commit_date = (b.get("commit") or {}).get("committed_date", "")[:10]
            result += f"  {b['name']}{protected}\n"
            result += f"    Last commit: {commit_date} — {(b.get('commit') or {}).get('title', '')[:60]}\n\n"
        return [TextContent(type="text", text=result)]

    # ── delete_branch ─────────────────────────────────────────────────────────
    elif name == "delete_branch":
        branch_name = arguments["branch_name"]

        # Guard against deleting protected branches
        if branch_name in ("main", "master", "develop"):
            return [TextContent(
                type="text",
                text=f"Refused to delete protected branch '{branch_name}'. This is a safety guard.",
            )]

        encoded = branch_name.replace("/", "%2F")
        status_code = gitlab_delete(f"projects/{PROJECT_ID}/repository/branches/{encoded}")

        if status_code == 204:
            return [TextContent(type="text", text=f"Branch '{branch_name}' deleted ✅")]
        return [TextContent(type="text", text=f"Delete failed: HTTP {status_code}")]

    # ── commit_files ──────────────────────────────────────────────────────────
    elif name == "commit_files":
        branch = arguments["branch"]
        message = arguments["commit_message"]
        files = arguments["files"]

        actions = [
            {"action": f.get("action", "create"), "file_path": f["path"], "content": f["content"]}
            for f in files
        ]
        data = {"branch": branch, "commit_message": message, "actions": actions}
        commit = gitlab_post(f"projects/{PROJECT_ID}/repository/commits", data)

        if "id" in commit:
            result = (
                f"Commit pushed ✅\n"
                f"  SHA:           {commit['id'][:8]}\n"
                f"  Branch:        {branch}\n"
                f"  Message:       {message}\n"
                f"  Files changed: {len(actions)}\n"
            )
        else:
            result = f"Commit failed: {commit}\n"
        return [TextContent(type="text", text=result)]

    # ── get_file_content ──────────────────────────────────────────────────────
    elif name == "get_file_content":
        file_path = arguments["file_path"].replace("/", "%2F")
        branch = arguments.get("branch", "develop")
        data = gitlab_get(
            f"projects/{PROJECT_ID}/repository/files/{file_path}",
            {"ref": branch},
        )
        if "content" not in data:
            return [TextContent(type="text", text=f"File not found: {arguments['file_path']} on {branch}")]

        content = base64.b64decode(data["content"]).decode("utf-8")
        result  = f"File: {data.get('file_path')} (branch: {branch})\n"
        result += f"Size: {data.get('size')} bytes\n\n"
        result += content
        return [TextContent(type="text", text=result)]

    return [TextContent(type="text", text=f"Unknown tool: {name}")]


if __name__ == "__main__":
    import asyncio

    async def main():
        async with stdio_server() as (read_stream, write_stream):
            await app.run(read_stream, write_stream, app.create_initialization_options())

    asyncio.run(main())
