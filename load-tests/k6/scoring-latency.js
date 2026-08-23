import http from 'k6/http';
import { check, sleep } from 'k6';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const SCORING = __ENV.SCORING_URL || 'http://localhost:8000';

export const options = {
  vus: 20,
  duration: '45s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200', 'p(99)<400'],
  },
};

export default function () {
  const payload = JSON.stringify({
    transaction_id: `k6-${__VU}-${__ITER}`,
    user_id: `user_${__VU}`,
    amount: 1200,
    merchant_category: 'food',
    location: 'Pune',
    device_id: `dev_user_${__VU}`,
    channel: 'upi',
    timestamp: new Date().toISOString(),
    tx_count: 25,
    avg_amount: 900,
    std_amount: 180,
    tx_last_5m: 1,
    is_new_device: false,
  });

  const res = http.post(`${SCORING}/score`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'scored': (r) => r.status === 200,
    'has score': (r) => r.json('risk_score') !== undefined,
  });

  sleep(0.02);
}
