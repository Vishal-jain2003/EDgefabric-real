import os
import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
from dotenv import load_dotenv

from pathlib import Path
load_dotenv(dotenv_path=Path(__file__).parent / ".env")

SONAR_URL = os.getenv("SONAR_URL")
SONAR_TOKEN = os.getenv("SONAR_TOKEN")
PROJECT_KEY = os.getenv("SONAR_PROJECT_KEY")

app = Server("sonarqube-server")

def _safe_json(response: httpx.Response) -> dict | list:
    if response.status_code == 401:
        return {"error": "401 Unauthorized — check SONAR_TOKEN"}
    if response.status_code == 403:
        return {"error": f"403 Forbidden: {response.url}"}
    if response.status_code == 404:
        return {"error": f"404 Not Found: {response.url}"}
    try:
        return response.json()
    except Exception:
        return {"error": f"Non-JSON response (HTTP {response.status_code}): {response.text[:200]}"}

def sonar_get(endpoint: str, params: dict = {}):
    response = httpx.get(
        f"{SONAR_URL}/api/{endpoint}",
        params=params,
        auth=(SONAR_TOKEN, ""),
        timeout=30
    )
    return _safe_json(response)

@app.list_tools()
async def list_tools():
    return [
        Tool(
            name="get_quality_gate_status",
            description="Get SonarQube quality gate status — PASSED or FAILED",
            inputSchema={
                "type": "object",
                "properties": {
                    "project_key": {
                        "type": "string",
                        "description": "SonarQube project key. Defaults to edgefabric."
                    }
                }
            }
        ),
        Tool(
            name="get_issues",
            description="Get all SonarQube issues — bugs, code smells, vulnerabilities",
            inputSchema={
                "type": "object",
                "properties": {
                    "project_key": {"type": "string"},
                    "severity": {
                        "type": "string",
                        "description": "BLOCKER, CRITICAL, MAJOR, MINOR, INFO",
                        "default": "MAJOR"
                    },
                    "types": {
                        "type": "string",
                        "description": "BUG, VULNERABILITY, CODE_SMELL",
                        "default": "BUG,VULNERABILITY,CODE_SMELL"
                    }
                }
            }
        ),
        Tool(
            name="get_metrics",
            description="Get code metrics — coverage, duplications, complexity",
            inputSchema={
                "type": "object",
                "properties": {
                    "project_key": {"type": "string"}
                }
            }
        ),
        Tool(
            name="get_hotspots",
            description="Get security hotspots that need review",
            inputSchema={
                "type": "object",
                "properties": {
                    "project_key": {"type": "string"}
                }
            }
        )
    ]

@app.call_tool()
async def call_tool(name: str, arguments: dict):
    project_key = arguments.get("project_key", PROJECT_KEY)

    if name == "get_quality_gate_status":
        data = sonar_get("qualitygates/project_status",
                         {"projectKey": project_key})
        if "error" in data:
            return [TextContent(type="text", text=f"SonarQube error: {data['error']}")]
        status = data.get("projectStatus", {})
        result = f"Quality Gate: {status.get('status', 'UNKNOWN')}\n\n"

        conditions = status.get("conditions", [])
        failed = [c for c in conditions if c.get("status") == "ERROR"]

        if failed:
            result += "Failed Conditions:\n"
            for c in failed:
                result += f"  - {c['metricKey']}: actual={c.get('actualValue')} threshold={c.get('errorThreshold')}\n"
        else:
            result += "All conditions passing ✅"

        return [TextContent(type="text", text=result)]

    elif name == "get_issues":
        data = sonar_get("issues/search", {
            "componentKeys": project_key,
            "severities": arguments.get("severity", "MAJOR"),
            "types": arguments.get("types", "BUG,VULNERABILITY,CODE_SMELL"),
            "resolved": "false",
            "ps": 50
        })
        if "error" in data:
            return [TextContent(type="text", text=f"SonarQube error: {data['error']}")]
        issues = data.get("issues", [])

        if not issues:
            return [TextContent(type="text", text="No issues found ✅")]

        result = f"Found {len(issues)} issues:\n\n"
        for issue in issues:
            result += f"[{issue.get('severity')}] {issue.get('type')}\n"
            result += f"  File: {issue.get('component', '').split(':')[-1]}\n"
            result += f"  Line: {issue.get('line', 'N/A')}\n"
            result += f"  Message: {issue.get('message')}\n"
            result += f"  Rule: {issue.get('rule')}\n\n"

        return [TextContent(type="text", text=result)]

    elif name == "get_metrics":
        data = sonar_get("measures/component", {
            "component": project_key,
            "metricKeys": "coverage,duplicated_lines_density,complexity,bugs,vulnerabilities,code_smells,reliability_rating,security_rating"
        })
        if "error" in data:
            return [TextContent(type="text", text=f"SonarQube error: {data['error']}")]
        measures = data.get("component", {}).get("measures", [])

        result = "Code Metrics:\n\n"
        for m in measures:
            result += f"  {m['metric']}: {m.get('value', 'N/A')}\n"

        return [TextContent(type="text", text=result)]

    elif name == "get_hotspots":
        data = sonar_get("hotspots/search",
                         {"projectKey": project_key})
        if "error" in data:
            return [TextContent(type="text", text=f"SonarQube error: {data['error']}")]
        hotspots = data.get("hotspots", [])

        if not hotspots:
            return [TextContent(type="text", text="No security hotspots found ✅")]

        result = f"Found {len(hotspots)} security hotspots:\n\n"
        for h in hotspots:
            result += f"  [{h.get('vulnerabilityProbability')}] {h.get('message')}\n"
            result += f"  File: {h.get('component', '').split(':')[-1]} Line: {h.get('line')}\n\n"

        return [TextContent(type="text", text=result)]

if __name__ == "__main__":
    from mcp.server.stdio import stdio_server
    import asyncio

    async def main():
        async with stdio_server() as (read_stream, write_stream):
            await app.run(
                read_stream,
                write_stream,
                app.create_initialization_options()
            )

    asyncio.run(main())
