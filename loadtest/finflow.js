// FinFlow load test. Run with the k6 Docker image (see README in this folder).
// Scenarios run in parallel against one backend:
//   payments: create an order and an automatically captured payment (write path, 2 POSTs)
//   reads:    dashboard summary + transactions page (read path)
//   retries:  the same payment POSTed 3 times with one Idempotency-Key (must create exactly one)
//   refunds:  partial refund of a freshly captured payment
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const DURATION = __ENV.DURATION || '60s';
const PAYMENT_RATE = Number(__ENV.PAYMENT_RATE || 20); // payment flows started per second

const duplicatePayments = new Counter('duplicate_payments');
const paymentFlow = new Trend('payment_flow_ms', true);

export const options = {
  scenarios: {
    payments: { executor: 'constant-arrival-rate', rate: PAYMENT_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 50, maxVUs: 200, exec: 'payments' },
    reads: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 20, maxVUs: 100, exec: 'reads' },
    retries: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 10, maxVUs: 50, exec: 'retries' },
    refunds: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 10, maxVUs: 50, exec: 'refunds' },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'duplicate_payments': ['count==0'],
    // Generous bounds; they exist mainly so k6 reports each endpoint's latency separately.
    'http_req_duration{name:POST /orders}': ['p(95)<2000'],
    'http_req_duration{name:POST /payments}': ['p(95)<5000'],
    'http_req_duration{name:POST /refunds}': ['p(95)<5000'],
    'http_req_duration{name:GET /dashboard/summary}': ['p(95)<2000'],
    'http_req_duration{name:GET /payments}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Each VU fetches the demo key once. Not done in setup(): k6 writes setup() data into the summary
// export, and an API key does not belong in committed result files.
let apiKey;
function key() {
  if (!apiKey) {
    const res = http.post(`${BASE}/api/v1/demo/session`);
    check(res, { 'demo session': (r) => r.status === 200 });
    apiKey = res.json('apiKey');
  }
  return apiKey;
}

// Every failed response is logged, so a non-zero error rate can be explained rather than guessed at.
function logFailure(res, name) {
  if (res.status === 0 || res.status >= 400) {
    console.warn(`FAILED ${name} status=${res.status} error=${res.error || ''} body=${String(res.body || '').slice(0, 160)}`);
  }
  return res;
}

function headers(key, idem) {
  const h = { 'Content-Type': 'application/json', 'X-API-Key': key };
  if (idem) h['Idempotency-Key'] = idem;
  return { headers: h };
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function createOrder(key) {
  const res = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    merchantReference: `LT-${uuid().slice(0, 18)}`, amountMinor: 1000 + Math.floor(Math.random() * 9000),
    currency: 'USD', description: 'k6 load test',
  }), Object.assign(headers(key), { tags: { name: 'POST /orders' } }));
  logFailure(res, 'POST /orders');
  check(res, { 'order 201': (r) => r.status === 201 });
  return res.json('id');
}

function pay(key, orderId, idem) {
  return logFailure(http.post(`${BASE}/api/v1/payments`, JSON.stringify({ orderId, paymentMethodToken: 'tok_success' }),
    Object.assign(headers(key, idem), { tags: { name: 'POST /payments' } })), 'POST /payments');
}

export function payments() {
  const data = { key: key() };
  const started = Date.now();
  const orderId = createOrder(data.key);
  const res = pay(data.key, orderId, uuid());
  check(res, { 'payment captured': (r) => r.status === 201 && r.json('status') === 'CAPTURED' });
  paymentFlow.add(Date.now() - started);
}

export function reads() {
  const data = { key: key() };
  const a = http.get(`${BASE}/api/v1/dashboard/summary`, Object.assign(headers(data.key), { tags: { name: 'GET /dashboard/summary' } }));
  const b = http.get(`${BASE}/api/v1/payments?size=20`, Object.assign(headers(data.key), { tags: { name: 'GET /payments' } }));
  logFailure(a, 'GET /dashboard/summary');
  logFailure(b, 'GET /payments');
  check(a, { 'dashboard 200': (r) => r.status === 200 });
  check(b, { 'list 200': (r) => r.status === 200 });
}

export function retries() {
  const data = { key: key() };
  const orderId = createOrder(data.key);
  const idem = uuid();
  const ids = new Set();
  for (let i = 0; i < 3; i++) {
    const res = pay(data.key, orderId, idem);
    check(res, { 'retry answered': (r) => r.status === 201 || r.status === 409 });
    if (res.status === 201) ids.add(res.json('id'));
  }
  const list = http.get(`${BASE}/api/v1/payments?orderId=${orderId}`, headers(data.key));
  if (list.json('totalElements') !== 1 || ids.size > 1) duplicatePayments.add(1);
}

export function refunds() {
  const data = { key: key() };
  const orderId = createOrder(data.key);
  const payment = pay(data.key, orderId, uuid());
  const res = http.post(`${BASE}/api/v1/payments/${payment.json('id')}/refunds`, JSON.stringify({ amountMinor: 500 }),
    Object.assign(headers(data.key, uuid()), { tags: { name: 'POST /refunds' } }));
  logFailure(res, 'POST /refunds');
  check(res, { 'refund succeeded': (r) => r.status === 201 && r.json('status') === 'SUCCEEDED' });
}
