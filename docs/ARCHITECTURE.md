# FinFlow architecture

This document describes what is implemented in this repository. Every box in the diagrams is
a real package, table, topic or process. Nothing here is aspirational; see
[Known limitations](#known-limitations) for what is not built.

FinFlow is a **simulation**: the "payment provider" is an in-process simulator with its own
database schema, and no real money or card data is involved.

## 1. System context and components

```mermaid
flowchart LR
    Browser["Browser<br/>React SPA"] -->|"HTTPS, X-API-Key"| API
    Ops["Operator<br/>curl"] -->|"X-Ops-Token"| API
    Prom["Prometheus"] -->|"scrape /actuator/prometheus"| API
    API -->|"OTLP traces (optional)"| Jaeger["Jaeger"]

    subgraph Backend["Spring Boot backend: one deployable, modular monolith"]
        API["REST controllers<br/>security, rate limit, idempotency"]
        Domain["order · payment · refund · ledger"]
        Recon["reconciliation"]
        Outbox["outbox publisher<br/>(scheduled)"]
        Consumers["Kafka consumers<br/>webhook fan-out · timeout recovery · DLT recorder"]
        Worker["webhook delivery worker<br/>(scheduled)"]
        Sim["provider simulator<br/>+ simulated webhook receiver"]
        API --> Domain
        API --> Recon
        Domain -->|"PaymentProvider port"| Sim
        Recon --> Sim
        Worker -->|"HTTP POST, HMAC-signed"| Sim
    end

    Domain -->|"state + history + ledger + outbox<br/>in ONE transaction"| PG[("PostgreSQL<br/>source of truth")]
    Sim -->|"own schema provider_sim,<br/>independent commits"| PG
    Outbox -->|"poll PENDING rows"| PG
    Outbox -->|"produce"| Kafka[("Kafka<br/>finflow.payment-events<br/>+ .dlt")]
    Kafka --> Consumers
    Consumers --> PG
    Worker --> PG
    API -->|"rate limits, API-key cache,<br/>dashboard cache (fail-open)"| Redis[("Redis<br/>optional")]
```

Everything runs in one JVM. The Kafka consumers, the outbox publisher and the webhook worker
are separate threads in the same process, so they share the deployable but not the request path.
This keeps the system easy to run and debug while still exercising real asynchronous boundaries
(a broker, at-least-once delivery, consumer groups).

| Package | Responsibility |
|---|---|
| `security`, `merchant`, `ratelimit` | API-key auth (SHA-256 hashed keys, Redis-cached lookup), ops-token auth, per-merchant and per-IP rate limits |
| `idempotency` | `idempotency_records`: key, operation, request fingerprint, stored response, in-flight lease |
| `order` | Orders (`OPEN → PAID`), unique merchant reference |
| `payment` | Payment aggregate and state machine, transaction history, orchestration against the provider |
| `refund` | Refund eligibility (`RefundPolicy`), reserve → provider → confirm flow |
| `ledger` | Double-entry journals, reversals, balances. Balance and immutability also enforced by DB triggers |
| `outbox` | Outbox writer (same transaction) and publisher (to Kafka) |
| `events` | Event envelope, Kafka error handling and DLT, timeout-recovery consumer, dead-letter recorder |
| `webhook` | Endpoints, fan-out consumer, delivery worker with retries, signing, simulated receiver |
| `provider` | `PaymentProvider` port, simulator with durable records in schema `provider_sim`, metered decorator |
| `reconciliation` | Pure matcher + service that repairs safe cases and writes an append-only report |
| `dashboard` | KPIs computed with SQL, cached briefly in Redis |
| `ops` | Operator endpoints: dead-letter replay, outbox re-queue, ledger reversal, reconciliation trigger |

## 2. Payment lifecycle

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> AUTHORIZED
    CREATED --> DECLINED
    CREATED --> FAILED: provider unavailable / not found at provider
    CREATED --> TIMED_OUT: no response
    TIMED_OUT --> AUTHORIZED: sync / auto-recovery / reconciliation
    TIMED_OUT --> DECLINED
    TIMED_OUT --> FAILED
    AUTHORIZED --> CAPTURED
    AUTHORIZED --> VOIDED
    CAPTURED --> PARTIALLY_REFUNDED
    CAPTURED --> REFUNDED
    PARTIALLY_REFUNDED --> PARTIALLY_REFUNDED
    PARTIALLY_REFUNDED --> REFUNDED
```

The table lives in `PaymentStatus`. A partial unique index on `payments(order_id)` allows only
one payment per order in `CREATED, AUTHORIZED, TIMED_OUT, CAPTURED, PARTIALLY_REFUNDED, REFUNDED`.
That is the double-charge guard, and `TIMED_OUT` is included because its outcome is unknown.

## 3. Creating a payment

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as PaymentController
    participant I as IdempotencyService
    participant S as PaymentService
    participant DB as PostgreSQL
    participant P as Provider (simulator)

    C->>API: POST /payments (Idempotency-Key K)
    API->>I: execute(K, fingerprint)
    I->>DB: INSERT idempotency_records ... ON CONFLICT DO NOTHING
    alt key exists, same fingerprint, COMPLETED
        I-->>C: stored status + body, Idempotent-Replayed: true
    else key exists, still running
        I-->>C: 409 IDEMPOTENCY_REQUEST_IN_PROGRESS
    else key exists, different request
        I-->>C: 409 IDEMPOTENCY_KEY_REUSED
    end
    I->>S: create()
    S->>DB: TX1: lock order, check, INSERT payment CREATED<br/>+ payment_events + outbox_events
    Note over S,P: no database transaction or lock is held here
    S->>P: authorize(paymentId) (idempotent on paymentId)
    P->>DB: provider_sim.transactions (own transaction)
    P-->>S: approved / declined / timeout / unavailable
    S->>DB: TX2: SELECT ... FOR UPDATE, state machine check,<br/>AUTHORIZED|DECLINED|TIMED_OUT|FAILED + history + outbox
    opt captureMode = AUTOMATIC and AUTHORIZED
        S->>P: capture(providerReference) (idempotent)
        S->>DB: TX3: CAPTURED + order PAID + ledger journal + history + outbox
    end
    S-->>I: payment
    I->>DB: UPDATE idempotency_records SET COMPLETED, response
    I-->>C: 201 Created
```

If the process dies between the provider call and TX2/TX3, the provider has a record that FinFlow
lacks. That gap is closed by the timeout-recovery consumer (for `TIMED_OUT`) and by reconciliation
(for `CREATED`, `AUTHORIZED`-but-captured and `PENDING` refunds). See §7.

## 4. Transactional outbox → Kafka → consumers

```mermaid
flowchart LR
    subgraph TX["One database transaction"]
        S["payment state change"] --> H["payment_events row"]
        S --> L["ledger_entries (if money moved)"]
        S --> O["outbox_events row PENDING<br/>(event id = dedup key)"]
    end
    O -->|"poll every 500 ms<br/>advisory lock: one publisher<br/>FOR UPDATE SKIP LOCKED"| Pub["OutboxPublisher<br/>pipelined batch send,<br/>acks=all, idempotent producer"]
    Pub -->|"key = payment id"| T[("finflow.payment-events<br/>3 partitions")]
    Pub -->|"on ack"| M["UPDATE status = PUBLISHED"]
    T --> F["webhook fan-out<br/>INSERT ... ON CONFLICT<br/>(endpoint_id, event_id) DO NOTHING"]
    T --> R["timeout recovery<br/>state machine makes it idempotent"]
    F -. "3 retries with backoff,<br/>poison: immediately" .-> DLT[("finflow.payment-events.dlt")]
    R -.-> DLT
    DLT --> D["DLT recorder → dead_letters table"]
    D -->|"POST /api/ops/dead-letters/{id}/replay"| T
```

**Delivery guarantees, stated precisely:**

- The business change and its event commit atomically (same PostgreSQL transaction).
- Publishing is **at-least-once**. A crash after Kafka acknowledges but before the row is marked
  `PUBLISHED` re-sends the event. Tested: `FailureInjectionIntegrationTest`.
- Consumers are **idempotent** (unique keys or the payment state machine), so a duplicate has no
  additional effect. Together this gives *effectively-once processing for these specific effects*.
  It is **not** end-to-end exactly-once: a webhook receiver can still see the same event more than
  once and must deduplicate on `FinFlow-Event-Id`.
- **Ordering:** events are keyed by payment id, so one payment's events share a partition and
  normally arrive in order. Not guaranteed when a send fails and is retried later (the publisher
  pipelines batches). No consumer depends on order; each event carries a full payment snapshot and
  its `createdAt`.
- A Kafka outage does not affect the API. Events wait in the outbox (`finflow_outbox_lag_seconds`)
  and publish when the broker returns. Tested by pausing the Kafka container.

## 5. Refunds and the ledger

```mermaid
sequenceDiagram
    autonumber
    participant API as RefundController (+ idempotency)
    participant R as RefundService
    participant DB as PostgreSQL
    participant P as Provider

    API->>R: refund(paymentId, amount)
    R->>DB: TX1: SELECT payment FOR UPDATE<br/>pending = SUM(PENDING refunds)<br/>RefundPolicy: status, 180-day window,<br/>amount ≤ captured − refunded − pending<br/>INSERT refund PENDING (the reservation)
    R->>P: refund(refundId) (idempotent on refundId)
    alt provider ok
        R->>DB: TX2: lock payment, refund SUCCEEDED,<br/>payment PARTIALLY_REFUNDED / REFUNDED,<br/>journal: Dr MERCHANT_BALANCE / Cr PROVIDER_RECEIVABLE,<br/>history + outbox
    else provider unavailable
        R->>DB: TX2: refund FAILED (reservation released), REFUND_FAILED history
    end
```

The `PENDING` row under the payment's row lock is what stops concurrent refunds from exceeding the
captured amount (tested with 8 parallel refunds).

### Ledger model

| Event | Debit | Credit |
|---|---|---|
| Capture | `PROVIDER_RECEIVABLE` (asset: the provider owes us) | `MERCHANT_BALANCE` (liability: we owe the merchant) |
| Refund | `MERCHANT_BALANCE` | `PROVIDER_RECEIVABLE` |
| Reversal (correction) | mirror of the original journal, every direction swapped | |

Invariants and where they are enforced:

| Invariant | Java | PostgreSQL |
|---|---|---|
| Every journal balances, one currency | `Journal` constructor | deferred constraint trigger `trg_ledger_journal_balanced`, checked at COMMIT |
| Entries are immutable | `@Immutable` entity | trigger `trg_ledger_append_only` rejects UPDATE/DELETE/TRUNCATE |
| A capture/refund is posted once | flow design | partial unique indexes `ux_ledger_capture_once`, `ux_ledger_refund_once` |
| A journal is reversed at most once | `LedgerService.reverse` | `ux_ledger_reversal_once` |
| Ledger moves with the state change | MANDATORY propagation | same transaction |

**Ledger vs payment status.** The payment row is the *current state* of one business object and
is updated in place (`CAPTURED` → `PARTIALLY_REFUNDED`). The ledger is an *append-only record of
money movements* between accounts. It is never updated, it sums to zero, and balances are derived
from it. If they disagree (e.g. a capture journal was reversed but the payment still says
`CAPTURED`), reconciliation reports `LEDGER_MISMATCH`.

## 6. Webhook delivery

```mermaid
stateDiagram-v2
    [*] --> PENDING: fan-out (one per event and endpoint)
    PENDING --> IN_PROGRESS: claimed (SKIP LOCKED + 30 s lease)
    RETRYING --> IN_PROGRESS: due
    IN_PROGRESS --> IN_PROGRESS: lease expired (worker crashed), reclaimed
    IN_PROGRESS --> SUCCEEDED: 2xx
    IN_PROGRESS --> RETRYING: non-2xx / timeout, schedule 2s,10s,30s,2m,10m
    IN_PROGRESS --> FAILED: schedule exhausted (6 attempts)
    FAILED --> PENDING: manual redeliver
    SUCCEEDED --> PENDING: manual redeliver
```

Each request carries `FinFlow-Event-Id` (dedup key), `FinFlow-Delivery-Id`, `FinFlow-Event-Type`
and `FinFlow-Signature: t=<unix>,v1=HMAC-SHA256(secret, t + "." + body)`. Every attempt is stored
in `webhook_delivery_attempts`. In the public demo, endpoints can only target the built-in
simulated receiver (external URLs need an operator allowlist, to prevent SSRF). Redirects are not
followed.

## 7. Reconciliation

```mermaid
flowchart TD
    Start["run(merchant): manual, scheduled (10 min) or ops"] --> Lock{"INSERT run RUNNING<br/>unique per merchant"}
    Lock -- "already running" --> C409["409 RECONCILIATION_IN_PROGRESS"]
    Lock --> Load["load provider_sim.transactions (provider's report)<br/>+ payments, refunds, ledger net per payment"]
    Load --> Match["ReconciliationMatcher (pure function)<br/>skip records younger than stale-after (60 s)"]
    Match --> Each{"for each discrepancy"}
    Each -- "recoverable" --> Repair["repair with an existing idempotent operation:<br/>resolveFromProvider · capture · confirm refund · fail refund"]
    Repair --> Rec["finding AUTO_RESOLVED / RESOLUTION_FAILED"]
    Each -- "unrecoverable" --> Open["finding OPEN (needs a human)"]
    Rec --> Write["append reconciliation_findings, finish run"]
    Open --> Write
    Write --> Report["GET /reconciliation/runs/{id} · report.csv"]
```

| Finding | Example cause | Handling |
|---|---|---|
| `UNRESOLVED_PAYMENT` | timeout, or crash after the provider approved | auto: look up at provider, apply |
| `STATUS_MISMATCH` (AUTHORIZED here, captured there) | crash after a successful capture | auto: re-run capture (idempotent at provider) |
| `PENDING_REFUND` | crash after provider refund / before provider call | auto: confirm, or fail and release |
| `DUPLICATE_PROVIDER_CAPTURE` | provider charged twice | open: refund the duplicate at the provider |
| `MISSING_AT_PROVIDER` | provider lost a capture | open: investigate |
| `ORPHAN_PROVIDER_TRANSACTION` | provider charge with no FinFlow payment | open: investigate / refund |
| `STATUS_MISMATCH` (DECLINED/FAILED here, authorized there) | funds held for a "failed" payment | open: void at provider |
| `AMOUNT_MISMATCH` | captured/refunded amounts differ | open |
| `LEDGER_MISMATCH` | ledger receivable ≠ captured − refunded | open: correct with a reversal |

The demo seeder injects one duplicate capture, one lost capture and one orphan capture into the
provider's records (flagged in `provider_sim.transactions.injected_anomaly`), so the report always
has unrecoverable findings to show.

## 8. Failure recovery scenarios (all covered by tests)

```mermaid
flowchart LR
    subgraph Crash["Process dies after provider success"]
        A1["payment CREATED, provider AUTHORIZED"] -->|"reconciliation"| A2["resolveFromProvider → AUTHORIZED → CAPTURED"]
        B1["payment AUTHORIZED, provider CAPTURED"] -->|"reconciliation"| B2["capture() again: provider idempotent → CAPTURED + ledger"]
        C1["refund PENDING, provider REFUNDED"] -->|"reconciliation"| C2["confirm → SUCCEEDED + ledger"]
    end
    subgraph Timeout["Provider timeout"]
        T1["TIMED_OUT (blocks new payments for the order)"] -->|"Kafka consumer after 5 s,<br/>manual sync, or reconciliation"| T2["lookup → AUTHORIZED / DECLINED / FAILED"]
    end
    subgraph Infra["Infrastructure"]
        K1["Kafka down"] --> K2["API unaffected; outbox accumulates; publishes on recovery"]
        R1["Redis down/slow"] --> R2["fail-open: rate limits off, caches bypassed; 250 ms timeouts"]
        W1["webhook worker dies mid-delivery"] --> W2["lease expires → reclaimed → receiver dedups"]
        P1["poison message"] --> P2["DLT → dead_letters → operator replay"]
    end
```

| Scenario | Test |
|---|---|
| 12 identical concurrent payment requests → 1 payment, 1 provider authorization | `IdempotencyIntegrationTest` |
| Retry while the first request is still waiting on a timeout | `IdempotencyIntegrationTest` |
| Outbox write fails → payment, ledger and event all roll back | `FailureInjectionIntegrationTest` |
| Kafka paused → API works, events publish after recovery | `FailureInjectionIntegrationTest` |
| Crash after Kafka ack, before marking published → no duplicate effects | `FailureInjectionIntegrationTest` |
| Consumer stopped and restarted → no lost events | `EventPipelineIntegrationTest` |
| Duplicate Kafka delivery → no duplicate webhook | `EventPipelineIntegrationTest` |
| Webhook worker crash → lease reclaim | `EventPipelineIntegrationTest` |
| Redis paused → payments, refunds, ledger still correct | `RedisOutageIntegrationTest` |
| Unbalanced journal / UPDATE on ledger rejected by the database | `LedgerIntegrityIntegrationTest` |

## 9. Redis usage

| Use | Key / TTL | Invalidation | If Redis is unavailable |
|---|---|---|---|
| Rate limiting (fixed window, Lua INCR+PEXPIRE) | `rl:{bucket}:{subject}:{window}` / 1 min | expiry | fail-open: requests allowed, `finflow_redis_failures_total` incremented |
| API key → merchant | `cache:apikey:{sha256}` / 5 min | evicted on key rotation; TTL bounds staleness | read from PostgreSQL |
| Dashboard summary | `cache:dashboard:{merchantId}` / 10 s | evicted after any payment change commits | computed from PostgreSQL |

Redis never holds payment, refund, ledger or idempotency state; uniqueness and financial invariants
are enforced only by PostgreSQL. Lettuce timeouts are 250 ms, so a hung Redis slows requests
slightly but cannot stall them (measured in `RedisOutageIntegrationTest`). Redis is excluded from the
readiness probe. Trade-off: if an eviction fails during an outage, a rotated API key can stay valid
for up to the 5-minute TTL.

## 10. Observability

- **Logs:** `requestId` (from `X-Request-Id` or generated) and `traceId` in every line. The request id
  travels into outbox rows, Kafka headers and webhook deliveries, so one id follows a payment
  through the async path. `FINFLOW_LOG_FORMAT=ecs` switches to JSON.
- **Traces:** Micrometer Tracing with the OpenTelemetry bridge: spans for HTTP requests and for Kafka
  produce/consume (observation enabled on the template and listeners), so a trace links the API
  call to the consumer that processed its event. Export via OTLP to Jaeger when `FINFLOW_TRACING_EXPORT_ENABLED=true`.
- **Metrics** (`/actuator/prometheus`): `finflow_payment_events_total{type}`,
  `finflow_payment_processing_seconds`, `finflow_provider_latency_seconds{operation,outcome}`,
  `finflow_outbox_pending|failed|lag_seconds`, `finflow_kafka_consumer_retries|dead_lettered|duplicates_total`,
  Kafka client lag (`kafka_consumer_fetch_manager_records_lag_max`),
  `finflow_webhook_attempts_total{outcome}`, `finflow_webhook_retries_scheduled_total`,
  `finflow_webhook_deliveries_failed_total`, `finflow_webhook_deliveries_due`,
  `finflow_reconciliation_runs|findings_total`, `finflow_idempotency_requests_total{result}`,
  `finflow_redis_failures_total`, `finflow_ratelimit_rejected_total`.
- **Health:** `/actuator/health/liveness`, `/actuator/health/readiness` (readiness = database only).
- **Local stack:** `docker compose --profile monitoring up -d` → Grafana on :3000 with the provisioned
  "FinFlow overview" dashboard, Prometheus on :9090, Jaeger on :16686.

## 11. Deployment view

**Not deployed publicly.** This is the target topology, verified locally with
`deploy/docker-compose.prod.yml` (see [PROJECT_GUIDE.md §15](PROJECT_GUIDE.md#15-deployment)).

```mermaid
flowchart LR
    User["Browser"] -->|"HTTPS :443"| Caddy["Caddy<br/>automatic TLS, HSTS<br/>404 for /api/ops, /actuator"]
    subgraph VM["Single VM (Docker Compose)"]
        subgraph Edge["edge network"]
            Caddy --> Web["frontend<br/>nginx: SPA + /api proxy, CSP"]
        end
        subgraph Internal["internal network (no internet)"]
            Web --> BE["backend<br/>API + outbox + consumers + workers"]
            BE --> PG[("PostgreSQL<br/>volume")]
            BE --> KF[("Kafka<br/>volume")]
            BE --> RD[("Redis")]
        end
    end
    GH["GitHub Actions"] -->|"CI: tests, gitleaks,<br/>multi-arch images"| GHCR[("GHCR")]
    GH -->|"manual Deploy workflow:<br/>SSH → backup, pull, up, smoke test, rollback"| VM
    GHCR -->|"docker pull"| VM
```

## Known limitations

- One provider (the simulator); no routing or failover between providers.
- The outbox publisher, consumers and worker run inside the API process. They can be split into a
  separate worker deployment by configuration, but that is not packaged.
- Reconciliation loads a merchant's full history into memory; a real system would reconcile per
  settlement period.
- Webhook signing secrets are stored in plaintext (needed for HMAC); production would encrypt them.
- Full capture only; no fees, FX, settlement or payouts in the ledger.
- Refund timeouts are treated as failures (the simulator never produces them).
