/*
Run with three direct replica ports and a fresh CLIENT_IP. With six requests,
each replica receives only two; a 429 proves the five-request budget is shared:
  docker run --rm -i -e GATEWAY_URLS=http://host.docker.internal:<port-1>,http://host.docker.internal:<port-2>,http://host.docker.internal:<port-3> -e CLIENT_IP=203.0.113.210 -e VUS=1 -e REQUESTS=6 -e RATE=10 -e DURATION=10s grafana/k6 run - < load-tests/rate-limit-burst-test.js
*/
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const gatewayUrls = (__ENV.GATEWAY_URLS || '')
  .split(',')
  .map((url) => url.trim().replace(/\/$/, ''))
  .filter(Boolean);
const clientIp = __ENV.CLIENT_IP || '203.0.113.200';
const vus = Number.parseInt(__ENV.VUS || '1', 10);
const totalRequests = Number.parseInt(__ENV.REQUESTS || '6', 10);
const rate = Number.parseFloat(__ENV.RATE || '10');
const duration = __ENV.DURATION || '10s';
const rateLimitedResponses = new Rate('rate_limited_responses');

export const options = {
  scenarios: {
    distributed_rate_limit_burst: {
      executor: 'shared-iterations',
      vus,
      iterations: totalRequests,
      maxDuration: duration,
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    rate_limited_responses: ['rate>0'],
  },
};

export function setup() {
  if (gatewayUrls.length < 3) {
    throw new Error('Set GATEWAY_URLS to three direct gateway replica URLs.');
  }
}

export default function () {
  const replicaIndex = (__VU + __ITER - 1) % gatewayUrls.length;
  const response = http.get(`${gatewayUrls[replicaIndex]}/api/products`, {
    headers: {
      // One identity across replicas proves that Redis owns the shared budget.
      'X-Forwarded-For': clientIp,
    },
    tags: {
      route: '/api/products',
      replica: String(replicaIndex + 1),
    },
  });

  const isExpectedStatus = response.status === 200 || response.status === 429;
  const isRateLimited = response.status === 429;

  rateLimitedResponses.add(isRateLimited);
  check(response, {
    'response is allowed or rate limited': () => isExpectedStatus,
  });

  // Pacing is configurable while REQUESTS keeps the distributed proof bounded.
  sleep(vus / rate);
}
