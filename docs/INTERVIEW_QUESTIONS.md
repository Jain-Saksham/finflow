# Interview questions: FinFlow

45 questions grouped by topic. Each has a **model answer**, **how FinFlow does it** (with file
pointers, all matching the code), a **trade-off or failure scenario**, and a likely **follow-up**.
Questions marked ★ are the hard ones interviewers like to probe.

Paths are relative to `backend/src/main/java/com/finflow/` unless stated otherwise.

---

## 1. Java and Spring Boot

### 1.1 How do `@Transactional` propagation levels matter in this codebase?
- **Answer:** Propagation decides whether a method joins, requires or suspends a transaction.
  `REQUIRED` joins or starts one; `MANDATORY` fails without one; `REQUIRES_NEW` suspends the caller's.
- **FinFlow:** `LedgerService.post`, `OutboxWriter.append` and `PaymentHistory.record` are `MANDATORY`.
  Posting a journal or writing an event outside the business transaction is a bug, so it fails loudly.
  `ProviderRecords` uses `REQUIRES_NEW` so the simulated provider's record survives a FinFlow rollback,
  like a real external system.
- **Trade-off:** `@Transactional` works through proxies, so self-invocation silently skips it. That
  is why `PaymentService` uses an explicit `TransactionTemplate` for its multi-step flow.
- **Follow-up:** What happens to a `MANDATORY` method called from a scheduled job with no transaction?

### 1.2 Why use `TransactionTemplate` instead of annotations in `PaymentService`?
- **Answer:** Programmatic transactions give exact control over boundaries inside one method.
- **FinFlow:** One `create()` call has three short transactions with a provider call in between,
  none of which holds a lock during the network call (`payment/PaymentService.java`).
- **Trade-off:** More verbose and easy to misuse. But annotations cannot express "commit, call out,
  re-open" inside one method.
- **Follow-up:** What could go wrong if TX2 used optimistic locking instead of `SELECT … FOR UPDATE`?

### 1.3 Why are virtual threads enabled, and where do they help?
- **Answer:** Virtual threads make blocking I/O cheap; a blocked virtual thread unmounts from its
  carrier, so thousands can wait without thousands of OS threads.
- **FinFlow:** `spring.threads.virtual.enabled=true` serves requests and scheduled jobs on virtual
  threads. The provider simulator sleeps 120 ms per call, and the webhook worker delivers each batch
  concurrently with `Executors.newVirtualThreadPerTaskExecutor()`.
- **Trade-off:** Virtual threads do not create more database connections. At ~125 payment flows/s
  the 20-connection Hikari pool, not threads, is the likely ceiling. Pinning on `synchronized` blocks
  can still hurt.
- **Follow-up:** How would you tell whether the pool or the CPU is the bottleneck?

### 1.4 How is configuration structured, and how are secrets handled?
- **Answer:** Typed `@ConfigurationProperties` records bound from environment variables; secrets
  only from the environment, never committed.
- **FinFlow:** Records such as `IdempotencyProperties` and `WebhookProperties`; `application.yml`
  maps `FINFLOW_*` env vars; `.env.example` holds placeholders; `.env*` is git-ignored; gitleaks runs
  in CI.
- **Trade-off:** Env vars are visible to anything that can read the process environment; a secrets
  manager with rotation would be next.
- **Follow-up:** How would you rotate the ops token without downtime?

### 1.5 How do you test a Spring Boot app against real infrastructure?
- **Answer:** Testcontainers starts real dependencies in Docker; `@ServiceConnection` wires them in
  without manual properties.
- **FinFlow:** `IntegrationTestBase` starts PostgreSQL, Kafka and Redis once per run (singleton
  containers, Spring context caching). Tests also pause containers to simulate outages, and use
  Mockito spies (`@MockitoSpyBean`) to inject failures at precise points.
