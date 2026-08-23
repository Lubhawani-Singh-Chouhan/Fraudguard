import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 100,
      stages: [
        { target: 50, duration: '20s' },
        { target: 100, duration: '40s' },
        { target: 20, duration: '10s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<200'],
  },
};

export default function () {
  const user = `load_${randomIntBetween(1, 200)}`;
  const payload = JSON.stringify({
    user_id: user,
    amount: randomIntBetween(200, 120000),
    currency: 'INR',
    merchant_id: 'm_amazon',
    merchant_category: 'ecommerce',
    location: 'Mumbai',
    device_id: `dev_${user}_${randomIntBetween(1, 4)}`,
    channel: 'upi',
    timestamp: new Date().toISOString(),
  });

  const res = http.post(`${GATEWAY}/transactions`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'accepted': (r) => r.status === 202 || r.status === 200,
  });

  sleep(0.05);
}
