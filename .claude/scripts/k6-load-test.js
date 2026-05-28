import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-load';

const putLatency = new Trend('put_latency', true);
const getLatency = new Trend('get_latency', true);
const errorRate  = new Rate('error_rate');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 1000; i++) {
    http.put(`${CACHE}/seed-load-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '900000' }
    });
  }
  return { seeded: 1000 };
}

export const options = {
  stages: [
    { duration: '2m', target: 50  },
    { duration: '2m', target: 100 },
    { duration: '5m', target: 150 },
    { duration: '1m', target: 0   },
  ],
  thresholds: {
    'put_latency{scenario:default}': ['p(95)<200', 'p(99)<500'],
    'get_latency{scenario:default}': ['p(95)<150', 'p(99)<300'],
    'error_rate':                    ['rate<0.01'],
    'http_req_failed':               ['rate<0.01'],
  },
};

export default function () {
  const rng = Math.random();
  const key = `seed-load-${Math.floor(Math.random() * 1000)}`;

  if (rng < 0.30) {
    const putKey = `load-put-${__VU}-${__ITER}`;
    const start = Date.now();
    const res = http.put(`${CACHE}/${putKey}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '300000' }
    });
    putLatency.add(Date.now() - start);
    const ok = check(res, { 'PUT 200': (r) => r.status === 200 });
    if (!ok) errorRate.add(1); else errorRate.add(0);
  } else {
    const start = Date.now();
    const res = http.get(`${CACHE}/${key}`, { headers: { 'X-Tenant': TENANT } });
    getLatency.add(Date.now() - start);
    const ok = check(res, { 'GET 200 or 404': (r) => r.status === 200 || r.status === 404 });
    if (!ok) errorRate.add(1); else errorRate.add(0);
  }
  sleep(0.1);
}
