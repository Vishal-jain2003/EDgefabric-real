import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-scale';

const latency  = new Trend('scale_latency', true);
const errRate  = new Rate('scale_error_rate');
const reqCount = new Counter('scale_requests');

export function setup() {
  const payload = 'x'.repeat(1024);
  for (let i = 0; i < 500; i++) {
    http.put(`${CACHE}/scale-seed-${i}`, payload, {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '3600000' }
    });
  }
}

export const options = {
  stages: [
    { duration: '1m',  target: 25  },
    { duration: '3m',  target: 25  },
    { duration: '30s', target: 50  },
    { duration: '3m',  target: 50  },
    { duration: '30s', target: 100 },
    { duration: '3m',  target: 100 },
    { duration: '30s', target: 200 },
    { duration: '3m',  target: 200 },
    { duration: '30s', target: 400 },
    { duration: '3m',  target: 400 },
    { duration: '1m',  target: 0   },
  ],
  thresholds: {
    'scale_error_rate': ['rate<0.01'],
  },
};

export default function () {
  const isWrite = Math.random() < 0.30;
  const start = Date.now();
  let res;

  if (isWrite) {
    res = http.put(`${CACHE}/scale-put-${__VU}-${__ITER}`, 'x'.repeat(1024), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '120000' }
    });
  } else {
    res = http.get(`${CACHE}/scale-seed-${Math.floor(Math.random() * 500)}`, {
      headers: { 'X-Tenant': TENANT }
    });
  }

  latency.add(Date.now() - start);
  reqCount.add(1);
  const ok = check(res, { 'ok': (r) => r.status < 500 });
  if (!ok) errRate.add(1); else errRate.add(0);
  sleep(0.1);
}
