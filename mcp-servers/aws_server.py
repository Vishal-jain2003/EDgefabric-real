import os
import boto3
import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
from dotenv import load_dotenv
from pathlib import Path
from datetime import datetime, timedelta, timezone

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

AWS_REGION           = os.getenv("AWS_DEFAULT_REGION", "ap-south-1")
AWS_ACCESS_KEY_ID    = os.getenv("AWS_ACCESS_KEY_ID")
AWS_SECRET_ACCESS_KEY = os.getenv("AWS_SECRET_ACCESS_KEY")

app = Server("aws-server")

def _ec2():
    return boto3.client("ec2", region_name=AWS_REGION,
                        aws_access_key_id=AWS_ACCESS_KEY_ID,
                        aws_secret_access_key=AWS_SECRET_ACCESS_KEY)

def _ssm():
    return boto3.client("ssm", region_name=AWS_REGION,
                        aws_access_key_id=AWS_ACCESS_KEY_ID,
                        aws_secret_access_key=AWS_SECRET_ACCESS_KEY)

def _sd():
    return boto3.client("servicediscovery", region_name=AWS_REGION,
                        aws_access_key_id=AWS_ACCESS_KEY_ID,
                        aws_secret_access_key=AWS_SECRET_ACCESS_KEY)

def _logs():
    return boto3.client("logs", region_name=AWS_REGION,
                        aws_access_key_id=AWS_ACCESS_KEY_ID,
                        aws_secret_access_key=AWS_SECRET_ACCESS_KEY)

@app.list_tools()
async def list_tools():
    return [
        Tool(
            name="get_ec2_instances",
            description="List EC2 instances filtered by Role tag (e.g. hermes-cache-node, hermes-loadbalancer)",
            inputSchema={
                "type": "object",
                "properties": {
                    "role_tag": {
                        "type": "string",
                        "description": "Value of the 'Role' EC2 tag e.g. hermes-cache-node"
                    }
                },
                "required": ["role_tag"]
            }
        ),
        Tool(
            name="get_ssm_command_status",
            description="Check the result of an AWS SSM RunShellScript command across all target instances",
            inputSchema={
                "type": "object",
                "properties": {
                    "command_id": {
                        "type": "string",
                        "description": "SSM Command ID returned by send-command"
                    }
                },
                "required": ["command_id"]
            }
        ),
        Tool(
            name="get_cloudmap_instances",
            description="List all instances registered in an AWS Cloud Map service (e.g. cache node discovery)",
            inputSchema={
                "type": "object",
                "properties": {
                    "service_id": {
                        "type": "string",
                        "description": "Cloud Map service ID e.g. srv-6lnd44knosnojplq"
                    }
                },
                "required": ["service_id"]
            }
        ),
        Tool(
            name="get_cloudwatch_logs",
            description="Fetch recent CloudWatch log events from a log group",
            inputSchema={
                "type": "object",
                "properties": {
                    "log_group": {
                        "type": "string",
                        "description": "CloudWatch log group name"
                    },
                    "log_stream": {
                        "type": "string",
                        "description": "Specific log stream name. Omit to use the latest stream."
                    },
                    "minutes": {
                        "type": "integer",
                        "description": "How many minutes back to fetch logs. Default 15.",
                        "default": 15
                    }
                },
                "required": ["log_group"]
            }
        ),
        Tool(
            name="check_service_health",
            description="HTTP health check against an EdgeFabric node or load balancer endpoint",
            inputSchema={
                "type": "object",
                "properties": {
                    "host": {
                        "type": "string",
                        "description": "IP or hostname of the node"
                    },
                    "port": {
                        "type": "integer",
                        "description": "Port number. Default 8080 for LB, 8082 for cache nodes.",
                        "default": 8080
                    },
                    "path": {
                        "type": "string",
                        "description": "Health endpoint path. Default /api/v1/system/health",
                        "default": "/api/v1/system/health"
                    }
                },
                "required": ["host"]
            }
        ),
        Tool(
            name="get_deployment_summary",
            description="Full deployment status: EC2 instances, CloudMap registration, and health for a given role",
            inputSchema={
                "type": "object",
                "properties": {
                    "role_tag": {
                        "type": "string",
                        "description": "EC2 Role tag e.g. hermes-cache-node or hermes-loadbalancer"
                    },
                    "cloudmap_service_id": {
                        "type": "string",
                        "description": "Cloud Map service ID for cache nodes. Omit for LB."
                    },
                    "health_port": {
                        "type": "integer",
                        "description": "Port for health check. Default 8082 for cache nodes.",
                        "default": 8082
                    },
                    "health_path": {
                        "type": "string",
                        "description": "Health path. Default /internal/cluster/members",
                        "default": "/internal/cluster/members"
                    }
                },
                "required": ["role_tag"]
            }
        )
    ]

