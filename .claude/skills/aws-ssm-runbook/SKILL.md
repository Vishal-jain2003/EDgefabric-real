---
name: aws-ssm-runbook
description: How to deploy and operate EdgeFabric on AWS via SSM (no SSH, no ECS). Load this whenever the task mentions deploy, SSM, EC2, Cloud Map, rolling restart, AWS health, or running shell commands on prod instances.
---

# Skill: AWS SSM Runbook

EdgeFabric uses **AWS SSM RunCommand** — there is no SSH access, no ECS, no
ECR. All shell commands on instances go through SSM and are auditable in
CloudTrail.

## Instance discovery

| Tag | Role |
|-----|------|
| `Role=hermes-loadbalancer` | LB nodes |
| `Role=hermes-cache-node` | Cache nodes |

```python
# via aws-get_ec2_instances MCP
aws-get_ec2_instances(role_tag="hermes-cache-node")
```

Cache nodes additionally self-register into Cloud Map service
`srv-6lnd44knosnojplq`. Use `aws-get_cloudmap_instances` to list registered IPs.

## Standard deploy sequence

```
1. docker-image-builder  → pushes anubhavpratap/edgefabric-<svc>:vX.Y.Z
2. SSM RunCommand on each instance (parallel for LB, SERIAL for cache):
     docker pull anubhavpratap/edgefabric-<svc>:vX.Y.Z
     docker stop <svc> && docker rm <svc>
     docker run -d --name <svc> --network host --restart unless-stopped \
       -e SPRING_PROFILES_ACTIVE=aws \
       anubhavpratap/edgefabric-<svc>:vX.Y.Z
3. Poll aws-get_ssm_command_status until all targets DONE
4. Run aws-check_service_health against each instance
5. Verify Cloud Map registration count == EC2 count (cache only)
```

## Rolling restart rule (cache nodes)

**Never** restart all cache nodes at once. Gossip needs time to re-converge.

```
for instance in cache_instances:
    ssm.send_command(...)
    wait until aws-check_service_health(instance, port=8082) == OK
    sleep 15s   # ≥ gossip.intervalMs * fanout * 3
```

LB nodes are stateless — parallel restart is fine.

## Health endpoints

| Service | Port | Path |
|---------|------|------|
| LB | 8080 | `/api/v1/system/health` |
| Cache | 8082 | `/api/v1/system/health` |
| Cache cluster view | 8082 | `/internal/cluster/members` |

Use `aws-get_deployment_summary(role_tag, cloudmap_service_id, health_path, health_port)`
for a one-shot status check.

## CloudWatch log groups

| Service | Log group |
|---------|-----------|
| LB | `/aws/ec2/hermes-loadbalancer` |
| Cache | `/aws/ec2/hermes-cache-node` |

Default fetch window: 15 minutes. **Always tail to ≤ 200 lines** to keep
context small (`aws-get_cloudwatch_logs` truncates by default).

## Forbidden commands via SSM

The `guard-bash.py` hook will block these on PreToolUse. If you genuinely
need them, get human approval first.

- `rm -rf /` (or any path outside the deploy dir)
- `docker system prune -a` without `--filter`
- `iptables -F` (would break gossip immediately)
- Anything that touches `/var/lib/docker` directly
- Name-based process kills (`pkill`, `killall`) — use specific PIDs

## Required env on every instance

```
SPRING_PROFILES_ACTIVE=aws
AWS_REGION=ap-south-1
CLOUDMAP_SERVICE_ID=srv-6lnd44knosnojplq   # cache only
```

## After every deploy

1. Post a deployment report comment on the linked Jira ticket via
   `atlassian-add_issue_comment` (instances, image tag, health %).
2. Persist a recall entry: `py .claude/scripts/recall.py write deploy-<vX.Y.Z>`.
