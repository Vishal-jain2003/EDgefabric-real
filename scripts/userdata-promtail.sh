#!/bin/bash
# Append this to your cache-node ASG launch template user data.
# Installs Promtail after Docker is ready, ships logs to self-hosted Loki.

LOKI_URL="http://10.0.14.141:3100"
PROMTAIL_VERSION="3.1.0"

# Wait for Docker to be running (cache node container must be up first)
until systemctl is-active docker &>/dev/null; do sleep 5; done
sleep 10

# Install Promtail
cd /tmp
curl -sLO "https://github.com/grafana/loki/releases/download/v${PROMTAIL_VERSION}/promtail-linux-amd64.zip"
unzip -o promtail-linux-amd64.zip
chmod +x promtail-linux-amd64
mv promtail-linux-amd64 /usr/local/bin/promtail
rm -f promtail-linux-amd64.zip

mkdir -p /etc/promtail /var/lib/promtail

cat > /etc/promtail/config.yml <<EOF
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /var/lib/promtail/positions.yaml

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

cat > /etc/systemd/system/promtail.service <<EOF
[Unit]
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
EOF

systemctl daemon-reload
systemctl enable promtail
systemctl start promtail
