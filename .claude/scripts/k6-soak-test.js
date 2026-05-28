import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-soak';

const putLatency = new Trend('soak_put_latency', true);
const getLatency = new Trend('soak_get_latency', true);
const errRate    = new Rate('soak_error_rate');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 2000; i++) {
    http.put(`${CACHE}/soak-seed-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '7200000' }
    });
  }
}

export const options = {
  stages: [
    { duration: '5m',  target: 80 },
    { duration: '50m', target: 80 },
    { duration: '5m',  target: 0  },
  ],
  thresholds: {
    'soak_put_latency': ['p(95)<250'],
    'soak_get_latency': ['p(95)<200'],
    'soak_error_rate':  ['rate<0.005'],
  },
};

let iteration = 0;

export default function () {
  iteration++;
  const isNewKey = (iteration % 100 === 0);
  const keyIdx = isNewKey ? `soak-new-${__VU}-${__ITER}` : `soak-seed-${Math.floor(Math.random() * 2000)}`;

  if (Math.random() < 0.30 || isNewKey) {
    const start = Date.now();
    const res = http.put(`${CACHE}/${keyIdx}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '600000' },
      timeout: '8s'
    });
    putLatency.add(Date.now() - start);
    const ok = check(res, { 'PUT 200': (r) => r.status === 200 });
    if (!ok) errRate.add(1); else errRate.add(0);
  } else {
    const start = Date.now();
    const res = http.get(`${CACHE}/${keyIdx}`, { headers: { 'X-Tenant': TENANT }, timeout: '8s' });
    getLatency.add(Date.now() - start);
    const ok = check(res, { 'GET ok': (r) => r.status < 500 });
    if (!ok) errRate.add(1); else errRate.add(0);
  }
  sleep(0.2);
}
