import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const LB = `http://${__ENV.LB_IP}:8080`;
const CACHE = `${LB}/api/v1/cache`;
const TENANT = 'perf-volume';

const t1k   = new Trend('vol_1kb_latency',   true);
const t50k  = new Trend('vol_50kb_latency',  true);
const t200k = new Trend('vol_200kb_latency', true);
const t1m   = new Trend('vol_1mb_latency',   true);
const t2m   = new Trend('vol_2mb_latency',   true);
const errRate = new Rate('vol_error_rate');

const SCENARIOS = [
  { label: '1KB',   bytes: 1024,        trend: t1k   },
  { label: '50KB',  bytes: 51200,       trend: t50k  },
  { label: '200KB', bytes: 204800,      trend: t200k },
  { label: '1MB',   bytes: 1048576,     trend: t1m   },
  { label: '2MB',   bytes: 2 * 1048576, trend: t2m   },
];

export const options = {
  vus: 5,
  iterations: 50,
  thresholds: {
    'vol_error_rate': ['rate<0.02'],
  },
};

export default function () {
  for (const s of SCENARIOS) {
    const key = `vol-${s.label}-${__VU}-${__ITER}`;
    const start = Date.now();
    const res = http.put(`${CACHE}/${key}`, 'x'.repeat(s.bytes), {
      headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/octet-stream', 'X-TTL-MS': '300000' },
      timeout: '30s'
    });
    s.trend.add(Date.now() - start);

    const ok = check(res, { [`${s.label} status 200`]: (r) => r.status === 200 });
    if (!ok) {
      errRate.add(1);
      console.log(`FAIL ${s.label}: status=${res.status} body=${res.body.substring(0, 200)}`);
    } else {
      errRate.add(0);
    }
    sleep(0.2);
  }
}
