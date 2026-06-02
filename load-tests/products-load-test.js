/*
Run:
  docker run --rm -i -e BASE_URL=http://host.docker.internal:<gateway-port> -e VUS=5 -e RATE=5 -e DURATION=30s grafana/k6 run - < load-tests/products-load-test.js
*/
import http from 'k6/http';
import { check } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '');
const vus = Number.parseInt(__ENV.VUS || '5', 10);
const rate = Number.parseInt(__ENV.RATE || '5', 10);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    products: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: vus,
      maxVUs: vus,
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/products`, {
    headers: {
      // Keep this workload focused on product reads rather than limiter saturation.
      'X-Forwarded-For': `k6-products-${__VU}-${__ITER}`,
    },
    tags: { route: '/api/products' },
  });

  check(response, {
    'products request succeeded': (result) => result.status === 200,
  });
}
