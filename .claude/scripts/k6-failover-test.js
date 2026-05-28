import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-failover';

const putLatency = new Trend('failover_put_latency', true);
const getLatency = new Trend('failover_get_latency', true);
const errRate    = new Rate('failover_error_rate');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 500; i++) {
    http.put(`${CACHE}/fail-seed-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '3600000' }
    });
  }
}

export const options = {
  stages: [
    { duration: '2m', target: 80 },   // Phase 1: baseline
    { duration: '3m', target: 80 },   // Phase 2: node-2 killed during this window
    { duration: '3m', target: 80 },   // Phase 3: node-2 restarting
    { duration: '2m', target: 80 },   // Phase 4: full recovery validation
    { duration: '1m', target: 0  },
  ],
  thresholds: {
    'failover_put_latency': ['p(99)<900'],
    'failover_get_latency': ['p(99)<600'],
    'failover_error_rate':  ['rate<0.03'],
  },
};

export default function () {
  const isWrite = Math.random() < 0.40;
  const start = Date.now();

  if (isWrite) {
    const res = http.put(`${CACHE}/fail-put-${__VU}-${__ITER}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '120000' },
      timeout: '10s'
    });
    putLatency.add(Date.now() - start);
    const ok = check(res, { 'PUT ok': (r) => r.status === 200 });
    if (!ok) {
      errRate.add(1);
      console.log(`PUT FAIL t=${__ITER}: ${res.status} ${res.body.substring(0, 100)}`);
    } else errRate.add(0);
  } else {
    const res = http.get(`${CACHE}/fail-seed-${Math.floor(Math.random() * 500)}`, {
      headers: { 'X-Tenant': TENANT },
      timeout: '10s'
    });
    getLatency.add(Date.now() - start);
    const ok = check(res, { 'GET ok': (r) => r.status < 500 });
    if (!ok) errRate.add(1); else errRate.add(0);
  }
  sleep(0.1);
}
