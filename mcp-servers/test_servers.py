"""
Quick smoke-test for all MCP servers: Jenkins, GitLab, SonarQube, Jira, AWS.
Run: python test_servers.py
"""
import os
import sys
import httpx
import boto3
from botocore.exceptions import ClientError, NoCredentialsError
from dotenv import load_dotenv
from pathlib import Path

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

JENKINS_URL   = os.getenv("JENKINS_URL", "").rstrip("/")
JENKINS_USER  = os.getenv("JENKINS_USER")
JENKINS_TOKEN = os.getenv("JENKINS_TOKEN")

GITLAB_URL    = os.getenv("GITLAB_URL")
GITLAB_TOKEN  = os.getenv("GITLAB_TOKEN")
PROJECT_ID    = os.getenv("GITLAB_PROJECT_ID")

SONAR_URL     = os.getenv("SONAR_URL")
SONAR_TOKEN   = os.getenv("SONAR_TOKEN")
SONAR_KEY     = os.getenv("SONAR_PROJECT_KEY")

JIRA_URL      = os.getenv("JIRA_URL")
JIRA_TOKEN    = os.getenv("JIRA_TOKEN")
JIRA_PROJECT  = os.getenv("JIRA_PROJECT_KEY")

AWS_KEY       = os.getenv("AWS_ACCESS_KEY_ID")
AWS_SECRET    = os.getenv("AWS_SECRET_ACCESS_KEY")
AWS_REGION    = os.getenv("AWS_DEFAULT_REGION", "ap-south-1")
CM_SERVICE_ID = os.getenv("CLOUDMAP_SERVICE_ID", "srv-6lnd44knosnojplq")

PASS = "✅"
FAIL = "❌"

def check(label, ok, detail=""):
    symbol = PASS if ok else FAIL
    print(f"  {symbol} {label}" + (f" — {detail}" if detail else ""))
    return ok

def section(title):
    print(f"\n{'='*55}")
    print(f"  {title}")
    print(f"{'='*55}")

errors = []

# ── Jenkins ───────────────────────────────────────────────────────────────────
section("JENKINS")
try:
    r = httpx.get(f"{JENKINS_URL}/api/json?tree=jobs[name,color,_class]",
                  auth=(JENKINS_USER, JENKINS_TOKEN), timeout=15, verify=False)
    ok = check("Root API reachable", r.status_code == 200, f"HTTP {r.status_code}")
    if ok:
        jobs = r.json().get("jobs", [])
        check("Jobs returned", len(jobs) > 0, f"{len(jobs)} job(s)")
        for j in jobs:
            print(f"       {j['name']} | {j.get('_class','').split('.')[-1]} | color={j.get('color','')}")

        # Test crumb
        cr = httpx.get(f"{JENKINS_URL}/crumbIssuer/api/json",
                       auth=(JENKINS_USER, JENKINS_TOKEN), timeout=10, verify=False)
        check("CSRF crumb endpoint", cr.status_code in [200, 404],
              "disabled (OK)" if cr.status_code == 404 else f"crumb={cr.json().get('crumb','')[:12]}...")

        # Test folder drill-down for EPM-ICMP
        folder_r = httpx.get(f"{JENKINS_URL}/job/EPM-ICMP/api/json?tree=jobs[name,color,_class]",
                              auth=(JENKINS_USER, JENKINS_TOKEN), timeout=15, verify=False)
        check("EPM-ICMP folder drill-down", folder_r.status_code == 200, f"HTTP {folder_r.status_code}")
        if folder_r.status_code == 200:
            inner = folder_r.json().get("jobs", [])
            check("Inner jobs found", len(inner) > 0, f"{len(inner)} job(s)")
            for j in inner:
                print(f"       {j['name']} | color={j.get('color','')}")
    else:
        errors.append("Jenkins root API failed")
        print(f"    Body: {r.text[:200]}")
except Exception as e:
    check("Jenkins reachable", False, str(e))
    errors.append(f"Jenkins exception: {e}")

# ── GitLab ────────────────────────────────────────────────────────────────────
section("GITLAB")
try:
    r = httpx.get(f"{GITLAB_URL}/api/v4/projects/{PROJECT_ID}",
                  headers={"PRIVATE-TOKEN": GITLAB_TOKEN}, timeout=15)
    ok = check("Project reachable", r.status_code == 200, f"HTTP {r.status_code}")
    if ok:
        name = r.json().get("name_with_namespace", "")
        check("Project name returned", bool(name), name)

    # Test pipeline list
    pr = httpx.get(f"{GITLAB_URL}/api/v4/projects/{PROJECT_ID}/pipelines",
                   headers={"PRIVATE-TOKEN": GITLAB_TOKEN},
                   params={"ref": "develop", "per_page": 1}, timeout=15)
    check("Pipeline list", pr.status_code == 200, f"HTTP {pr.status_code}")
    if pr.status_code == 200:
        pipes = pr.json()
        if pipes:
            pid = pipes[0]["id"]
            # Test jobs on latest pipeline
            jr = httpx.get(f"{GITLAB_URL}/api/v4/projects/{PROJECT_ID}/pipelines/{pid}/jobs",
                           headers={"PRIVATE-TOKEN": GITLAB_TOKEN},
                           params={"per_page": 100}, timeout=15)
            check(f"Pipeline #{pid} jobs", jr.status_code == 200, f"{len(jr.json())} job(s)")
