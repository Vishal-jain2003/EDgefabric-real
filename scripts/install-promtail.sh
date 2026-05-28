#!/bin/bash
set -euo pipefail

# Install Promtail on EC2 instance for shipping Docker container logs to self-hosted Loki.
# Required env var: LOKI_URL (e.g., http://10.0.1.50:3100)

PROMTAIL_VERSION="3.1.0"
PROMTAIL_BIN="/usr/local/bin/promtail"
PROMTAIL_CONFIG="/etc/promtail/config.yml"
PROMTAIL_POSITIONS="/var/lib/promtail"

if [ -z "${LOKI_URL:-}" ]; then
    echo "ERROR: LOKI_URL must be set (e.g., http://<loki-private-ip>:3100)"
    exit 1
fi

echo "==> Installing Promtail ${PROMTAIL_VERSION}..."

# Download and install binary
cd /tmp
curl -sLO "https://github.com/grafana/loki/releases/download/v${PROMTAIL_VERSION}/promtail-linux-amd64.zip"
unzip -o promtail-linux-amd64.zip
chmod +x promtail-linux-amd64
mv promtail-linux-amd64 "${PROMTAIL_BIN}"
rm -f promtail-linux-amd64.zip

# Create directories
mkdir -p /etc/promtail "${PROMTAIL_POSITIONS}"

# Write config pointing to self-hosted Loki (no auth needed within VPC)
cat > "${PROMTAIL_CONFIG}" <<EOF
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: ${PROMTAIL_POSITIONS}/positions.yaml

clients:
  - url: ${LOKI_URL}/loki/api/v1/push

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
            stream: stream
      - json:
          expressions:
            level: level
            service_name: service
            operation: operation
            result: result
            nodeId: nodeId
          source: log
      - labels:
          level:
          service_name:
          operation:
          result:
          nodeId:
      - output:
          source: log
EOF

# Install systemd service
cat > /etc/systemd/system/promtail.service <<EOF
[Unit]
Description=Promtail Log Shipper
Documentation=https://grafana.com/docs/loki/latest/clients/promtail/
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=${PROMTAIL_BIN} -config.file=${PROMTAIL_CONFIG}
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# Enable and start
systemctl daemon-reload
systemctl enable promtail
systemctl restart promtail

echo "==> Promtail installed and running (shipping to ${LOKI_URL})"
systemctl status promtail --no-pager
