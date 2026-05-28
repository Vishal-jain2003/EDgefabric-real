import os
import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
from dotenv import load_dotenv
from pathlib import Path

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

JENKINS_URL  = os.getenv("JENKINS_URL", "").rstrip("/")
JENKINS_USER = os.getenv("JENKINS_USER")
JENKINS_TOKEN = os.getenv("JENKINS_TOKEN")

app = Server("jenkins-server")

# ── helpers ───────────────────────────────────────────────────────────────────

def _safe_json(response: httpx.Response) -> dict | list:
    """Parse JSON safely; return an error dict if the body is not valid JSON."""
    content_type = response.headers.get("content-type", "")
    if response.status_code == 404:
        return {"error": f"404 Not Found: {response.url}"}
    if response.status_code == 403:
        return {"error": f"403 Forbidden — check CSRF crumb or token permissions: {response.url}"}
    if response.status_code == 401:
        return {"error": "401 Unauthorized — JENKINS_USER / JENKINS_TOKEN are invalid"}
    if "application/json" not in content_type and "javascript" not in content_type:
        # Jenkins returned HTML (login page, error page, folder page)
        snippet = response.text[:300].strip().replace("\n", " ")
        return {"error": f"Non-JSON response (HTTP {response.status_code}): {snippet}"}
    try:
        return response.json()
    except Exception as e:
        return {"error": f"JSON parse error: {e} — body: {response.text[:200]}"}


def _get_crumb() -> dict:
    """Fetch a Jenkins CSRF crumb. Returns empty dict if crumb issuer is disabled."""
    try:
        r = httpx.get(
            f"{JENKINS_URL}/crumbIssuer/api/json",
            auth=(JENKINS_USER, JENKINS_TOKEN),
            timeout=10,
            verify=False,
        )
        if r.status_code == 200:
            data = r.json()
            return {data["crumbRequestField"]: data["crumb"]}
    except Exception:
        pass
    return {}


def _job_url_path(job_name: str) -> str:
    """
    Convert a job name like 'EPM-ICMP' or 'EPM-ICMP/develop' into
    the Jenkins URL segment 'job/EPM-ICMP' or 'job/EPM-ICMP/job/develop'.
    """
    parts = job_name.strip("/").split("/")
    return "/".join(f"job/{p}" for p in parts)


def jenkins_get(endpoint: str) -> dict | list:
    response = httpx.get(
        f"{JENKINS_URL}/{endpoint}",
        auth=(JENKINS_USER, JENKINS_TOKEN),
        timeout=30,
        verify=False,
    )
    return _safe_json(response)


def jenkins_post(endpoint: str, data: dict | None = None) -> httpx.Response:
    crumb = _get_crumb()
    response = httpx.post(
        f"{JENKINS_URL}/{endpoint}",
        auth=(JENKINS_USER, JENKINS_TOKEN),
        headers=crumb,
        data=data or {},
        timeout=30,
        verify=False,
    )
    return response


def _collect_jobs(jobs: list, prefix: str = "") -> list[dict]:
    """Recursively expand folder jobs so we surface real pipeline jobs."""
    result = []
    for job in jobs:
        name = job.get("name", "")
        full_name = f"{prefix}/{name}" if prefix else name
        job_class = job.get("_class", "")
        if "Folder" in job_class or "WorkflowMultiBranchProject" in job_class:
            # Recurse into folder
            path = _job_url_path(full_name)
            sub = jenkins_get(f"{path}/api/json?tree=jobs[name,color,_class,url]")
            if isinstance(sub, dict) and "jobs" in sub:
                result.extend(_collect_jobs(sub["jobs"], full_name))
            else:
                result.append({"name": full_name, "color": job.get("color", ""), "url": job.get("url", "")})
        else:
            result.append({"name": full_name, "color": job.get("color", ""), "url": job.get("url", "")})
    return result


# ── tool definitions ──────────────────────────────────────────────────────────