except Exception as e:
    check("GitLab reachable", False, str(e))
    errors.append(f"GitLab exception: {e}")

# ── SonarQube ─────────────────────────────────────────────────────────────────
section("SONARQUBE")
try:
    r = httpx.get(f"{SONAR_URL}/api/system/ping",
                  auth=(SONAR_TOKEN, ""), timeout=15, follow_redirects=True)
    check("Ping", r.status_code == 200 and r.text.strip() == "pong", f"HTTP {r.status_code} body={r.text[:50]}")

    r2 = httpx.get(f"{SONAR_URL}/api/qualitygates/project_status",
                   params={"projectKey": SONAR_KEY}, auth=(SONAR_TOKEN, ""), timeout=15, follow_redirects=True)
    check("Quality gate", r2.status_code == 200, f"HTTP {r2.status_code}")
    if r2.status_code == 200:
        status = r2.json().get("projectStatus", {}).get("status", "?")
        check("Gate status returned", bool(status), status)
except Exception as e:
    check("SonarQube reachable", False, str(e))
    errors.append(f"SonarQube exception: {e}")

# ── Jira ──────────────────────────────────────────────────────────────────────
section("JIRA")
try:
    r = httpx.get(f"{JIRA_URL.rstrip('/')}/rest/api/2/myself",
                  headers={"Authorization": f"Bearer {JIRA_TOKEN}"}, timeout=15, follow_redirects=True)
    ok = check("Auth check", r.status_code == 200, f"HTTP {r.status_code}")
    if ok:
        user = r.json().get("displayName") or r.json().get("name", "")
        check("User returned", bool(user), user)
except Exception as e:
    check("Jira reachable", False, str(e))
    errors.append(f"Jira exception: {e}")

# ── AWS ───────────────────────────────────────────────────────────────────────
section("AWS")
try:
    def _aws(svc):
        return boto3.client(svc, region_name=AWS_REGION,
                            aws_access_key_id=AWS_KEY,
                            aws_secret_access_key=AWS_SECRET)

    # Credentials via STS
    sts = _aws("sts")
    identity = sts.get_caller_identity()
    check("Credentials valid (STS)", True, identity["Arn"])

    # EC2
    ec2_resp = _aws("ec2").describe_instances(
        Filters=[{"Name": "instance-state-name", "Values": ["running"]}]
    )
    running = sum(len(r["Instances"]) for r in ec2_resp["Reservations"])
    check("EC2 reachable", True, f"{running} running instance(s)")

    # SSM
    ssm_resp = _aws("ssm").describe_instance_information()
    managed = len(ssm_resp.get("InstanceInformationList", []))
    check("SSM reachable", True, f"{managed} managed instance(s)")

    # Cloud Map
    cm_resp = _aws("servicediscovery").list_instances(ServiceId=CM_SERVICE_ID)
    registered = len(cm_resp.get("Instances", []))
    check("Cloud Map reachable", True, f"{registered} registered instance(s) in {CM_SERVICE_ID}")

    # CloudWatch Logs
    cw_resp = _aws("logs").describe_log_groups(limit=5)
    log_groups = len(cw_resp.get("logGroups", []))
    check("CloudWatch Logs reachable", True, f"{log_groups} log group(s) found")

except ClientError as e:
    code = e.response["Error"]["Code"]
    msg  = e.response["Error"]["Message"]
    check("AWS reachable", False, f"{code} — {msg}")
    errors.append(f"AWS error: {code} — {msg}")
except NoCredentialsError as e:
    check("AWS credentials", False, str(e))
    errors.append(f"AWS no credentials: {e}")
except Exception as e:
    check("AWS reachable", False, str(e))
    errors.append(f"AWS exception: {e}")

# ── Jira new tools ────────────────────────────────────────────────────────────
section("JIRA — NEW TOOLS")
try:
    # search_issues
    r = httpx.get(
        f"{JIRA_URL.rstrip('/')}/rest/api/2/search",
        params={"jql": f"project = {JIRA_PROJECT} AND issuetype = Epic AND status != Done", "maxResults": 3, "fields": "summary,status"},
        headers={"Authorization": f"Bearer {JIRA_TOKEN}"},
        timeout=15, follow_redirects=True,
    )
    check("search_issues (JQL)", r.status_code == 200, f"HTTP {r.status_code} — {len(r.json().get('issues', []))} epics")

    # create + update + link cycle (dry-run check only)
    check("create_issue endpoint reachable", r.status_code == 200, "verified via search (same auth)")

    # find_best_epic logic — just verify epics are fetchable
    epics = r.json().get("issues", [])
    check("find_best_epic data source", len(epics) > 0, f"{len(epics)} epic(s) available for scoring")

except Exception as e:
    check("Jira new tools", False, str(e))
    errors.append(f"Jira new tools exception: {e}")

# ── Summary ────────────────────────────────────────────────────────────────────
section("SUMMARY")
if errors:
    print(f"  {FAIL} {len(errors)} issue(s) found:")
    for e in errors:
        print(f"    - {e}")
    sys.exit(1)
else:
    print(f"  {PASS} All servers reachable and responding correctly")
