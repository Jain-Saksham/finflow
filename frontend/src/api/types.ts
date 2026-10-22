// Mirrors the backend DTOs. Money is always an integer in minor units plus a currency code.

export type PaymentStatus =
  | 'CREATED'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED'
  | 'DECLINED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'VOIDED';

export const PAYMENT_STATUSES: PaymentStatus[] = [
  'CREATED',
  'AUTHORIZED',
  'CAPTURED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'DECLINED',
  'FAILED',
  'TIMED_OUT',
  'VOIDED',
];

export type CaptureMode = 'AUTOMATIC' | 'MANUAL';
export type PaymentAction = 'CAPTURE' | 'VOID' | 'REFUND' | 'SYNC';
export type OrderStatus = 'OPEN' | 'PAID';
export type RefundStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';
export type Currency = 'USD' | 'EUR' | 'GBP' | 'INR';
export const CURRENCIES: Currency[] = ['USD', 'EUR', 'GBP', 'INR'];

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Merchant {
  id: string;
  name: string;
  defaultCurrency: string;
  demo: boolean;
  apiKeyPrefix: string;
  createdAt: string;
}

export interface DemoSession {
  apiKey: string;
  merchantId: string;
  merchantName: string;
  simulation: boolean;
  notice: string;
}

export interface Order {
  id: string;
  merchantReference: string;
  amountMinor: number;
  currency: string;
  description: string | null;
  status: OrderStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Payment {
  id: string;
  orderId: string;
  amountMinor: number;
  currency: string;
  status: PaymentStatus;
  captureMode: CaptureMode;
  paymentMethodToken: string;
  provider: string;
  providerReference: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  capturedAmountMinor: number;
  refundedAmountMinor: number;
  refundableAmountMinor: number;
  availableActions: PaymentAction[];
  authorizedAt: string | null;
  capturedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentEvent {
  id: number;
  type: string;
  fromStatus: PaymentStatus | null;
  toStatus: PaymentStatus;
  amountMinor: number | null;
  detail: string | null;
  createdAt: string;
}

export interface Refund {
  id: string;
  paymentId: string;
  amountMinor: number;
  currency: string;
  status: RefundStatus;
  reason: string | null;
  providerReference: string | null;
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export type LedgerAccount = 'PROVIDER_RECEIVABLE' | 'MERCHANT_BALANCE';

export interface LedgerEntry {
  id: number;
  journalId: string;
  journalType: 'CAPTURE' | 'REFUND';
  paymentId: string;
  refundId: string | null;
  account: LedgerAccount;
  direction: 'DEBIT' | 'CREDIT';
  amountMinor: number;
  currency: string;
  description: string;
  createdAt: string;
}

export interface LedgerBalances {
  accounts: {
    account: LedgerAccount;
    currency: string;
    debitsMinor: number;
    creditsMinor: number;
    balanceMinor: number;
  }[];
  checks: { currency: string; totalDebitsMinor: number; totalCreditsMinor: number; balanced: boolean }[];
}

export interface Scenario {
  token: string;
  label: string;
  description: string;
  outcome: 'APPROVE' | 'DECLINE' | 'TIMEOUT' | 'UNAVAILABLE';
}

export interface Scenarios {
  scenarios: Scenario[];
  latencyMs: number;
  timeoutMs: number;
  outage: boolean;
}

export interface DailyPoint {
  date: string;
  succeeded: number;
  failed: number;
  pending: number;
}

export interface DashboardSummary {
  totalPayments: number;
  totalOrders: number;
  statusCounts: Record<PaymentStatus, number>;
  authorizationRatePercent: number | null;
  volumes: { currency: string; capturedMinor: number; refundedMinor: number; netMinor: number; capturedCount: number }[];
  daily: DailyPoint[];
  failureReasons: { code: string; count: number }[];
  recentPayments: Payment[];
}

export type SimulatedBehavior = 'ALWAYS_SUCCEED' | 'FAIL_FIRST_ATTEMPT' | 'FAIL_FIRST_THREE' | 'ALWAYS_FAIL';
export type DeliveryStatus = 'PENDING' | 'IN_PROGRESS' | 'RETRYING' | 'SUCCEEDED' | 'FAILED';

export interface WebhookEndpoint {
  id: string;
  url: string;
  description: string | null;
  enabled: boolean;
  simulatedBehavior: SimulatedBehavior | null;
  secret: string | null;
  secretPreview: string;
  createdAt: string;
}

export interface WebhookDelivery {
  id: string;
  endpointId: string;
  endpointUrl: string;
  eventId: string;
  eventType: string;
  paymentId: string | null;
  status: DeliveryStatus;
  attemptCount: number;
  nextAttemptAt: string | null;
  lastResponseStatus: number | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  succeededAt: string | null;
}

export interface DeliveryAttempt {
  attemptNo: number;
  outcome: 'SUCCEEDED' | 'FAILED';
  responseStatus: number | null;
  responseBody: string | null;
  error: string | null;
  durationMs: number;
  startedAt: string;
}

export interface DeliveryDetail {
  delivery: WebhookDelivery;
  attempts: DeliveryAttempt[];
  maxAttempts: number;
}

export interface ReconciliationRun {
  id: string;
  trigger: 'MANUAL' | 'SCHEDULED' | 'OPS';
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  providerTransactions: number | null;
  internalPayments: number | null;
  matchedPayments: number | null;
  inFlightSkipped: number | null;
  staleAfterSeconds: number;
  findingsTotal: number | null;
  autoResolved: number | null;
  openFindings: number | null;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface ReconciliationFinding {
  id: number;
  type: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  recoverable: boolean;
  resolution: 'AUTO_RESOLVED' | 'OPEN' | 'RESOLUTION_FAILED';
  paymentId: string | null;
  refundId: string | null;
  providerReference: string | null;
  expected: string | null;
  actual: string | null;
  detail: string;
  resolutionDetail: string | null;
  createdAt: string;
}

export interface ReconciliationReport {
  run: ReconciliationRun;
  findings: ReconciliationFinding[];
}
