# FinFlow project guide

A developer guide to what FinFlow is, how it is built and why, and how to run, test, deploy and
operate it. Diagrams are in [ARCHITECTURE.md](ARCHITECTURE.md); the incident runbook is
[OPERATIONS.md](OPERATIONS.md); load-test details are in [../loadtest/README.md](../loadtest/README.md).

> FinFlow is a **simulation**. It never connects to a real payment provider, never accepts card
> numbers, and moves no money. A startup guard refuses to run with any provider other than the
> built-in simulator.

---

## 1. Problem statement, goals and non-goals

**Problem.** Taking a payment looks like one API call, but the hard parts are everything around it:
a provider that times out after charging the card, a client that retries, two refunds racing each
other, an event that must reach a merchant's server even when it is down, and proving afterwards
that the books match the provider's records. FinFlow is a small, runnable system built to show how
those problems are solved correctly.

**Goals**

- A complete merchant flow: orders → payments (authorize, capture, void) → refunds, with a UI.
- Financial correctness that survives retries, concurrency, crashes and partial outages.
- Real distributed-systems mechanics (transactional outbox, Kafka, webhooks with retries,
  reconciliation) with honest delivery guarantees.
- Every claim backed by an automated test or a measurement.
- Easy to run locally (`docker compose`) and to explain in an interview.

**Non-goals**

- Real payments, card data (PCI DSS scope), KYC, fraud scoring, multi-provider routing.
- Settlement, payouts, fees, FX, partial captures.
- Microservices for their own sake; the modular monolith is deliberate (§4).
- Multi-tenant self-service onboarding (merchants exist; there is no signup).

## 2. Requirements

### Functional

| Requirement | Where |
|---|---|
| Explore a demo merchant from a public page | `POST /api/v1/demo/session`, Welcome page |
| Create orders; pay with simulated success, declines, timeout, provider outage, crash | `OrderController`, `PaymentController`, `SimulationScenario` |
| See state transitions and transaction history | `GET /payments/{id}/events`, payment page timeline |
| Capture / void authorizations; partial and full refunds with eligibility rules | `PaymentService`, `RefundService`, `RefundPolicy` |
| Inspect ledger entries and balances | `LedgerController`, Ledger page |
| Signed webhooks with retries and attempt history | `webhook` package, Webhooks pages |
| Reconciliation against provider records with a report | `reconciliation` package, Reconciliation page, CSV export |
| Dashboard and operational metrics from real data | `DashboardService`, `/actuator/prometheus`, Grafana |

### Non-functional

| Requirement | How it is met |
|---|---|
| No double charge, no double posting, no over-refund under concurrency or retries | DB constraints + row locks + durable idempotency (§8, §9); tests with 8–12 concurrent requests |
| Ledger always balanced | Deferred constraint trigger at COMMIT (§9) |
| Survive provider timeouts and process crashes | Unknown outcomes are recorded as such and resolved from the provider (§5, §11) |
| API available while Kafka or Redis are down | Outbox absorbs Kafka outages; Redis fails open (§10, §11) |
| Observable | Structured logs with request ids, Prometheus metrics, OTLP traces (§13) |
| Secure public demo | Hashed API keys, per-visitor limits, operator functions behind a separate token, SSRF guard, CSP (§12) |
| Reproducible | Pinned image tags, Flyway migrations, Testcontainers, one-command local stack |

## 3. Technology choices

| Choice | Why | Alternative considered |
|---|---|---|
| Java 21, Spring Boot 4.1 | Mature transaction management, Spring Kafka, Micrometer, Testcontainers support; virtual threads make blocking provider calls cheap | Kotlin/Go: fine, but Java is the target interview stack |
| PostgreSQL 17 | Transactions, row locks, partial unique indexes, deferred constraint triggers: the invariants live where the data lives | MySQL lacks deferred constraints and partial indexes |
| Flyway | Versioned, reviewed SQL; `ddl-auto=validate` catches entity/schema drift at startup | Liquibase (heavier), Hibernate auto-DDL (unsafe) |
| Kafka 4.2 (KRaft) | Durable, partitioned, replayable log; consumer groups make independent async workflows natural | RabbitMQ (fine for webhooks, weaker for replay), DB polling only (no fan-out) |
| Redis 8.6 | Atomic counters for rate limiting, TTL caches | Caffeine (per-instance only, no shared limits) |
| React 19 + TypeScript, Vite, TanStack Query | Server-state caching with explicit loading/error states; typed API layer | Next.js (SSR not needed) |
| Testcontainers | Integration tests against the real PostgreSQL, Kafka and Redis, not mocks or H2 | H2/embedded Kafka hide real behaviour (triggers, SKIP LOCKED) |
| Micrometer + Prometheus + OpenTelemetry | Vendor-neutral metrics and traces; Boot auto-instruments HTTP and Kafka | |
| k6 | Scriptable open-model load tests, runs from a container | JMeter, Gatling |
| Caddy (deployment) | Automatic HTTPS with one line of config | nginx + certbot |

