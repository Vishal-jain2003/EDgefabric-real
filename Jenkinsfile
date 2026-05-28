pipeline {
  agent { label 'linux' }

  tools {
    jdk 'JDK-21'
    maven 'Maven 3.9.8'
  }

  parameters {
    string(name: 'WEBHOOK_URL', defaultValue: '', description: 'Callback URL for build completion notification (set by CI automation)')
  }

  environment {
    MVN_REPO_REG        = "${WORKSPACE}/.m2repo-registry"
    MVN_REPO_CACHING    = "${WORKSPACE}/.m2repo-caching"
    MVN_REPO_LB         = "${WORKSPACE}/.m2repo-loadbalancer"
    MVN_REPO_AGENTOPS   = "${WORKSPACE}/.m2repo-agentops"
    CLOUDMAP_SERVICE_ID = 'srv-6lnd44knosnojplq'
  }

  stages {

    stage('Checkout') {
      steps {
        checkout scm
        echo "Branch detected: ${env.BRANCH_NAME}"
        echo "Node: ${env.NODE_NAME}, Labels: ${env.NODE_LABELS}"
        echo "Workspace: ${env.WORKSPACE}"
      }
    }

    stage('Clean Maven Repo') {
      steps {
        sh 'rm -rf "$MVN_REPO_REG" "$MVN_REPO_CACHING" "$MVN_REPO_LB" "$MVN_REPO_AGENTOPS"'
      }
    }

    stage('Env check') {
      steps {
        sh '''
          echo NODE_NAME=$NODE_NAME
          echo NODE_LABELS=$NODE_LABELS
          echo JAVA_HOME=$JAVA_HOME
          java -version
          which mvn
          mvn -v
          echo MVN_REPO_REG=$MVN_REPO_REG
          echo MVN_REPO_CACHING=$MVN_REPO_CACHING
          echo MVN_REPO_LB=$MVN_REPO_LB
          echo MVN_REPO_AGENTOPS=$MVN_REPO_AGENTOPS
        '''
      }
    }

    stage('Codebase index freshness') {
      steps {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
          sh '''
            if command -v py >/dev/null 2>&1; then PY=py
            elif command -v python3 >/dev/null 2>&1; then PY=python3
            else PY=python
            fi
            $PY mcp-servers/codebase_indexer.py --check
          '''
        }
      }
    }

    stage('Build parent pom') {
      steps {
        sh 'mvn -B -U -N -Dmaven.repo.local=$MVN_REPO_LB clean install'
        sh 'mvn -B -N -Dmaven.repo.local=$MVN_REPO_CACHING install'
      }
    }

    stage('Build hashing module') {
      steps {
        dir('consistent-hashing') {
          sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_LB clean install'
          sh 'mvn -B -Dmaven.repo.local=$MVN_REPO_CACHING install -DskipTests'
        }
      }
    }

    // Registry is an optional sidecar — built in parallel with caching and loadbalancer.
    // Its failure is non-blocking: caching and loadbalancer always proceed independently.
    stage('Build services') {
      steps {
        script {
          parallel(
            "registry-service": {
              catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                dir('registry') {
                  sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_REG clean verify'
                }
              }
            },
            "caching-service": {
              dir('caching') {
                sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_CACHING -Dgossip.port=0 clean verify'
              }
            },
            "loadbalancer-service": {
              dir('loadbalancer') {
                sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_LB clean verify'
              }
            },
            "agentic-ops-service": {
              dir('agentic-ops') {
                sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_AGENTOPS clean verify'
              }
            }
          )
        }
      }
    }

    stage('SonarQube Analysis') {
      steps {
        withSonarQubeEnv('SonarHyd') {
          sh '''
            mvn -B compile sonar:sonar \
              -Dmaven.repo.local=$MVN_REPO_LB \
              -Dsonar.projectKey=EPM-ICMP-HEREMES \
              -Dsonar.branch.name=${BRANCH_NAME}
          '''
        }
      }
    }

    stage('Quality Gate') {
      steps {
        timeout(time: 70, unit: 'MINUTES') {
          // waitForQualityGate checks project-level status — polluted by other teams on shared key.
          // Instead: custom check counting only violations in com.edgefabric.* packages.
          script {
            def qgResult = waitForQualityGate abortPipeline: false
            if (qgResult.status != 'OK') {
              withSonarQubeEnv('SonarHyd') {
                def violations = sh(
                  script: """curl -s -H "Authorization: Bearer $SONAR_AUTH_TOKEN" \
                    "$SONAR_HOST_URL/api/issues/search?projectKeys=EPM-ICMP-HEREMES&branch=${BRANCH_NAME}&sinceLeakPeriod=true&resolved=false&ps=100" \
                    | python3 -c "
import sys, json
data = json.load(sys.stdin)
ours = [i for i in data['issues'] if 'com.edgefabric' in i.get('component','')]
print(len(ours))
" """,
                  returnStdout: true
                ).trim()
                echo "Quality Gate status: ${qgResult.status} | EdgeFabric violations: ${violations}"
                if (violations.toInteger() > 0) {
                  error("Quality Gate FAILED: ${violations} new violations in com.edgefabric.* packages")
                } else {
                  echo "Quality Gate PASSED for EdgeFabric code (other teams noise ignored)"
                }
              }
            }
          }
        }
      }
    }

    stage('Package (develop + main + release)') {
      when {
        beforeAgent true
        anyOf {
          branch 'develop'
          branch 'main'
          expression { env.BRANCH_NAME?.startsWith('release/') }
        }
      }
      steps {
        script {
          parallel(
            "registry-service": {
              catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                dir('registry') {
                  sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_REG -DskipTests package'
                }
              }
            },
            "caching-service": {
              dir('caching') {
                sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_CACHING -DskipTests package'
              }
            },
            "loadbalancer-service": {
              dir('loadbalancer') {
                sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_LB -DskipTests package'
              }
            },
            "agentic-ops-service": {
              dir('agentic-ops') {
                sh 'mvn -B -U -Dmaven.repo.local=$MVN_REPO_AGENTOPS -DskipTests package'
              }
            }
          )
        }
      }
      post {
        // Use 'always' so caching/LB artifacts are archived even if registry packaging fails
        always {
          dir('registry') {
            archiveArtifacts artifacts: 'target/**/*.jar', allowEmptyArchive: true
          }
          dir('caching') {
            archiveArtifacts artifacts: 'target/**/*.jar', allowEmptyArchive: true
          }
          dir('loadbalancer') {
            archiveArtifacts artifacts: 'target/**/*.jar', allowEmptyArchive: true
          }
          dir('agentic-ops') {
            archiveArtifacts artifacts: 'target/**/*.jar', allowEmptyArchive: true
          }
        }
      }
    }

    stage('E2E Tests (Docker Compose)') {
      when {
        branch 'develop'
      }
      steps {
        sh '''
          echo "Cleaning up any leftover containers from a previous run..."
          docker compose down -v --remove-orphans || true

          echo "Force-removing ALL containers from this compose project (running or stopped)..."
          docker ps -aq --filter "name=epm-icmp-hermespipeline" | xargs -r docker rm -f || true

          echo "Force-releasing TCP ports 8080/8081/8082/8083/8084 in case any other container holds them..."
          docker ps -aq --filter "publish=8080" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=8081" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=8082" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=8083" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=8084" | xargs -r docker rm -f || true

          echo "Force-releasing UDP gossip ports 7946/7947/7948/7949..."
          docker ps -aq --filter "publish=7946" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=7947" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=7948" | xargs -r docker rm -f || true
          docker ps -aq --filter "publish=7949" | xargs -r docker rm -f || true

          echo "Starting EdgeFabric stack using Docker Compose..."
          docker compose up -d --build

          echo "Waiting for Load Balancer to become healthy..."
          READY=0
          for i in $(seq 1 30); do
            if curl -s http://localhost:8080/api/v1/system/health | grep '"status":"UP"' > /dev/null; then
              echo "Services are ready!"
              READY=1
              break
            fi
            echo "Waiting for services..."
            sleep 5
          done
          if [ "$READY" -eq 0 ]; then
            echo "Services did not become healthy in time"
            exit 1
          fi

          echo "Resolving actual Docker container name for cache-node-4..."
          NODE4_CONTAINER=$(docker inspect --format '{{.Name}}' $(docker compose ps -q cache-node-4) | sed 's|^/||')
          echo "cache-node-4 container name: $NODE4_CONTAINER"

          echo "Resolving actual Docker container name for cache-node-3..."
          NODE3_CONTAINER=$(docker inspect --format '{{.Name}}' $(docker compose ps -q cache-node-3) | sed 's|^/||')
          echo "cache-node-3 container name: $NODE3_CONTAINER"

          echo "Resolving actual Docker container name for cache-node-2..."
          NODE2_CONTAINER=$(docker inspect --format '{{.Name}}' $(docker compose ps -q cache-node-2) | sed 's|^/||')
          echo "cache-node-2 container name: $NODE2_CONTAINER"

          echo "Running E2E tests..."
          mvn -B -pl testing_edgefabric verify -Dedgefabric.url=http://localhost:8080 -Dnode2.container=$NODE2_CONTAINER -Dnode3.container=$NODE3_CONTAINER -Dnode4.container=$NODE4_CONTAINER
        '''
      }
      post {
        always {
          sh 'mvn -B -pl testing_edgefabric allure:report || true'
          archiveArtifacts artifacts: 'testing_edgefabric/target/site/allure-maven-plugin/**', allowEmptyArchive: true
          sh '''
            echo "Stopping the docker compose environment"
            docker compose down -v
          '''
        }
      }
    }

    stage('Docker Build & Push (all services)') {
      when {
        anyOf {
          branch 'main'
          expression { env.BRANCH_NAME?.startsWith('release/') }
        }
      }
      steps {
        withCredentials([usernamePassword(
          credentialsId: 'dockerhub-creds',
          usernameVariable: 'DOCKER_USERNAME',
          passwordVariable: 'DOCKER_PASSWORD'
        )]) {
          sh 'echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin'
          script {
            parallel(
              "loadbalancer": {
                sh '''
                  docker build -t anubhavpratap/edgefabric-loadbalancer:v1 loadbalancer
                  docker push anubhavpratap/edgefabric-loadbalancer:v1
                '''
              },
              "cache-node": {
                sh '''
                  docker build -t anubhavpratap/edgefabric-cache-node:v1 -f caching/Dockerfile .
                  docker push anubhavpratap/edgefabric-cache-node:v1
                '''
              },
              "service-registry": {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                  sh '''
                    docker build -t anubhavpratap/edgefabric-registry:v1 registry
                    docker push anubhavpratap/edgefabric-registry:v1
                  '''
                }
              },
              "grafana": {
                sh '''
                  cd monitoring
                  docker build -f Dockerfile.grafana -t anubhavpratap/edgefabric-grafana:v1 .
                  docker push anubhavpratap/edgefabric-grafana:v1
                '''
              },
              "prometheus": {
                sh '''
                  cd monitoring
                  docker build -f Dockerfile.prometheus -t anubhavpratap/edgefabric-prometheus:v1 .
                  docker push anubhavpratap/edgefabric-prometheus:v1
                '''
              },
              "alertmanager": {
                sh '''
                  cd monitoring
                  docker build -f Dockerfile.alertmanager -t anubhavpratap/edgefabric-alertmanager:v1 .
                  docker push anubhavpratap/edgefabric-alertmanager:v1
                '''
              },
              "agentic-ops": {
                sh '''
                  docker build -f agentic-ops/Dockerfile -t anubhavpratap/edgefabric-agentops:v1 .
                  docker push anubhavpratap/edgefabric-agentops:v1
                '''
              }
            )
          }
        }
      }
    }


    stage('Deploy Cache Nodes & LB via SSM') {
      when {
        expression { env.BRANCH_NAME?.startsWith('release/') }
      }
      steps {
        script {
          parallel(
                  "loadbalancer": {
                    withCredentials([usernamePassword(
                            credentialsId: 'aws-jenkins-creds',
                            usernameVariable: 'AWS_ACCESS_KEY_ID',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                      sh '''
                  export AWS_DEFAULT_REGION=ap-south-1

                  LB_INSTANCE_IDS=$(aws ec2 describe-instances \
                    --filters "Name=tag:Role,Values=hermes-loadbalancer" "Name=instance-state-name,Values=running" \
                    --query "Reservations[].Instances[].InstanceId" \
                    --output text | tr '\t\n' ' ' | xargs)
                  echo "Loadbalancer instances: $LB_INSTANCE_IDS"

                  if [ -z "$LB_INSTANCE_IDS" ]; then
                    echo "No running loadbalancer instances found with tag Role=hermes-loadbalancer"
                    exit 1
                  fi

                  COMMAND_ID=$(aws ssm send-command \
                    --instance-ids $LB_INSTANCE_IDS \
                    --document-name "AWS-RunShellScript" \
                    --comment "Deploy Loadbalancer" \
                    --parameters 'commands=[
                      "if ! command -v docker &>/dev/null; then yum install -y docker && systemctl start docker && systemctl enable docker; fi",
                      "docker pull anubhavpratap/edgefabric-loadbalancer:v1",
                      "docker stop loadbalancer || true",
                      "docker rm loadbalancer || true",
                      "docker run -d --restart unless-stopped --name loadbalancer --network host -e CLUSTER_DNS=cache-nodes.cache-cluster.internal -e CLUSTER_NODE_PORT=8082 -e WAL_ENABLED=true -e WAL_STORAGE=s3 -e WAL_S3_BUCKET=ef-hermes-wal -e WAL_REGION=ap-south-1 anubhavpratap/edgefabric-loadbalancer:v1"
                    ]' \
                    --query "Command.CommandId" \
                    --output text)

                  echo "Loadbalancer Command ID: $COMMAND_ID"

                  FAILED=0
                  for INSTANCE in $LB_INSTANCE_IDS; do
                    aws ssm wait command-executed \
                      --command-id "$COMMAND_ID" \
                      --instance-id "$INSTANCE" || true

                    STATUS=$(aws ssm list-command-invocations \
                      --command-id "$COMMAND_ID" \
                      --instance-id "$INSTANCE" \
                      --details \
                      --query "CommandInvocations[0].Status" \
                      --output text)

                    echo "Loadbalancer $INSTANCE deploy status: $STATUS"
                    if [ "$STATUS" != "Success" ]; then
                      echo "FAILED: $INSTANCE"
                      FAILED=1
                    fi
                  done

                  if [ "$FAILED" -eq 1 ]; then
                    echo "One or more loadbalancers failed"
                    exit 1
                  fi

                  echo "All loadbalancers deployed successfully"

                  # Step 2: Setup/update Promtail on LB instances
                  cat > /tmp/gen_promtail_params.py << 'PYEOF'
import json, base64

cfg = """server:
  http_listen_port: 9080
  grpc_listen_port: 0
positions:
  filename: /var/lib/promtail/positions.yaml
clients:
  - url: http://10.0.14.141:3100/loki/api/v1/push
scrape_configs:
  - job_name: docker
    static_configs:
      - targets: [localhost]
        labels:
          job: edgefabric
          environment: production
          __path__: /var/lib/docker/containers/*/*-json.log
    pipeline_stages:
      - json:
          expressions:
            log: log
      - json:
          expressions:
            level: level
            service_raw: service
            operation: operation
            result: result
          source: log
      - regex:
          expression: '^edgefabric-(?P<service>.+)$'
          source: service_raw
      - labels:
          level:
          service:
          operation:
          result:
      - output:
          source: log
"""

svc = """[Unit]
Description=Promtail Log Shipper
After=network-online.target docker.service
Wants=network-online.target
[Service]
Type=simple
ExecStart=/usr/local/bin/promtail -config.file=/etc/promtail/config.yml
Restart=on-failure
RestartSec=10
[Install]
WantedBy=multi-user.target
"""

cfg_b64 = base64.b64encode(cfg.encode()).decode()
svc_b64 = base64.b64encode(svc.encode()).decode()
commands = [
    "PROMTAIL_VERSION=3.1.0",
    "mkdir -p /etc/promtail /var/lib/promtail",
    "command -v promtail || (cd /tmp && curl -sLO https://github.com/grafana/loki/releases/download/v${PROMTAIL_VERSION}/promtail-linux-amd64.zip && unzip -o promtail-linux-amd64.zip && chmod +x promtail-linux-amd64 && mv promtail-linux-amd64 /usr/local/bin/promtail && rm -f promtail-linux-amd64.zip)",
    "echo " + cfg_b64 + " | base64 -d > /etc/promtail/config.yml",
    "echo " + svc_b64 + " | base64 -d > /etc/systemd/system/promtail.service",
    "systemctl daemon-reload",
    "systemctl enable promtail",
    "systemctl restart promtail",
    "sleep 5",
    "systemctl is-active promtail && echo Promtail OK"
]
params = {"commands": commands}
with open('/tmp/prom-ssm-params.json', 'w') as f:
    json.dump({"Parameters": params}, f)
PYEOF
                  python3 /tmp/gen_promtail_params.py
                  rm -f /tmp/gen_promtail_params.py

                  PROM_CMD_ID=$(aws ssm send-command \
                    --instance-ids $LB_INSTANCE_IDS \
                    --document-name "AWS-RunShellScript" \
                    --comment "Setup Promtail on Loadbalancer" \
                    --cli-input-json file:///tmp/prom-ssm-params.json \
                    --query "Command.CommandId" \
                    --output text)
                  rm -f /tmp/prom-ssm-params.json
                  echo "Promtail setup command: $PROM_CMD_ID"
                  for INSTANCE in $LB_INSTANCE_IDS; do
                    aws ssm wait command-executed \
                      --command-id "$PROM_CMD_ID" \
                      --instance-id "$INSTANCE" || true
                    PSTATUS=$(aws ssm list-command-invocations \
                      --command-id "$PROM_CMD_ID" \
                      --instance-id "$INSTANCE" \
                      --details \
                      --query "CommandInvocations[0].Status" \
                      --output text)
                    echo "Promtail setup $INSTANCE: $PSTATUS"
                  done
                '''
                    }
                  },

                "cache-nodes": {
                    withCredentials([usernamePassword(
                            credentialsId: 'aws-jenkins-creds',
                            usernameVariable: 'AWS_ACCESS_KEY_ID',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                    sh '''
                 export AWS_DEFAULT_REGION=ap-south-1

                 INSTANCE_IDS=$(aws ec2 describe-instances \
                   --filters "Name=tag:Role,Values=hermes-cache-node" "Name=instance-state-name,Values=running" \
                   --query "Reservations[].Instances[].InstanceId" \
                   --output text | tr '\t\n' ' ' | xargs)
                 echo "Cache node instances: $INSTANCE_IDS"

                 if [ -z "$INSTANCE_IDS" ]; then
                   echo "No running cache-node instances found with tag Role=hermes-cache-node"
                   exit 1
                 fi

                 FAILED=0
                 NODE_NUM=0
                 for INSTANCE in $INSTANCE_IDS; do
                   if [ "$NODE_NUM" -gt 0 ]; then
                     echo "Waiting 15s for gossip convergence before next node..."
                     sleep 15
                   fi
                   NODE_NUM=$((NODE_NUM+1))
                   echo "Deploying cache node $NODE_NUM: $INSTANCE"

                   # Generate base64-encoded deploy script so all vars live in one shell session
                   SCRIPT_B64=$(python3 << 'PYEOF_DEPLOY'
import base64
s = (
    "#!/bin/bash\n"
    "set -e\n"
    "TOKEN=$(curl -X PUT http://169.254.169.254/latest/api/token "
    "-H 'X-aws-ec2-metadata-token-ttl-seconds: 21600' -s)\n"
    "NODE_IP=$(curl -H \"X-aws-ec2-metadata-token: $TOKEN\" "
    "http://169.254.169.254/latest/meta-data/local-ipv4 -s)\n"
    "NODE_ID=$(echo \"$NODE_IP\" | tr . -)\n"
    "echo Deploying cache node at \"$NODE_IP\" NODE_ID=\"$NODE_ID\"\n"
    "docker pull anubhavpratap/edgefabric-cache-node:v1\n"
    "docker stop cache-node 2>/dev/null || true\n"
    "docker rm cache-node 2>/dev/null || true\n"
    "docker run -d --restart unless-stopped --name cache-node --network host"
    " -e CLOUDMAP_ENABLED=true"
    " -e CLOUDMAP_SERVICE_ID=srv-6lnd44knosnojplq"
    " -e SPRING_APPLICATION_NAME=edgefabric-cache-node-\"$NODE_ID\""
    " -e AWS_REGION=ap-south-1"
    " -e WAL_HEALING_ENABLED=true"
    " -e CACHE_WAL_CLIENT_LB_BASE_URL=http://10.0.11.146:8080"
    " -e NODE_IP=\"$NODE_IP\""
    " anubhavpratap/edgefabric-cache-node:v1\n"
    "sleep 10\n"
    "docker ps --filter name=cache-node\n"
    "echo CACHE_DEPLOY_OK ip=\"$NODE_IP\"\n"
)
print(base64.b64encode(s.encode()).decode(), end='')
PYEOF_DEPLOY
                   SCRIPT_B64=$(python3 /tmp/gen_cache_b64.py)
                   rm -f /tmp/gen_cache_b64.py

                   cat > /tmp/gen_cache_params.py << PYEOF_PARAMS
import json
params = {"Parameters": {"commands": ["echo $SCRIPT_B64 | base64 -d | bash"]}}
with open('/tmp/cache-node-params.json', 'w') as f:
    json.dump(params, f)
PYEOF_PARAMS
                   python3 /tmp/gen_cache_params.py
                   rm -f /tmp/gen_cache_params.py

                   COMMAND_ID=$(aws ssm send-command \
                     --instance-ids "$INSTANCE" \
                     --document-name "AWS-RunShellScript" \
                     --comment "Deploy Cache Node sequential" \
                     --cli-input-json file:///tmp/cache-node-params.json \
                     --query "Command.CommandId" \
                     --output text)
                   rm -f /tmp/cache-node-params.json
                   echo "Cache node $INSTANCE Command ID: $COMMAND_ID"

                   aws ssm wait command-executed \
                     --command-id "$COMMAND_ID" \
                     --instance-id "$INSTANCE" || true

                   STATUS=$(aws ssm list-command-invocations \
                     --command-id "$COMMAND_ID" \
                     --instance-id "$INSTANCE" \
                     --details \
                     --query "CommandInvocations[0].Status" \
                     --output text)

                   echo "Cache node $INSTANCE deploy status: $STATUS"
                   if [ "$STATUS" != "Success" ]; then
                     echo "FAILED: $INSTANCE"
                     aws ssm list-command-invocations \
                       --command-id "$COMMAND_ID" \
                       --instance-id "$INSTANCE" \
                       --details \
                       --query "CommandInvocations[0].CommandPlugins[0].Output" \
                       --output text || true
                     FAILED=1
                   fi
                 done

                 if [ "$FAILED" -eq 1 ]; then
                   echo "One or more cache nodes failed"
                   exit 1
                 fi

                 echo "All cache nodes deployed successfully" 
                '''
                    }
                  },

                  "agentic-ops": {
                    withCredentials([
                      usernamePassword(
                        credentialsId: 'aws-jenkins-creds',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                      ),
                      string(credentialsId: 'anthropic-api-key', variable: 'ANTHROPIC_API_KEY')
                    ]) {
                      sh '''
                  export AWS_DEFAULT_REGION=ap-south-1

                  LB_INSTANCE_IDS=$(aws ec2 describe-instances \
                    --filters "Name=tag:Role,Values=hermes-loadbalancer" "Name=instance-state-name,Values=running" \
                    --query "Reservations[].Instances[].InstanceId" \
                    --output text | tr '\t\n' ' ' | xargs)
                  echo "Deploying agentic-ops on LB instances: $LB_INSTANCE_IDS"

                  if [ -z "$LB_INSTANCE_IDS" ]; then
                    echo "No running loadbalancer instances found — skipping agentic-ops deploy"
                    exit 1
                  fi

                  COMMAND_ID=$(aws ssm send-command \
                    --instance-ids $LB_INSTANCE_IDS \
                    --document-name "AWS-RunShellScript" \
                    --comment "Deploy agentic-ops" \
                    --parameters "commands=[
                      \"docker pull anubhavpratap/edgefabric-agentops:v1\",
                      \"docker stop agentic-ops || true\",
                      \"docker rm agentic-ops || true\",
                      \"docker run -d --restart unless-stopped --name agentic-ops --network host -e LLM_API_KEY=$ANTHROPIC_API_KEY -e AGENTOPS_LB_URL=http://localhost:8080 anubhavpratap/edgefabric-agentops:v1\",
                      \"sleep 10\",
                      \"docker ps --filter name=agentic-ops\",
                      \"curl -sf http://localhost:8090/api/v1/observe/health && echo agentic-ops OK || { docker logs agentic-ops --tail 30; exit 1; }\"
                    ]" \
                    --query "Command.CommandId" \
                    --output text)

                  echo "agentic-ops Command ID: $COMMAND_ID"

                  FAILED=0
                  for INSTANCE in $LB_INSTANCE_IDS; do
                    aws ssm wait command-executed \
                      --command-id "$COMMAND_ID" \
                      --instance-id "$INSTANCE" || true

                    STATUS=$(aws ssm list-command-invocations \
                      --command-id "$COMMAND_ID" \
                      --instance-id "$INSTANCE" \
                      --details \
                      --query "CommandInvocations[0].Status" \
                      --output text)

                    echo "agentic-ops $INSTANCE deploy status: $STATUS"
                    if [ "$STATUS" != "Success" ]; then
                      FAILED=1
                    fi
                  done

                  if [ "$FAILED" -eq 1 ]; then
                    echo "agentic-ops deploy failed on one or more instances"
                    exit 1
                  fi
                  echo "agentic-ops deployed successfully"
                '''
                    }
                  },

                  "monitoring-stack": {
                    withCredentials([usernamePassword(
                            credentialsId: 'aws-jenkins-creds',
                            usernameVariable: 'AWS_ACCESS_KEY_ID',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                      sh '''
                  export AWS_DEFAULT_REGION=ap-south-1

                  MON_INSTANCE_ID=$(aws ec2 describe-instances \
                    --filters "Name=tag:Role,Values=hermes-grafana" "Name=instance-state-name,Values=running" \
                    --query "Reservations[].Instances[].InstanceId" \
                    --output text | head -1)

                  if [ -z "$MON_INSTANCE_ID" ]; then
                    echo "No running instance with tag Role=hermes-grafana found"
                    exit 1
                  fi
                  echo "Monitoring instance: $MON_INSTANCE_ID"

                  MON_CMD_ID=$(aws ssm send-command \
                    --instance-ids "$MON_INSTANCE_ID" \
                    --document-name "AWS-RunShellScript" \
                    --comment "Deploy Monitoring Stack (Prometheus + Alertmanager + Grafana)" \
                    --parameters 'commands=[
                      "docker pull anubhavpratap/edgefabric-prometheus:v1",
                      "docker pull anubhavpratap/edgefabric-alertmanager:v1",
                      "docker pull anubhavpratap/edgefabric-grafana:v1",
                      "docker stop prometheus || true",
                      "docker rm prometheus || true",
                      "docker stop alertmanager || true",
                      "docker rm alertmanager || true",
                      "docker stop grafana || true",
                      "docker rm grafana || true",
                      "docker run -d --restart unless-stopped --name alertmanager --network host anubhavpratap/edgefabric-alertmanager:v1",
                      "docker run -d --restart unless-stopped --name prometheus --network host anubhavpratap/edgefabric-prometheus:v1",
                      "docker run -d --restart unless-stopped --name grafana --network host -e GF_SECURITY_ADMIN_USER=admin -e GF_SECURITY_ADMIN_PASSWORD=edgefabric2024 -e LOKI_URL=http://localhost:3100 -e PROMETHEUS_URL=http://localhost:9090 anubhavpratap/edgefabric-grafana:v1",
                      "sleep 15",
                      "curl -sf http://localhost:9093/-/healthy && echo Alertmanager healthy || { docker logs alertmanager --tail 30; exit 1; }",
                      "curl -sf http://localhost:9090/-/healthy && echo Prometheus healthy || { docker logs prometheus --tail 30; exit 1; }",
                      "curl -sf http://localhost:3000/api/health && echo Grafana healthy || { docker logs grafana --tail 30; exit 1; }"
                    ]' \
                    --query "Command.CommandId" \
                    --output text)

                  echo "Monitoring deploy command: $MON_CMD_ID"
                  aws ssm wait command-executed \
                    --command-id "$MON_CMD_ID" \
                    --instance-id "$MON_INSTANCE_ID" || true

                  MON_STATUS=$(aws ssm list-command-invocations \
                    --command-id "$MON_CMD_ID" \
                    --instance-id "$MON_INSTANCE_ID" \
                    --details \
                    --query "CommandInvocations[0].Status" \
                    --output text)
                  echo "Monitoring deploy: $MON_STATUS"
                  if [ "$MON_STATUS" != "Success" ]; then
                    exit 1
                  fi
                '''
                    }
                  }

          )
        }
      }
    }

    stage('Deploy (main only / prod)') {
      when {
        beforeAgent true
        branch 'main'
      }
      steps {
        echo "Add main/prod deploy steps here"
      }
    }

    stage('Feature branch info') {
      when {
        expression { env.BRANCH_NAME?.startsWith('feature/') }
      }
      steps {
        echo "Feature branch: build/test done; packaging/deploy skipped."
      }
    }

  }

  post {
    success {
      echo "Pipeline SUCCESS for ${env.BRANCH_NAME}"
      script {
        if (env.WEBHOOK_URL) {
          sh """
            curl -s -X POST "${env.WEBHOOK_URL}" \
              -H "Content-Type: application/json" \
              -d '{
                "result":  "SUCCESS",
                "job":     "${env.JOB_NAME}",
                "build":   "${env.BUILD_NUMBER}",
                "branch":  "${env.BRANCH_NAME}",
                "url":     "${env.BUILD_URL}",
                "duration": "${currentBuild.durationString}"
              }' || true
          """
        }
      }
    }
    failure {
      echo "Pipeline FAILED for ${env.BRANCH_NAME}"
      script {
        if (env.WEBHOOK_URL) {
          sh """
            curl -s -X POST "${env.WEBHOOK_URL}" \
              -H "Content-Type: application/json" \
              -d '{
                "result":  "FAILURE",
                "job":     "${env.JOB_NAME}",
                "build":   "${env.BUILD_NUMBER}",
                "branch":  "${env.BRANCH_NAME}",
                "url":     "${env.BUILD_URL}",
                "duration": "${currentBuild.durationString}"
              }' || true
          """
        }
      }
    }
    unstable {
      script {
        if (env.WEBHOOK_URL) {
          sh """
            curl -s -X POST "${env.WEBHOOK_URL}" \
              -H "Content-Type: application/json" \
              -d '{
                "result":  "UNSTABLE",
                "job":     "${env.JOB_NAME}",
                "build":   "${env.BUILD_NUMBER}",
                "branch":  "${env.BRANCH_NAME}",
                "url":     "${env.BUILD_URL}",
                "duration": "${currentBuild.durationString}"
              }' || true
          """
        }
      }
    }
    aborted {
      script {
        if (env.WEBHOOK_URL) {
          sh """
            curl -s -X POST "${env.WEBHOOK_URL}" \
              -H "Content-Type: application/json" \
              -d '{
                "result":  "ABORTED",
                "job":     "${env.JOB_NAME}",
                "build":   "${env.BUILD_NUMBER}",
                "branch":  "${env.BRANCH_NAME}",
                "url":     "${env.BUILD_URL}",
                "duration": "${currentBuild.durationString}"
              }' || true
          """
        }
      }
    }
  }
}
