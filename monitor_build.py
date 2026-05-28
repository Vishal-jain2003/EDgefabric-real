#!/usr/bin/env python3
import json
import time
import sys
import os

JENKINS_USER = os.environ.get('JENKINS_USER')
JENKINS_TOKEN = os.environ.get('JENKINS_TOKEN')
JENKINS_URL = "https://jenkinshyd.epam.com/jenkins"
JOB_PATH = "job/EPM-ICMP/job/EPM-ICMP-JAN2026/job/EPM-ICMP-EDGEFABRIC/job/EPM-ICMP-EFHERMES/job/EPM-ICMP-HermesPipeline/job/release%2Fv1.7.0"

import subprocess

def get_build_status(build_number):
    """Fetch build status from Jenkins"""
    url = f"{JENKINS_URL}/{JOB_PATH}/{build_number}/api/json"
    cmd = ['curl', '-s', '-u', f'{JENKINS_USER}:{JENKINS_TOKEN}', url]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0:
        return json.loads(result.stdout)
    return None

# Monitor build #46
BUILD_NUMBER = 46
print(f"[MONITOR] Tracking build #{BUILD_NUMBER}")
print(f"[MONITOR] Jenkins job: {JOB_PATH}")
print()

max_polls = 60
poll_interval = 15
poll_count = 0

while poll_count < max_polls:
    data = get_build_status(BUILD_NUMBER)
    if not data:
        print(f"[POLL {poll_count+1}] Failed to fetch build status")
        poll_count += 1
        time.sleep(poll_interval)
        continue
    
    building = data.get('building', False)
    result = data.get('result')
    duration = data.get('duration', 0) // 1000  # Convert to seconds
    
    if building:
        elapsed = poll_count * poll_interval
        print(f"[POLL {poll_count+1}] Running... {elapsed}s elapsed ({duration}s actual)")
    else:
        print()
        print(f"RESULT: {result}")
        print(f"Build #{BUILD_NUMBER} completed with status: {result}")
        print(f"Duration: {duration}s")
        print()
        
        # Print stage details
        stages = data.get('stages', [])
        if stages:
            print("Stage Results:")
            for stage in stages:
                stage_name = stage.get('name', 'Unknown')
                stage_status = stage.get('status', 'UNKNOWN')
                print(f"  - {stage_name}: {stage_status}")
        
        sys.exit(0 if result == 'SUCCESS' else 1)
    
    poll_count += 1
    time.sleep(poll_interval)

print(f"[TIMEOUT] Build did not complete within {max_polls * poll_interval}s")
sys.exit(1)