## 4. Architecture and modules

One Spring Boot application (a **modular monolith**) plus PostgreSQL, Kafka and Redis. Packages have
one responsibility each and depend in one direction (`refund → payment → order/ledger/provider`).
The outbox publisher, Kafka consumers and webhook worker are background threads in the same JVM.
See [ARCHITECTURE.md §1](ARCHITECTURE.md#1-system-context-and-components) for the component diagram
and the full package table.

**Why not microservices?** Payment, refund and ledger changes must commit atomically. Splitting them
would replace one database transaction with a saga and compensations, adding failure modes without a
scaling need. The natural first extractions, if load demanded it, are the webhook delivery worker
and the Kafka consumers. They already communicate only through the database and Kafka, scale
differently from the API, and can fail without affecting payment correctness.

## 5. Payment lifecycle and state machine

States: `CREATED → AUTHORIZED → CAPTURED → PARTIALLY_REFUNDED → REFUNDED`, plus `DECLINED`,
`FAILED`, `VOIDED` (terminal) and `TIMED_OUT` (unknown outcome). The allowed-transition table is in
[`PaymentStatus`](../backend/src/main/java/com/finflow/payment/PaymentStatus.java); every change goes
through an intention-revealing method on [`Payment`](../backend/src/main/java/com/finflow/payment/Payment.java)
(`authorize`, `capture`, `applyRefund`, …) that validates the transition and returns the history event.
Responses include `availableActions`, so clients never duplicate the rules.

The orchestration rule: **no database transaction is held across a provider call.**

1. TX1: validate, insert the `CREATED` payment (+ history + outbox), commit.
2. Call the provider with no locks held. Calls are idempotent on the payment id.
3. TX2: `SELECT … FOR UPDATE`, re-check the state machine, apply the result, commit.

A **timeout** means the outcome is unknown, so the payment becomes `TIMED_OUT`, not `FAILED`. It keeps
blocking new payments for the order, and is resolved by a provider *lookup*: automatically by the
timeout-recovery Kafka consumer after 5 s, manually via `POST /payments/{id}/sync`, or by
reconciliation. Retrying the authorization could charge twice; a lookup cannot.

## 6. Database schema and constraints

Migrations: [V1](../backend/src/main/resources/db/migration/V1__initial_schema.sql) (core),
[V2](../backend/src/main/resources/db/migration/V2__reliability.sql) (idempotency, outbox, webhooks,
provider records, reconciliation, ledger triggers),
[V3](../backend/src/main/resources/db/migration/V3__demo_reset.sql) (demo reset guard).

| Table | Purpose | Important constraints |
|---|---|---|
| `merchants` | API key hash, demo flag | unique `api_key_hash`; at most one `demo = true` |
| `orders` | Merchant orders | unique `(merchant_id, merchant_reference)`; `amount_minor > 0` |
| `payments` | Payment aggregate | unique `(merchant_id, idempotency_key)`; partial unique `order_id` for active statuses; `refunded ≤ captured ≤ amount`; status `CHECK` |
| `payment_events` | Transition history | identity id gives order |
| `refunds` | Refunds, `PENDING` = reservation | unique `(merchant_id, idempotency_key)` |
| `ledger_entries` | Double-entry ledger | balance trigger (deferred), append-only trigger, capture/refund/reversal posted once |
| `idempotency_records` | Request fingerprints and stored responses | unique `(merchant_id, idempotency_key)` |
| `outbox_events` | Events awaiting Kafka | partial index on `PENDING` |
| `webhook_endpoints` / `_deliveries` / `_delivery_attempts` / `_receipts` | Webhooks | unique `(endpoint_id, event_id)`; unique `(delivery_id, attempt_no)` |
| `dead_letters` | Copy of the Kafka DLT | unique `(topic, partition, offset)` |
| `reconciliation_runs` / `_findings` | Reports (append-only) | one `RUNNING` run per merchant |
| `provider_sim.transactions` | The simulated provider's own records | unique `(type, idempotency_key)`; no FKs into FinFlow |

Money is always `BIGINT` minor units plus a 3-letter currency, never a float.

## 7. API and errors

OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`. Endpoint list and curl examples are in the
[README](../README.md#api-examples). Authentication is `X-API-Key` (merchants) or `X-Ops-Token`
(operators, `/api/ops/**`, not in the public OpenAPI document).

Every error is RFC 9457 `application/problem+json` with a stable `code` and the `requestId`:

```json
{
  "type": "https://finflow.dev/errors/refund-exceeds-refundable",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Requested refund of 99999 exceeds the refundable amount of 1000",
  "code": "REFUND_EXCEEDS_REFUNDABLE",
  "requestId": "6f2c…"
}
```

Validation errors add `"errors": [{"field": "amountMinor", "message": "must be greater than 0"}]`.
Codes include `VALIDATION_FAILED`, `UNAUTHENTICATED`, `NOT_FOUND` (also for other merchants'
resources), `INVALID_STATE_TRANSITION`, `ORDER_HAS_ACTIVE_PAYMENT`, `PAYMENT_NOT_REFUNDABLE`,
`REFUND_EXCEEDS_REFUNDABLE`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_REQUEST_IN_PROGRESS`,
`RATE_LIMITED` (with `Retry-After`), `PROVIDER_UNAVAILABLE`, `PROVIDER_TIMEOUT`, `SIMULATED_CRASH`.

## 8. Idempotency

Required on `POST /payments` and `POST /payments/{id}/refunds`, optional on capture/void.
[`IdempotencyService`](../backend/src/main/java/com/finflow/idempotency/IdempotencyService.java):

1. `INSERT INTO idempotency_records … ON CONFLICT (merchant_id, idempotency_key) DO NOTHING` with
   status `IN_PROGRESS`, a 30 s lease and `SHA-256(operation + normalized request JSON)`.
2. If the row already existed: different operation/fingerprint → `409 IDEMPOTENCY_KEY_REUSED`;
   `COMPLETED` → replay the stored status code and body with `Idempotent-Replayed: true`;
   `IN_PROGRESS` with a live lease → `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`; expired lease (the
   original request crashed) → take it over with a compare-and-set.
3. Run the operation. Success or a business 4xx → store it as `COMPLETED`. A 5xx or transient error →
   delete the record so the same key can be retried.

**Why the key alone is not enough:** an in-memory or cache-only "seen keys" set loses entries on
restart or eviction and has a check-then-act race between instances. The unique constraint makes
"exactly one request owns this key" an atomic database fact. And because records expire after 24 h,
the payment and refund rows also carry the key under their own unique constraint, a permanent second
guard (tested: a crash, then a retry with the same key, returns the original `CREATED` payment).

Tests: 12 identical simultaneous requests → one payment and one provider authorization; a retry while
the first request waits on a provider timeout gets `409 IN_PROGRESS`, then the original result.

## 9. Double-entry ledger

| Event | Debit | Credit |
|---|---|---|
| Capture | `PROVIDER_RECEIVABLE` | `MERCHANT_BALANCE` |
| Refund | `MERCHANT_BALANCE` | `PROVIDER_RECEIVABLE` |
| Reversal | mirror of the original | |

- **Balanced:** `Journal` refuses to construct unbalanced lines, and the deferred constraint trigger
  `trg_ledger_journal_balanced` checks `Σ debits = Σ credits` and a single currency per journal at
  COMMIT, regardless of which code (or SQL session) wrote the rows.
- **Immutable:** `trg_ledger_append_only` rejects UPDATE, DELETE and TRUNCATE. Two narrowly scoped
  exceptions need a transaction-local flag: demo seeding may shift `created_at` only, and the demo
  reset may delete rows of the demo merchant only.
- **No double posting:** partial unique indexes allow one capture journal per payment, one refund
  journal per refund, one reversal per journal.
- **Atomic with the state change:** `LedgerService.post` has MANDATORY propagation. A failure-injection
  test makes the outbox write fail and shows the status change and ledger rows roll back together.
- **Corrections:** `POST /api/ops/merchants/{m}/ledger/journals/{j}/reverse` posts a mirror journal.
- **Ledger vs payment status:** the payment row is mutable current state; the ledger is an append-only
  record of money movement. Reconciliation flags any payment whose ledger receivable ≠
  captured − refunded (`LEDGER_MISMATCH`).

## 10. Transactional outbox and Kafka

Detailed flow and guarantees: [ARCHITECTURE.md §4](ARCHITECTURE.md#4-transactional-outbox--kafka--consumers).

- `PaymentHistory.record` writes the history row and the outbox row in the caller's transaction.
- `OutboxPublisher` polls every 500 ms under a Postgres advisory lock (one active publisher across
  instances), takes up to 500 `PENDING` rows `FOR UPDATE SKIP LOCKED`, sends them pipelined
  (`acks=all`, idempotent producer, `max.block.ms=5000`), and marks the acknowledged ones
  `PUBLISHED`. Failures back off exponentially; after 20 attempts a row becomes `FAILED` (ops can
  re-queue it).
- Topic `finflow.payment-events` (3 partitions, key = payment id) and `finflow.payment-events.dlt`.
- Consumers: **webhook fan-out** (dedup by `(endpoint_id, event_id)`), **timeout recovery**
  (idempotent via the state machine), **DLT recorder** (into `dead_letters`, its own error handler so
  it cannot loop). Retries: 3 with exponential backoff from 500 ms; poison messages skip retries.
- **Guarantee:** at-least-once publish + idempotent consumers. Not end-to-end exactly-once; webhook
  receivers must deduplicate. Per-payment ordering is normal but not guaranteed after a failed send.

## 11. Redis, webhooks and reconciliation

**Redis** ([ARCHITECTURE.md §9](ARCHITECTURE.md#9-redis-usage)): fixed-window rate limits (Lua
INCR + PEXPIRE), API-key cache (5 min TTL, evicted on rotation), dashboard cache (10 s TTL, evicted
after any committed payment change). All fail open with 250 ms timeouts. No financial state, no
idempotency state.

**Webhooks:** one delivery per event and endpoint; a worker claims due deliveries with
`FOR UPDATE SKIP LOCKED` and a 30 s lease, POSTs them concurrently on virtual threads, and records
every attempt. Retry schedule `2s, 10s, 30s, 2m, 10m`, then `FAILED`; manual redelivery starts a new
cycle. Signature: `FinFlow-Signature: t=<unix>,v1=HMAC-SHA256(secret, t + "." + body)`; receivers verify
it in constant time, reject timestamps older than 5 minutes, and **deduplicate on
`FinFlow-Event-Id`** (store processed ids; acknowledge duplicates with 2xx so retries stop). The
built-in simulated receiver does exactly that, and can be told to fail on purpose.

**Reconciliation:** loads the provider's report (`provider_sim.transactions`), payments, refunds and
ledger nets for a merchant; [`ReconciliationMatcher`](../backend/src/main/java/com/finflow/reconciliation/ReconciliationMatcher.java)
(a pure function) lists discrepancies; safe ones are repaired with existing idempotent operations,
the rest stay open. Runs and findings are append-only. Records younger than `stale-after` (60 s) are
skipped because their request may still be in flight. Table of findings:
[ARCHITECTURE.md §7](ARCHITECTURE.md#7-reconciliation).

## 12. Security considerations

- **Simulation only:** startup guard rejects non-simulated providers; payment methods must match
  `^tok_[a-z_]+$`, so card numbers are rejected by validation; UI and API docs carry disclaimers.
- **AuthN:** API keys are 256-bit random values stored as SHA-256 (fast hash is fine for high-entropy
  secrets and allows an indexed lookup). Operators use a separate `X-Ops-Token` compared in constant
  time; without `FINFLOW_OPS_TOKEN` the ops API is disabled. In production Caddy additionally returns
  404 for `/api/ops/*` and `/actuator/*`, so operator functions are reachable only from the host.
- **AuthZ:** every query is scoped by the authenticated merchant id; other merchants' ids return 404.
- **Demo:** one shared demo merchant whose key is handed out publicly; it cannot reach ops functions,
  other merchants' data or external URLs. Rate limits for it are per visitor (merchant + client IP).
  The nightly demo reset touches only `demo = true` data (checked in SQL and in the ledger trigger).
- **Abuse:** per-merchant limits (300 req/min, 60 writes/min), per-IP limits on public endpoints,
  amount caps, max 5 webhook endpoints, request body limit 64 KiB at nginx.
- **SSRF:** webhook URLs must match an operator allowlist (empty by default); redirects are not followed.
- **Web:** strict CSP (`script-src 'self'`), `X-Frame-Options: DENY`, HSTS at Caddy, no stack traces
  in errors, CSV export neutralizes spreadsheet formulas.
- **Proxies:** nginx overwrites `X-Forwarded-For` and trusts it only from Caddy's network, so clients
  cannot spoof their IP to dodge limits.
- **Secrets:** environment variables only; `.env*` git-ignored; gitleaks runs in CI.
- **Known gaps:** webhook secrets stored in plaintext; no per-user accounts or MFA (API keys only).

## 13. Observability

Metric names, log fields and tracing: [ARCHITECTURE.md §10](ARCHITECTURE.md#10-observability).
Local stack: `docker compose --profile monitoring up -d` → Grafana http://localhost:3000 (dashboard
"FinFlow overview"), Prometheus :9090, Jaeger :16686 (set `FINFLOW_TRACING_EXPORT_ENABLED=true`).

## 14. Local setup, configuration, migrations, tests

```bash
cp .env.example .env
docker compose up -d                        # PostgreSQL :5433, Kafka :9092, Redis :6380
cd backend && ./mvnw spring-boot:run        # http://localhost:8080
cd frontend && npm install && npm run dev   # http://localhost:5173
# or everything in containers:
docker compose --profile app up -d --build  # http://localhost:8081
```

Configuration: every setting is an environment variable; the table is in the
[README](../README.md#configuration) and the defaults in
[application.yml](../backend/src/main/resources/application.yml).

Migrations run automatically at startup (Flyway), and `ddl-auto=validate` stops the app if entities
and schema disagree. Rules: never edit an applied migration; add `V<n>__*.sql`.

```bash
cd backend && ./mvnw test        # 103 tests (46 unit, 57 integration); needs Docker
cd frontend && npm test          # 19 tests
cd frontend && npm run build     # type-check + production bundle
```

## 15. Deployment

**Status: not deployed publicly.** Everything needed is in the repository and was verified locally,
but no public host was provisioned (no hosting account was used). Do not assume a live URL exists.

### Target: one VM with Docker Compose

[`deploy/docker-compose.prod.yml`](../deploy/docker-compose.prod.yml): Caddy (automatic HTTPS) →
frontend nginx → backend, with PostgreSQL, Kafka and Redis on an `internal: true` network (no
inbound or outbound internet). Only ports 80/443 are published. Resource limits are set per
container, sized for 2 vCPU / 4 GB. Images are multi-arch (amd64, arm64) and published to GHCR by CI
from `main`.

Verified locally (2026-10-23) with `FINFLOW_DOMAIN=localhost`: HTTP→HTTPS redirect, HSTS and CSP
headers, `/api/ops` and `/actuator` return 404 publicly, client IPs reach the backend correctly
through both proxies, and the full browser flow works over HTTPS.

### Why this target (free tiers checked October 2026)

| Option | Finding | Verdict |
|---|---|---|
| Render free web service | Spins down after 15 min idle; 750 h/month. FinFlow measured **291 s to become ready at 0.1 CPU / 512 MB** (Docker-limited locally), so every cold start keeps a visitor waiting ~5 min | Not usable for this app |
| Render free Postgres | Expires 30 days after creation | Not durable |
| Neon free Postgres | 100 CU-hours/project/month, scale to zero after 5 min; FinFlow's pollers keep it awake when the backend is always on | Fine only with a sleeping backend |
| Aiven free Kafka | 250 KiB/s, 5 topics × 2 partitions, no credit card, **powers off when idle** (manual restart) | Works (set `FINFLOW_KAFKA_PARTITIONS=2`), but events pause in the outbox while it is off |
| Upstash Redis free | 500K commands/month, 250 MB | Sufficient (FinFlow uses 1–3 commands per request) |
| Oracle Cloud Always Free | Ampere A1 reportedly reduced from 4 OCPU / 24 GB to **2 OCPU / 12 GB** in 2026 | Enough for the whole compose stack at $0; needs an account and card verification |
| Small paid VPS (e.g. 2 vCPU / 4 GB) | A few € per month | Simplest reliable option |

Recommended: an Oracle Always Free ARM VM or a small VPS running the production compose file. Fully
managed free tiers work only with real caveats (sleeping backend, Kafka needing manual power-on).

### Steps

1. VM with Docker; DNS `A` record for your domain → VM; open ports 80/443 only.
2. `mkdir /opt/finflow`; copy `deploy/.env.production.example` to `/opt/finflow/.env.production`,
   fill in secrets (`openssl rand -hex 32`), `chmod 600`.
3. Repository secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`.
4. GitHub → Actions → **Deploy** → run with an image tag (a commit SHA from CI). The workflow copies
   the compose file, Caddyfile and `deploy.sh`, which backs up the database (`pg_dump`), pulls,
   restarts and smoke-tests `https://<domain>/api/v1/demo/session`, restoring the previous tag if the
   smoke test fails.

### Migrations and rollback

- Flyway applies migrations when the new backend starts; `deploy.sh` takes a `pg_dump` first.
- Write migrations **expand/contract**: add columns/tables in release N (old code ignores them),
  switch code to use them, remove old ones in a later release. Then rolling back the application is
  just redeploying the previous image tag (`deploy.sh <previous-sha>`), because the older code still
  works against the newer schema.
- A migration that cannot be backward compatible requires restoring the backup
  (`pg_restore --clean -d finflow backups/<file>.dump`) together with the previous image.

### Demo data and reset

The demo merchant is seeded on first start. `FINFLOW_DEMO_RESET_CRON` (production default
`0 0 4 * * *` UTC) or `POST /api/ops/demo/reset` (from the host) wipes and re-seeds **only** the demo
merchant. Real merchants cannot be affected: deletes filter on `merchants.demo = true`, and the
ledger trigger rejects deleting any non-demo merchant's entries even with the reset flag set (tested).

### Troubleshooting

| Symptom | Check |
|---|---|
| Backend unhealthy | `docker compose logs backend` (JSON); readiness is DB-only, so it is PostgreSQL connectivity or a failed migration |
| `Migration checksum mismatch` | An applied migration was edited; restore it and add a new migration |
| Demo "logs out" after restart | `FINFLOW_DEMO_API_KEY` not set (a random key is generated per start) |
| Webhooks not arriving | Kafka down? `finflow_outbox_lag_seconds`; dead letters: `GET /api/ops/dead-letters` |
| Many 429s | `X-RateLimit-*` headers; behind a proxy set `FINFLOW_FORWARD_HEADERS_STRATEGY=framework` and nginx `TRUSTED_PROXY_CIDR` |
| Reconciliation shows a fresh payment as fine but stuck | It is younger than `stale-after`; run again later |

Incident procedures: [OPERATIONS.md](OPERATIONS.md).

## 16. Performance testing

Methodology and full numbers: [loadtest/README.md](../loadtest/README.md). Summary (Apple M4 laptop,
everything on one machine, simulator latency 120 ms per provider call, rate limiting off):

| Load | Result |
|---|---|
| 20 payment flows/s, 60 s | 5,768 requests, 0 failed; payment flow p95 283 ms |
| 100 payment flows/s, 60 s | 15,373 requests, 0 failed, 0 duplicate payments; `POST /payments` p95 331 ms (of which ~240 ms is simulated provider latency); async backlog drained within 5 s of the end |
| 200 payment flows/s target | Saturates at ~124 flows/s; `POST /payments` p95 2.99 s; 12 of 15,830 requests failed with client-side connect timeouts |
| Correctness after 18,989 payments | 0 unbalanced journals, 0 duplicate active payments, 0 double postings, 57,325 events → 57,325 deliveries, reconciliation found only the 3 injected anomalies |

One earlier 100/s run had 5 failed requests (0.03 %) with no server-side error; the cause was not
proven. The load test found two bottlenecks (sequential outbox sends, sequential webhook delivery),
both fixed before the numbers above.

## 17. Known limitations and future improvements

| Limitation | Improvement |
|---|---|
| Not deployed publicly | Provision a VM and run the Deploy workflow |
| Workers run in the API process | Run the same image with web disabled as a worker deployment |
| Single simulated provider, its records in the same PostgreSQL | Second provider + routing/failover; separate provider service |
| Reconciliation loads full merchant history | Reconcile per settlement period / file, stream results |
| Webhook secrets in plaintext | Envelope encryption with a KMS key; secret rotation with two active secrets |
| Event order not guaranteed after failed sends | Per-key in-flight limit of 1, or a sequence number consumers can check |
| Refund timeouts treated as failures | Keep `PENDING` and confirm via provider lookup |
| No partial capture, fees, FX, settlement | Extend journals with fee and settlement accounts |
| API keys only | OAuth2 client credentials for merchants; SSO + MFA for operators |
| One Postgres, no replicas | Read replica for dashboards/reconciliation; PITR backups |
