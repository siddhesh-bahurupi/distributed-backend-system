/*
Run after creating a product with available inventory:
  docker run --rm -i -e BASE_URL=http://host.docker.internal:<gateway-port> -e PRODUCT_ID=1 -e VUS=5 -e RATE=3 -e DURATION=30s grafana/k6 run - < load-tests/orders-load-test.js
*/
import http from 'k6/http';
import { check } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '');
const productId = Number.parseInt(__ENV.PRODUCT_ID || '1', 10);
const quantity = Number.parseInt(__ENV.QUANTITY || '1', 10);
const vus = Number.parseInt(__ENV.VUS || '5', 10);
const rate = Number.parseInt(__ENV.RATE || '3', 10);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    orders: {
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
  const response = http.post(
    `${baseUrl}/api/orders`,
    JSON.stringify({ productId, quantity }),
    {
      headers: {
        'Content-Type': 'application/json',
        // Keep order creation load separate from the deliberate burst scenario.
        'X-Forwarded-For': `k6-orders-${__VU}-${__ITER}`,
      },
      tags: { route: '/api/orders' },
    },
  );

  check(response, {
    'order was created': (result) => result.status === 201,
  });
}