- **Trade-off:** Slower than unit tests (the suite takes about 2.5 min) and needs Docker in CI.
  Shared containers mean tests must isolate by data (each test creates its own merchant), not by
  truncation.
- **Follow-up:** Why did stubbing a spy on a `@Transactional` bean fail, and how was it fixed?
  (Stubbing went through the proxy's transaction check; the fix stubs `AopTestUtils.getUltimateTargetObject`.)

---

## 2. REST API design and security

### 2.1 How are errors returned consistently?
- **Answer:** One error format, machine-readable codes, no internals leaked.
- **FinFlow:** `common/error/GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so
  Spring's own exceptions also become RFC 9457 problem details. Each response carries a stable
  `code`, field `errors` and the `requestId`. Security 401/403/429 use the same shape.
- **Trade-off:** Stable codes are a public contract; renaming one breaks clients.
- **Follow-up:** Why return 404 instead of 403 for another merchant's payment?

### 2.2 How are merchants authenticated and isolated?
- **Answer:** API keys identify the tenant; every query is scoped by tenant id.
- **FinFlow:** Keys are 256-bit random values stored as SHA-256 (`merchant/ApiKeys`). The filter
  resolves them via a Redis-cached lookup. Repositories take `merchantId` (`findByIdAndMerchantId`),
  and other merchants' ids return 404. Tested in `SecurityAndValidationIntegrationTest`.
- **Trade-off:** A fast hash is fine for high-entropy keys (no brute force) and allows an indexed
  lookup; it would be wrong for passwords. A cached key may stay valid for up to 5 min after rotation
  if eviction fails.
- **Follow-up:** How would you add key scopes (read-only keys)?

### 2.3 How do you protect a public demo that hands out an API key?
- **Answer:** Treat it as hostile: least privilege, limits, no side effects outside the sandbox.
- **FinFlow:** The demo key reaches only the demo merchant. Operator functions need a separate
  `X-Ops-Token` and are blocked at Caddy. Limits are per visitor (merchant + IP). External webhook
  URLs are disabled (SSRF), payment-method tokens must match `tok_*`, and a nightly reset touches only
  `demo = true` data.
- **Trade-off:** Shared state means visitors see each other's test data; that is acceptable for a demo.
- **Follow-up:** Someone scripts 10,000 orders an hour from rotating IPs. What next?

### 2.4 How does rate limiting work, and what happens when Redis is down?
- **Answer:** Count requests per subject per window in a shared store; reject with 429 and `Retry-After`.
- **FinFlow:** `ratelimit/RateLimiter` runs a fixed window in Redis, INCR + PEXPIRE in one Lua
  script. Limits: 300 req/min and 60 writes/min per merchant, 60/min per IP for public endpoints. It
  fails open on Redis errors (counted in `finflow_redis_failures_total`).
- **Trade-off:** Fixed windows allow up to 2× the limit at a window boundary. Failing open trades
  abuse protection for availability; that is acceptable because correctness never depends on Redis.
- **Follow-up:** When would you fail closed instead?

### 2.5 How do you make webhooks trustworthy to the receiver?
- **Answer:** Sign the payload with a shared secret, include a timestamp, and give a unique event id.
- **FinFlow:** `webhook/WebhookSigner` sends `FinFlow-Signature: t=…,v1=HMAC-SHA256(secret, t.body)`,
  verified with a constant-time compare and a 5-minute tolerance. Deliveries also carry
  `FinFlow-Event-Id` and do not follow redirects.
- **Trade-off:** HMAC needs the secret in plaintext on our side. Asymmetric signatures would let us
  store only a private key, but are slower and harder for receivers.
- **Follow-up:** How do you rotate a signing secret without breaking receivers?

---

## 3. PostgreSQL and transaction management

### 3.1 Which invariants are enforced by the database, not only by Java?
- **Answer:** Anything whose violation would be a financial bug should be a constraint, because every
  writer goes through the database.
- **FinFlow:** One active payment per order (partial unique index), unique idempotency keys, and
  `refunded ≤ captured ≤ amount` (CHECK). Each capture/refund is posted once (partial unique indexes),
  journals must balance (deferred constraint trigger), and the ledger is append-only (trigger).
- **Trade-off:** Constraint errors surface as `DataIntegrityViolationException` and must be translated
  into meaningful API errors (done in `PaymentService.create` and `RefundService.create`).
- **Follow-up:** Why must the balance trigger be `DEFERRABLE INITIALLY DEFERRED`?

### 3.2 Why `SELECT … FOR UPDATE` on the payment row?
- **Answer:** It serializes concurrent changes to one aggregate; the second transaction waits, then
  sees the committed state.
- **FinFlow:** `PaymentRepository.findForUpdate` is used by capture, void, refund reservation and
  confirmation, and by sync. After acquiring the lock, the state machine re-validates.
- **Trade-off:** Locks are held only for milliseconds because no provider call happens inside the
  transaction. Holding them across the network would cut throughput and risk long waits.
- **Follow-up:** Optimistic or pessimistic locking here, and why?

### 3.3 What does `FOR UPDATE SKIP LOCKED` give you?
- **Answer:** Several workers can claim different rows from the same queue table without blocking
  each other.
- **FinFlow:** The outbox publisher's batch query and the webhook worker's claim query both use it.
  The worker also sets a lease (`locked_until`) so a crashed worker's rows become claimable again.
- **Trade-off:** It gives no ordering across workers, which is why the outbox publisher additionally
  takes an advisory lock so only one instance publishes at a time.
- **Follow-up:** Why not use Kafka for webhook retries instead of a table?

### 3.4 How do you change the schema safely in production?
- **Answer:** Versioned migrations, never edited once applied, written expand/contract so the old
  release still works against the new schema.
- **FinFlow:** Flyway V1–V3 run at startup, with `ddl-auto=validate`. `deploy/deploy.sh` runs
  `pg_dump` before deploying, and rollback means redeploying the previous image tag.
- **Trade-off:** Running migrations on app start is simple, but couples deploys to migration runtime.
  A long migration needs a separate step.
- **Follow-up:** How would you add a NOT NULL column to a 100M-row table?

### 3.5 How are timestamps and money stored?
- **Answer:** `timestamptz` in UTC; money as integer minor units plus currency.
- **FinFlow:** `BIGINT amount_minor` everywhere; Java `long`; the frontend parses and formats money
  with string arithmetic (`frontend/src/lib/money.ts`, tested against the 0.1 + 0.2 trap). Instants are
  truncated to microseconds to match PostgreSQL.
- **Trade-off:** Minor units assume a fixed exponent per currency (2 here). JPY (0) or KWD (3) need a
  currency table.
- **Follow-up:** Where would rounding happen if you added percentage fees?

---

## 4. Idempotency and concurrency

### ★ 4.1 Why is an idempotency key not sufficient without durable enforcement?
- **Answer:** The key is only a label. Safety comes from an atomic "first writer wins" decision that
  survives restarts and is shared by all instances. An in-memory set has a check-then-act race, loses
  entries on restart, and is per instance; a cache can evict.
- **FinFlow:** `idempotency/IdempotencyService` inserts with
  `ON CONFLICT (merchant_id, idempotency_key) DO NOTHING`, so exactly one request owns a key. It
  stores the fingerprint and response; payments and refunds also keep the key under their own unique
  constraint.
- **Failure scenario:** 12 identical requests at once produce one payment, one provider
  authorization and one ledger journal; the others get the stored result or `409 IN_PROGRESS`
  (`IdempotencyIntegrationTest`).
- **Follow-up:** What do you return if the same key arrives with a different body?

### 4.2 What does FinFlow return for repeated keys in each case?
- **Answer:** Define the semantics explicitly: replay, conflict, or wait.
- **FinFlow:** Same request and completed → the stored status and body plus `Idempotent-Replayed:
  true`. Different request → `409 IDEMPOTENCY_KEY_REUSED`. Still running → `409
  IDEMPOTENCY_REQUEST_IN_PROGRESS`. A 4xx business rejection is stored and replayed. A 5xx releases the
  key, so the same key can be retried.
- **Trade-off:** Storing 4xx means a client cannot "fix" a request and reuse the key. That is
  intentional: the key identifies one attempt.
- **Follow-up:** Why release the key on 5xx but not on 422?

### 4.3 What happens if the server crashes in the middle of an idempotent request?
- **Answer:** The record is left `IN_PROGRESS`; you need lease expiry plus re-execution that is itself
  safe.
- **FinFlow:** A 30 s lease; after it, a retry takes over with a compare-and-set. Re-execution is safe
  because `PaymentService.create` finds the existing payment by its unique key and returns it.
  Tested with an abandoned record and with the crash token.
- **Trade-off:** The lease must exceed the slowest legitimate request (the provider timeout is 1.5 s),
  or two requests could run at once.
- **Follow-up:** How do you pick the lease length?

### ★ 4.4 How do you prevent concurrent refunds from exceeding the refundable amount?
- **Answer:** Make the check and the reservation atomic. Lock the payment, count already-reserved
  amounts, insert a reservation, then commit before the slow external call.
- **FinFlow:** `RefundService.create` TX1 does `SELECT payment FOR UPDATE`, sums `PENDING` refunds,
  applies `RefundPolicy` (status, 180-day window, amount ≤ captured − refunded − pending) and inserts a
  `PENDING` refund. The provider call happens after commit; TX2 confirms or fails it. A CHECK
  constraint backs it (`refunded ≤ captured`).
- **Failure scenario:** 8 parallel refunds of 40.00 on a 100.00 payment → exactly 2 succeed
  (`RefundConcurrencyIntegrationTest`).
- **Follow-up:** What if TX2 never happens? (Reconciliation confirms or fails the stale `PENDING` refund.)

### 4.5 How do you stop two different payments for the same order (double charge)?
- **Answer:** A uniqueness rule on "active payment per order" enforced by the database.
- **FinFlow:** A partial unique index on `payments(order_id)` covers `CREATED, AUTHORIZED, TIMED_OUT,
  CAPTURED, PARTIALLY_REFUNDED, REFUNDED`. Declined, failed and voided attempts don't count, so a
  customer can retry. `TIMED_OUT` counts because the charge may have happened.
- **Trade-off:** A stuck `TIMED_OUT` blocks the order until it is resolved, which is the safe choice.
- **Follow-up:** Why include `REFUNDED` in the index?

---

## 5. Double-entry ledger and financial correctness

### ★ 5.1 How can a ledger remain balanced?
- **Answer:** Every business event writes a journal whose debits equal its credits, in one
  transaction, and nothing can edit it afterwards.
- **FinFlow:** `ledger/Journal` cannot be built unbalanced. A deferred constraint trigger re-checks
  each journal at COMMIT, whoever wrote it. An append-only trigger blocks UPDATE, DELETE and TRUNCATE,
  and unique indexes prevent double posting. Tested by inserting a one-sided row with raw SQL; the
  commit fails.
- **Trade-off:** Triggers are hidden logic and cost a query per inserted row (fine at this scale).
- **Follow-up:** How do you correct a wrong entry if you can't update it?

### 5.2 How is a mistake corrected?
- **Answer:** Never edit. Post a reversal (and, if needed, a corrected journal).
- **FinFlow:** `LedgerService.reverse` posts the mirror journal with directions swapped and a
  `reverses_journal_id`. It can be done once, and reversals can't be reversed. It's exposed only to
  operators (`/api/ops/.../reverse`). Reconciliation then flags the payment, since the ledger no
  longer matches it.
- **Trade-off:** After reversing a capture, the capture-once index prevents re-posting a corrected
  capture. A real system would add an `ADJUSTMENT` journal type.
- **Follow-up:** Design the adjustment flow.

### 5.3 How does the ledger differ from the payment status?
- **Answer:** Status is mutable current state of one object; the ledger is an immutable history of
  value moving between accounts, and balances are derived from it.
- **FinFlow:** `payments.status` changes in place. `ledger_entries` only ever grows. Capture is
  Dr `PROVIDER_RECEIVABLE` / Cr `MERCHANT_BALANCE`; a refund reverses it. Reconciliation compares the
  two (`LEDGER_MISMATCH`).
- **Trade-off:** Two representations can drift; detecting drift is the job of reconciliation.
- **Follow-up:** Which one would you trust in an audit, and why?

### 5.4 Why are there no ledger entries at authorization?
- **Answer:** An authorization is a hold, not a movement of money.
- **FinFlow:** Entries post on capture and refund only. Void posts nothing.
- **Trade-off:** You can't see held amounts in the ledger. A memo account for holds could add that.
- **Follow-up:** How would fees change the capture journal?

---

## 6. Kafka, outbox and event delivery

### ★ 6.1 Why use a transactional outbox?
- **Answer:** Writing to the database and publishing to a broker are two systems; without a
  distributed transaction, one can succeed while the other fails ("dual write"). The outbox writes the
  event into the same database transaction and publishes it afterwards.
- **FinFlow:** `PaymentHistory.record` inserts the history row and the outbox row in the business
  transaction. `OutboxPublisher` relays rows to Kafka. A test makes the outbox insert fail; the status
  change and ledger rows roll back too. Another test pauses Kafka; the API keeps working and events
  publish afterwards.
- **Trade-off:** Extra table and latency (poll interval 500 ms). Change-data capture (Debezium) avoids
  polling but adds infrastructure.
- **Follow-up:** Outbox polling vs CDC: when would you switch?

### ★ 6.2 What happens when a Kafka consumer processes a message twice?
- **Answer:** With at-least-once delivery it will happen. Each consumer must be idempotent:
  a dedup key, a unique constraint, or a state check.
- **FinFlow:** Webhook fan-out inserts `ON CONFLICT (endpoint_id, event_id) DO NOTHING`, so a
  duplicate creates nothing. Timeout recovery relies on the state machine: a second resolve hits
  `INVALID_STATE_TRANSITION` and is ignored. Tested by re-sending all of a payment's events to Kafka,
  and by a crash after the Kafka acknowledgement.
- **Trade-off:** The receiver of a webhook can still see an event twice. That is documented; receivers
  dedupe on `FinFlow-Event-Id`.
- **Follow-up:** Where does "effectively once" stop being true in FinFlow?

### 6.3 Is FinFlow "exactly once"?
- **Answer:** No. Kafka's exactly-once covers Kafka-to-Kafka with transactions; side effects in other
  systems need idempotency.
- **FinFlow:** Publishing is at-least-once and consumers are idempotent, so each effect happens once
  in FinFlow's database. Webhook HTTP deliveries are at-least-once to the receiver.
- **Trade-off:** Claiming exactly-once would be wrong; the honest guarantee is easier to reason about.
- **Follow-up:** Would Kafka transactions help the outbox publisher?

### 6.4 How are retries, poison messages and replays handled?
- **Answer:** Retry transient errors with backoff, skip retries for poison messages, park failures in
  a dead-letter topic, and provide a replay path.
- **FinFlow:** `events/KafkaConfig` uses a `DefaultErrorHandler` with 3 exponential retries, makes
  `PoisonMessageException` non-retryable, and publishes failures to `finflow.payment-events.dlt`. A
  separately configured DLT listener copies them into `dead_letters`, and operators replay with
  `POST /api/ops/dead-letters/{id}/replay`.
- **Trade-off:** Replay goes to the original topic, so every consumer group sees it again; that is safe
  only because all of them are idempotent.
- **Follow-up:** Why does the DLT listener need its own container factory?

### 6.5 Are events ordered?
- **Answer:** Kafka orders within a partition; keying by aggregate id keeps one aggregate's events
  together.
- **FinFlow:** The key is the payment id across 3 partitions. The publisher pipelines batches for
  throughput (a load test showed sequential sends fell behind), so after a failed send a later event
  can overtake an earlier one. No consumer depends on order; each event carries a full snapshot and
  `createdAt`.
- **Trade-off:** Strict per-key order would mean one in-flight send per payment, costing throughput.
- **Follow-up:** How would a receiver handle `payment.captured` arriving before `payment.authorized`?

---

## 7. Redis and caching

### ★ 7.1 Why not store all payment state in Redis?
- **Answer:** Redis trades durability and transactional guarantees for speed. Payments need atomic
  multi-row transactions, constraints, durable writes and audit history.
- **FinFlow:** PostgreSQL is the only source of truth. Redis holds rate-limit counters and two caches
  of re-computable data. Persistence is turned off in compose. A paused-Redis test shows payments,
  refunds and the ledger staying correct.
- **Trade-off:** Postgres reads are slower than Redis. Caching derived data recovers most of that
  without risking correctness.
- **Follow-up:** Which data would be safe to move to Redis, and which never?

### 7.2 How are caches invalidated?
- **Answer:** TTL as a safety net plus explicit eviction on writes.
- **FinFlow:** The API-key cache has a 5 min TTL and is evicted on rotation (`DemoAccount`). The
  dashboard cache has a 10 s TTL and is evicted by a `@TransactionalEventListener(AFTER_COMMIT)` when a
  payment changes, so a rolled-back change never evicts and a committed one always does.
- **Trade-off:** Under heavy writes the dashboard cache is almost always cold (measured in the load
  test). Evicting before commit would race readers.
- **Follow-up:** Why evict after commit rather than update the cache?

### 7.3 What happens when Redis is slow rather than down?
- **Answer:** The dangerous case: hung calls hold request threads. Use short timeouts.
- **FinFlow:** Lettuce has a 250 ms command timeout and 500 ms connect timeout. The paused-container
  test confirms requests complete, only slower. Redis is excluded from the readiness probe, so a Redis
  outage does not take instances out of rotation.
- **Trade-off:** Each Redis call can add up to 250 ms during an outage. A circuit breaker would skip
  Redis entirely after repeated failures.
- **Follow-up:** Add a circuit breaker: where, and with what thresholds?

---

## 8. Microservices and distributed systems

### ★ 8.1 Why does a provider timeout not necessarily mean the payment failed?
- **Answer:** A timeout only tells you that you didn't get an answer. The request may have been
  processed, and the response lost. Treating it as failed invites a retry that charges twice.
- **FinFlow:** `tok_timeout` makes the simulator approve, then never answer. The payment becomes
  `TIMED_OUT`, which still blocks new payments for the order. It is resolved by asking the provider
  (`lookupAuthorization`): automatically by the recovery consumer, via `/sync`, or by reconciliation.
- **Failure scenario:** Tested end to end; the payment goes `CREATED → TIMED_OUT → AUTHORIZED →
  CAPTURED` with exactly one provider authorization.
- **Follow-up:** What if the provider has no lookup API?

### ★ 8.2 How do you handle a crash after the provider succeeds but before local state is updated?
- **Answer:** Make provider calls idempotent and keyed by your id; record intent before calling;
  detect and repair the gap from the provider's records.
- **FinFlow:** The payment is committed as `CREATED` before the call; authorization is keyed by payment
  id, capture by provider reference, refund by refund id. Reconciliation finds `CREATED`/`PENDING`
  records older than 60 s, or `AUTHORIZED` payments the provider captured. It repairs them through the
  same idempotent operations (`resolveFromProvider`, `capture`, `confirmFromProvider`).
- **Failure scenario:** Covered by the `tok_crash_after_provider_approval` token, a spy that fails the
  capture commit, and one that fails the refund commit; each is repaired by reconciliation.
- **Follow-up:** Why must reconciliation ignore records younger than a threshold?

### 8.3 Which components would you keep in the modular monolith, and which would you extract?
- **Answer:** Keep together what must change atomically; extract what scales or fails independently.
- **FinFlow:** Keep orders, payments, refunds and the ledger together; they share transactions.
  Extract first the webhook delivery worker (I/O bound, retries, external receivers) and the Kafka
  consumers. They already talk only via PostgreSQL and Kafka, so it's a deployment change. Then
  reconciliation as a batch job.
- **Trade-off:** Extracting the ledger would turn one transaction into a saga with compensations: more
  failure modes for no scaling benefit at this size.
- **Follow-up:** What would the ledger service's API look like if you did extract it?

### 8.4 How does reconciliation work, and what can it fix by itself?
- **Answer:** Compare two independent records of the same truth; classify differences; repair only
  what is provably safe; report the rest.
- **FinFlow:** The pure `ReconciliationMatcher` compares provider transactions with payments, refunds
  and ledger nets. It auto-repairs unresolved payments, captures applied at the provider, and pending
  refunds. It leaves duplicate, orphan and missing charges, amount mismatches and ledger mismatches
  open. Runs and findings are append-only and exportable as CSV.
- **Trade-off:** Loading a merchant's whole history doesn't scale; a real system reconciles per
  settlement file.
- **Follow-up:** How would you make reconciliation incremental?

---

## 9. Docker, deployment and observability

### 9.1 What makes the container images production-friendly?
- **Answer:** Small, non-root, reproducible, health-checked, sized to their memory limits.
- **FinFlow:** Multi-stage builds with pinned base images. Boot jar layers are extracted for caching,
  and the build stage runs on the native platform, so arm64 images don't compile under emulation.
  Both images run as non-root users and have health checks. `MaxRAMPercentage=70` and
  `ExitOnOutOfMemoryError` are set. nginx-unprivileged serves the frontend.
- **Trade-off:** The backend image is 496 MB. A custom jlink runtime or distroless image would shrink it.
- **Follow-up:** How do you size heap vs container memory?

### 9.2 How would you deploy this cheaply, and why not the free managed tiers?
- **Answer:** Check real limits against the workload before choosing.
- **FinFlow:** Render's free tier spins down, and the backend measured 291 s to start at 0.1 CPU. Its
  free Postgres expires after 30 days, and Aiven's free Kafka powers off when idle. So the target is
  one VM running `deploy/docker-compose.prod.yml` behind Caddy (automatic HTTPS), deployed by a manual
  GitHub Actions workflow that backs up, deploys and smoke-tests with automatic rollback. Not deployed
  publicly yet.
- **Trade-off:** One VM is a single point of failure; acceptable for a demo, not for production.
- **Follow-up:** What changes for high availability?

### 9.3 How do you follow one payment across HTTP, Kafka and webhooks?
- **Answer:** Correlation ids and distributed tracing.
- **FinFlow:** `RequestIdFilter` puts `requestId` in the MDC and response header. It is stored on
  outbox rows, sent as a Kafka header, restored in consumers, and stored on webhook deliveries.
  Micrometer Tracing with OpenTelemetry adds `traceId` and exports spans (HTTP, Kafka) to Jaeger via
  OTLP. Logs can be JSON (ECS).
- **Trade-off:** 100 % trace sampling locally, 10 % in production; rare failures may lack a trace,
  but they still have the request id.
- **Follow-up:** Which alerts would you page on?

### 9.4 Which metrics matter for this system?
- **Answer:** Business outcomes, latency, and backlogs of every asynchronous queue.
- **FinFlow:** `finflow_payment_events_total{type}`, `finflow_payment_processing_seconds`,
  `finflow_provider_latency_seconds`, `finflow_outbox_lag_seconds` and `pending`, Kafka consumer lag,
  `finflow_webhook_attempts_total`, `deliveries_due` and `failed`, reconciliation findings, idempotency
  outcomes, Redis failures. All are on the provisioned Grafana dashboard.
- **Trade-off:** High-cardinality tags (merchant id) are deliberately avoided in metrics.
- **Follow-up:** What does a rising outbox lag with normal Kafka health tell you?

---

## 10. System design and trade-offs

### ★ 10.1 How would you scale the system while preserving correctness?
- **Answer:** Scale the stateless parts horizontally; keep invariants in the database; partition when
  one database is no longer enough.
- **FinFlow:** The API is stateless (rate limits in Redis, idempotency in Postgres), so it can run on N
  instances. The outbox publisher is a singleton via advisory lock; the webhook workers scale
  horizontally with SKIP LOCKED leases; Kafka consumers scale up to the partition count. Measured
  ceiling on one laptop: ~125 payment flows/s with a 20-connection pool. Next steps would be a
  read replica for dashboards and reconciliation, then sharding by merchant id, which every
  invariant is already scoped by.
- **Trade-off:** Sharding by merchant makes cross-merchant queries (platform reporting) harder.
- **Follow-up:** Which invariant would break first if you naively added a second database?

### 10.2 What did load testing change?
- **Answer:** Measure before tuning; fix what the data shows.
- **FinFlow:** At 100 flows/s the outbox lag grew to 84 s (sequential sends) and 17,112 webhook
  deliveries piled up (sequential delivery). Pipelined publishing and concurrent delivery fixed both;
  the backlog then drained within 5 s. A Kafka-outage test showed `send()` blocking for the 60 s
  default `max.block.ms` while holding a DB transaction; now 5 s.
- **Trade-off:** Pipelining traded strict per-payment ordering for throughput (documented).
- **Follow-up:** What would you measure next?

### 10.3 How do you handle a dependency outage end to end?
- **Answer:** Decide per dependency whether to fail, degrade or queue.
- **FinFlow:**
  - Provider down → payment `FAILED` (safe to retry) or `503` on capture, recorded in history.
  - Kafka down → API unaffected; outbox queues.
  - Redis down → fail open.
  - Webhook receiver down → retries, then `FAILED`, then manual redelivery.
  - PostgreSQL down → the app is not ready; the readiness probe depends only on the database.
- **Trade-off:** Failing open on rate limits during a Redis outage means a brief window without abuse
  protection.
- **Follow-up:** What if PostgreSQL is slow rather than down?

### 10.4 What would you do differently for real money?
- **Answer:** Real PCI scope, provider redundancy, stronger operations.
- **FinFlow:** Things it deliberately doesn't do: tokenization by a PCI-compliant vault, a real provider
  with webhooks *from* the provider, settlement-file reconciliation, fees and FX, KMS-encrypted
  secrets, HA PostgreSQL with point-in-time recovery, multi-region, and four-eyes approval for ledger
  reversals.
- **Trade-off:** Each adds safety and operational cost; the simulator keeps the design visible
  without them.
- **Follow-up:** Which would you build first, and why?

### 10.5 Why reserve a refund before calling the provider instead of calling first?
- **Answer:** If you call first, two concurrent refunds can both be accepted by the provider before
  either is recorded. Reserving first serializes the decision under a lock without holding the lock
  during the call.
- **FinFlow:** The `PENDING` refund row is the reservation (TX1). The provider call happens without
  locks. TX2 confirms or fails it, and a crash in between is repaired by reconciliation (confirm if
  the provider has it, fail and release if not).
- **Trade-off:** A reservation can be stuck `PENDING` until reconciliation runs (every 10 min), which
  briefly reduces the refundable amount.
- **Follow-up:** How would you shorten that window?
