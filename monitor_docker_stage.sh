#!/bin/bash

JENKINS_URL="https://jenkinshyd.epam.com/jenkins"
JOB_PATH="job/EPM-ICMP/job/EPM-ICMP-JAN2026/job/EPM-ICMP-EDGEFABRIC/job/EPM-ICMP-EFHERMES/job/EPM-ICMP-HermesPipeline/job/release%2Fv1.7.0"
BUILD_NUMBER=46

echo "[MONITOR] Watching build #$BUILD_NUMBER until Docker Build stage completes"
echo "[MONITOR] Checking every 30 seconds"
echo ""

for i in {1..120}; do
  curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" \
    "$JENKINS_URL/$JOB_PATH/$BUILD_NUMBER/api/json" > build_check.json 2>&1

  python3 << 'PYEOF'
import json, sys
try:
    with open('build_check.json', 'r') as f:
        data = json.load(f)
    
    building = data.get('building', False)
    result = data.get('result')
    stages = data.get('stages', [])
    
    if not building and result:
        print(f"\n>>> BUILD COMPLETED <<<")
        print(f"Result: {result}")
        if stages:
            print("\nFinal Stage Results:")
            for s in stages:
                name = s.get('name', '?')
                status = s.get('status', '?')
                print(f"  {name}: {status}")
        sys.exit(0)
    
    # Look for Docker stage
    if stages:
        docker_stage = next((s for s in stages if 'docker' in s.get('name', '').lower()), None)
        if docker_stage:
            status = docker_stage.get('status', '?')
            if status in ['FAILURE', 'SUCCESS', 'ABORTED']:
                print(f"\nDocker stage reached: {status}")
                print("\nAll stages so far:")
                for s in stages:
                    print(f"  {s.get('name')}: {s.get('status')}")
                sys.exit(0)
            else:
                elapsed = (i * 30)
                print(f"[{i}] Docker stage {status} ({elapsed}s elapsed)")
                sys.exit(1)
        else:
            elapsed = (i * 30)
            stages_str = ', '.join(s.get('name', '?') for s in stages[:3])
            print(f"[{i}] Building... {elapsed}s | Stages: {stages_str}... ({len(stages)} total)")
            sys.exit(1)
    else:
        elapsed = (i * 30)
        print(f"[{i}] Building... {elapsed}s (no stages yet)")
        sys.exit(1)
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
PYEOF

  if [ $? -eq 0 ]; then
    exit 0
  fi
  
  sleep 30
done

echo "[TIMEOUT] Monitoring exceeded 1 hour"
exit 1
