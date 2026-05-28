#!/bin/sh
# ──────────────────────────────────────────────────────────────
# Cache-node entrypoint
#
# 1. If NODE_IP is already set (e.g. via docker -e NODE_IP=...),
#    use it as-is.
# 2. Otherwise, try the EC2 IMDS v2 metadata endpoint to fetch
#    the private IPv4 address.
# 3. Fall back to "localhost" for local/dev runs.
#
# Uses `exec` so the JVM becomes PID 1 and receives SIGTERM
# directly — this ensures Spring's graceful-shutdown hook and
# @PreDestroy (CloudMap deregister) fire reliably.
# ──────────────────────────────────────────────────────────────

if [ -z "$NODE_IP" ]; then
  echo "NODE_IP not set, attempting EC2 metadata lookup..."

  TOKEN=$(curl -sf -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null)

  if [ -n "$TOKEN" ]; then
    NODE_IP=$(curl -sf -H "X-aws-ec2-metadata-token: $TOKEN" \
      http://169.254.169.254/latest/meta-data/local-ipv4 2>/dev/null)
  fi

  # fallback for non-EC2 environments
  if [ -z "$NODE_IP" ]; then
    NODE_IP=localhost
    echo "EC2 metadata unavailable, falling back to NODE_IP=localhost"
  fi
fi

export NODE_IP
echo "Starting cache node with NODE_IP=$NODE_IP"

# JVM tuning for low-latency cache node
# - G1GC with 20ms max pause time target
# - Explicit heap sizing for predictable GC behavior
# - Container-aware settings (use 75% of Docker memory limit)
# - Larger heap for cache data (600M vs 512M for loadbalancer)
exec java \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Xms600M \
  -Xmx600M \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heapdump.hprof \
  -jar app.jar
