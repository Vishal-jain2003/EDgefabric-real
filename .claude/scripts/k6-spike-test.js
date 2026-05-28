import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-spike';

const latency = new Trend('spike_latency', true);
const errRate = new Rate('spike_error_rate');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 300; i++) {
    http.put(`${CACHE}/spike-seed-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '1800000' }
    });
  }
}

export const options = {
  stages: [
    { duration: '2m',  target: 30  },
    { duration: '10s', target: 400 },
    { duration: '1m',  target: 400 },
    { duration: '10s', target: 30  },
    { duration: '2m',  target: 30  },
    { duration: '10s', target: 600 },
    { duration: '1m',  target: 600 },
    { duration: '10s', target: 30  },
    { duration: '2m',  target: 30  },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    'spike_error_rate': ['rate<0.10'],
  },
};

export default function () {
  const start = Date.now();
  let res;

  if (Math.random() < 0.25) {
    res = http.put(`${CACHE}/spike-put-${__VU}-${__ITER}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '120000' },
      timeout: '5s'
    });
  } else {
    res = http.get(`${CACHE}/spike-seed-${Math.floor(Math.random() * 300)}`, {
      headers: { 'X-Tenant': TENANT },
      timeout: '5s'
    });
  }

  latency.add(Date.now() - start);
  const ok = check(res, { 'ok': (r) => r.status < 500 });
  if (!ok) errRate.add(1); else errRate.add(0);
  sleep(0.05);
}
