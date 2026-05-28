import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Gauge } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-stress';

const putLatency = new Trend('stress_put_latency', true);
const getLatency = new Trend('stress_get_latency', true);
const errorRate  = new Rate('stress_error_rate');
const activeVUs  = new Gauge('active_vus');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 500; i++) {
    http.put(`${CACHE}/stress-seed-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '1800000' }
    });
  }
}

export const options = {
  stages: [
    { duration: '2m',  target: 50   },
    { duration: '3m',  target: 150  },
    { duration: '3m',  target: 300  },
    { duration: '3m',  target: 500  },
    { duration: '3m',  target: 700  },
    { duration: '3m',  target: 1000 },
    { duration: '5m',  target: 1000 },
    { duration: '3m',  target: 0    },
  ],
  thresholds: {
    'stress_error_rate': ['rate<0.50'],
  },
};

export default function () {
  activeVUs.add(__VU);
  const isWrite = Math.random() < 0.30;

  if (isWrite) {
    const key = `stress-put-${__VU}-${__ITER}`;
    const start = Date.now();
    const res = http.put(`${CACHE}/${key}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '120000' },
      timeout: '10s'
    });
    putLatency.add(Date.now() - start);
    const ok = check(res, { 'PUT 200': (r) => r.status === 200 });
    if (!ok) { errorRate.add(1); console.log(`PUT FAIL VU=${__VU} status=${res.status}`); }
    else errorRate.add(0);
  } else {
    const key = `stress-seed-${Math.floor(Math.random() * 500)}`;
    const start = Date.now();
    const res = http.get(`${CACHE}/${key}`, { headers: { 'X-Tenant': TENANT }, timeout: '10s' });
    getLatency.add(Date.now() - start);
    const ok = check(res, { 'GET ok': (r) => r.status === 200 || r.status === 404 });
    if (!ok) errorRate.add(1); else errorRate.add(0);
  }
  sleep(0.05);
}
