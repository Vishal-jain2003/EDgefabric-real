import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-break';

const latency = new Trend('break_latency', true);
const errRate = new Rate('break_error_rate');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 300; i++) {
    http.put(`${CACHE}/break-seed-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '3600000' }
    });
  }
}

export const options = {
  // 1 VU added per second for 15 minutes = max 900 VUs
  stages: [
    { duration: '15m', target: 900 },
  ],
  thresholds: {
    'break_latency':    ['p(99)<2000'],
    'break_error_rate': ['rate<0.20'],
  },
};

export default function () {
  const start = Date.now();
  const isWrite = Math.random() < 0.30;
  let res;

  if (isWrite) {
    res = http.put(`${CACHE}/break-put-${__VU}-${__ITER}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '60000' },
      timeout: '3s'
    });
  } else {
    res = http.get(`${CACHE}/break-seed-${Math.floor(Math.random() * 300)}`, {
      headers: { 'X-Tenant': TENANT },
      timeout: '3s'
    });
  }

  latency.add(Date.now() - start);
  const ok = check(res, { 'ok': (r) => r.status < 500 });
  if (!ok) errRate.add(1); else errRate.add(0);
  sleep(0.05);
}