@app.list_tools()
async def list_tools():
    return [
        Tool(
            name="get_build_status",
            description="Get latest Jenkins build status for a job",
            inputSchema={
                "type": "object",
                "properties": {
                    "job_name": {
                        "type": "string",
                        "description": "Jenkins job name — use folder/job format e.g. 'EPM-ICMP' or 'EPM-ICMP/develop'"
                    }
                },
                "required": ["job_name"]
            }
        ),
        Tool(
            name="get_build_log",
            description="Get console log of a Jenkins build to see why it failed",
            inputSchema={
                "type": "object",
                "properties": {
                    "job_name": {"type": "string"},
                    "build_number": {
                        "type": "string",
                        "description": "Build number or 'lastBuild'"
                    }
                },
                "required": ["job_name"]
            }
        ),
        Tool(
            name="trigger_build",
            description="Trigger a Jenkins job build",
            inputSchema={
                "type": "object",
                "properties": {
                    "job_name": {"type": "string"}
                },
                "required": ["job_name"]
            }
        ),
        Tool(
            name="get_all_jobs",
            description="List all Jenkins jobs and their current status (recurses into folders)",
            inputSchema={
                "type": "object",
                "properties": {}
            }
        )
    ]


# ── tool handlers ─────────────────────────────────────────────────────────────

@app.call_tool()
async def call_tool(name: str, arguments: dict):

    if name == "get_build_status":
        job  = arguments["job_name"]
        path = _job_url_path(job)
        data = jenkins_get(f"{path}/lastBuild/api/json")

        if "error" in data:
            return [TextContent(type="text", text=f"Error fetching build status: {data['error']}")]

        status   = data.get("result") or ("IN PROGRESS" if data.get("building") else "UNKNOWN")
        duration = data.get("duration", 0) // 1000
        result   = f"Jenkins Job: {job}\n"
        result  += f"  Build #:   {data.get('number', 'N/A')}\n"
        result  += f"  Status:    {status}\n"
        result  += f"  Duration:  {duration}s\n"
        result  += f"  Timestamp: {data.get('timestamp', 'N/A')}\n"
        result  += f"  URL:       {data.get('url', 'N/A')}\n"
        return [TextContent(type="text", text=result)]

    elif name == "get_build_log":
        job   = arguments["job_name"]
        build = arguments.get("build_number", "lastBuild")
        path  = _job_url_path(job)

        response = httpx.get(
            f"{JENKINS_URL}/{path}/{build}/consoleText",
            auth=(JENKINS_USER, JENKINS_TOKEN),
            timeout=60,
            verify=False,
        )
        if response.status_code == 404:
            return [TextContent(type="text", text=f"No console log found for {job} #{build} — job or build does not exist")]
        if response.status_code != 200:
            return [TextContent(type="text", text=f"Failed to fetch log: HTTP {response.status_code}")]

        log = response.text[-4000:]
        return [TextContent(type="text", text=f"Build Log for {job} #{build} (last 4000 chars):\n\n{log}")]

    elif name == "trigger_build":
        job      = arguments["job_name"]
        path     = _job_url_path(job)
        response = jenkins_post(f"{path}/build")

        if response.status_code in [200, 201]:
            return [TextContent(type="text", text=f"✅ Build triggered for '{job}'")]
        elif response.status_code == 403:
            return [TextContent(type="text", text=f"❌ 403 Forbidden — CSRF crumb may have failed or token lacks build permissions")]
        elif response.status_code == 404:
            return [TextContent(type="text", text=f"❌ 404 — Job '{job}' not found. Check the job path.")]
        else:
            return [TextContent(type="text", text=f"❌ Failed to trigger build: HTTP {response.status_code}\n{response.text[:300]}")]

    elif name == "get_all_jobs":
        data = jenkins_get("api/json?tree=jobs[name,color,_class,url,jobs[name,color,_class,url]]")

        if "error" in data:
            return [TextContent(type="text", text=f"Error: {data['error']}")]

        jobs = _collect_jobs(data.get("jobs", []))

        if not jobs:
            return [TextContent(type="text", text="No jobs found — Jenkins may be empty or the token lacks read permissions")]

        result = f"All Jenkins Jobs ({len(jobs)}):\n\n"
        for job in jobs:
            color  = job.get("color", "")
            if color == "blue":
                status = "✅ PASSING"
            elif color == "red":
                status = "❌ FAILING"
            elif color in ("blue_anime", "red_anime", "notbuilt_anime"):
                status = "🔄 RUNNING"
            elif color == "notbuilt":
                status = "⚪  NOT BUILT"
            else:
                status = f"⚪  {color}"
            result += f"  {status} — {job['name']}\n"
        return [TextContent(type="text", text=result)]


if __name__ == "__main__":
    import asyncio

    async def main():
        async with stdio_server() as (read_stream, write_stream):
            await app.run(
                read_stream,
                write_stream,
                app.create_initialization_options()
            )

    asyncio.run(main())