@app.call_tool()
async def call_tool(name: str, arguments: dict):

    if name == "get_ec2_instances":
        role = arguments["role_tag"]
        ec2 = _ec2()
        resp = ec2.describe_instances(Filters=[
            {"Name": "tag:Role", "Values": [role]},
            {"Name": "instance-state-name", "Values": ["running", "stopped", "pending"]}
        ])
        instances = [i for r in resp["Reservations"] for i in r["Instances"]]
        if not instances:
            return [TextContent(type="text", text=f"No EC2 instances found with Role={role}")]

        result = f"EC2 Instances (Role={role}) — {len(instances)} found:\n\n"
        for i in instances:
            name_tag = next((t["Value"] for t in i.get("Tags", []) if t["Key"] == "Name"), "unnamed")
            result += f"  {i['InstanceId']} ({name_tag})\n"
            result += f"    State:      {i['State']['Name']}\n"
            result += f"    Private IP: {i.get('PrivateIpAddress', 'N/A')}\n"
            result += f"    Public IP:  {i.get('PublicIpAddress', 'N/A')}\n"
            result += f"    Type:       {i.get('InstanceType', 'N/A')}\n\n"
        return [TextContent(type="text", text=result)]

    elif name == "get_ssm_command_status":
        command_id = arguments["command_id"]
        ssm = _ssm()
        resp = ssm.list_command_invocations(CommandId=command_id, Details=True)
        invocations = resp.get("CommandInvocations", [])
        if not invocations:
            return [TextContent(type="text", text=f"No invocations found for command {command_id}")]

        result = f"SSM Command {command_id}:\n\n"
        all_success = True
        for inv in invocations:
            status = inv.get("Status", "Unknown")
            if status != "Success":
                all_success = False
            output = ""
            for cp in inv.get("CommandPlugins", []):
                output = cp.get("Output", "")[:500]
            result += f"  Instance: {inv['InstanceId']}\n"
            result += f"  Status:   {status}\n"
            if output:
                result += f"  Output:   {output}\n"
            result += "\n"
        result += "Overall: ✅ All succeeded\n" if all_success else "Overall: ❌ Some instances failed\n"
        return [TextContent(type="text", text=result)]

    elif name == "get_cloudmap_instances":
        service_id = arguments["service_id"]
        sd = _sd()
        resp = sd.list_instances(ServiceId=service_id)
        instances = resp.get("Instances", [])
        if not instances:
            return [TextContent(type="text", text=f"No instances registered in Cloud Map service {service_id}")]

        result = f"Cloud Map Service {service_id} — {len(instances)} instance(s):\n\n"
        for inst in instances:
            attrs = inst.get("Attributes", {})
            result += f"  ID: {inst['Id']}\n"
            result += f"  IP: {attrs.get('AWS_INSTANCE_IPV4', 'N/A')}\n"
            result += f"  Port: {attrs.get('AWS_INSTANCE_PORT', 'N/A')}\n\n"
        return [TextContent(type="text", text=result)]

    elif name == "get_cloudwatch_logs":
        log_group  = arguments["log_group"]
        log_stream = arguments.get("log_stream")
        minutes    = arguments.get("minutes", 15)
        cw = _logs()

        if not log_stream:
            streams = cw.describe_log_streams(
                logGroupName=log_group, orderBy="LastEventTime", descending=True, limit=1
            )
            stream_list = streams.get("logStreams", [])
            if not stream_list:
                return [TextContent(type="text", text=f"No log streams found in {log_group}")]
            log_stream = stream_list[0]["logStreamName"]

        start_ms = int((datetime.now(timezone.utc) - timedelta(minutes=minutes)).timestamp() * 1000)
        events_resp = cw.get_log_events(
            logGroupName=log_group, logStreamName=log_stream,
            startTime=start_ms, limit=100, startFromHead=False
        )
        events = events_resp.get("events", [])
        if not events:
            return [TextContent(type="text", text=f"No log events in last {minutes} minutes from {log_stream}")]

        result = f"CloudWatch Logs — {log_group}/{log_stream} (last {minutes} min):\n\n"
        for ev in events:
            ts = datetime.fromtimestamp(ev["timestamp"] / 1000, tz=timezone.utc).strftime("%H:%M:%S")
            result += f"[{ts}] {ev['message'].rstrip()}\n"
        return [TextContent(type="text", text=result)]

    elif name == "check_service_health":
        host = arguments["host"]
        port = arguments.get("port", 8080)
        path = arguments.get("path", "/api/v1/system/health")
        url  = f"http://{host}:{port}{path}"
        try:
            resp = httpx.get(url, timeout=10)
            status = "✅ HEALTHY" if resp.status_code < 400 else "⚠️ UNHEALTHY"
            return [TextContent(type="text", text=f"{status} — {url}\nHTTP {resp.status_code}\n{resp.text[:500]}")]
        except Exception as e:
            return [TextContent(type="text", text=f"❌ UNREACHABLE — {url}\nError: {e}")]

    elif name == "get_deployment_summary":
        role      = arguments["role_tag"]
        cm_svc_id = arguments.get("cloudmap_service_id")
        port      = arguments.get("health_port", 8082)
        path      = arguments.get("health_path", "/internal/cluster/members")

        ec2 = _ec2()
        resp = ec2.describe_instances(Filters=[
            {"Name": "tag:Role", "Values": [role]},
            {"Name": "instance-state-name", "Values": ["running"]}
        ])
        instances = [i for r in resp["Reservations"] for i in r["Instances"]]

        result = f"🚀 Deployment Summary — Role: {role}\n{'='*50}\n\n"
        result += f"EC2 Running Instances: {len(instances)}\n\n"

        healthy_count = 0
        for inst in instances:
            ip = inst.get("PrivateIpAddress", "")
            iid = inst["InstanceId"]
            url = f"http://{ip}:{port}{path}"
            try:
                hr = httpx.get(url, timeout=8)
                h_status = "✅ HEALTHY" if hr.status_code < 400 else "⚠️ UNHEALTHY"
                if hr.status_code < 400:
                    healthy_count += 1
            except Exception:
                h_status = "❌ UNREACHABLE"
            result += f"  {iid} ({ip}) — {h_status}\n"

        result += f"\nHealth Summary: {healthy_count}/{len(instances)} nodes healthy\n"

        if cm_svc_id:
            try:
                sd = _sd()
                cm_resp = sd.list_instances(ServiceId=cm_svc_id)
                cm_instances = cm_resp.get("Instances", [])
                result += f"\nCloud Map Registrations: {len(cm_instances)}\n"
                for ci in cm_instances:
                    result += f"  {ci['Id']} — {ci.get('Attributes', {}).get('AWS_INSTANCE_IPV4', 'N/A')}\n"
            except Exception as e:
                result += f"\nCloud Map: Error — {e}\n"

        result += f"\n{'='*50}\n"
        result += "✅ Deployment SUCCESSFUL\n" if healthy_count == len(instances) and instances else "❌ Deployment has issues — investigate above\n"
        return [TextContent(type="text", text=result)]


if __name__ == "__main__":
    import asyncio

    async def main():
        async with stdio_server() as (read_stream, write_stream):
            await app.run(read_stream, write_stream, app.create_initialization_options())

    asyncio.run(main())
