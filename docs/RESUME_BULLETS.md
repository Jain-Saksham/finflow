# Resume bullets

Use 3–4. Every number below was measured in this repository (see `loadtest/README.md` and the test
suite). Bracketed placeholders are **not yet measured**: fill them in only after measuring, or delete
the clause.

- Built **FinFlow**, a payment orchestration simulator (Java 21, Spring Boot 4, PostgreSQL, Kafka,
  Redis, React/TypeScript) with authorize/capture/refund flows, a double-entry ledger whose balance
  and immutability are enforced by PostgreSQL triggers, and durable idempotency keys. It produced
  **zero duplicate payments or ledger postings across 18,989 payments** in load tests, including a
  saturation run.
- Implemented a **transactional outbox → Kafka** pipeline with idempotent consumers, a dead-letter
  topic with operator replay, and HMAC-signed webhooks with lease-based retries. Load testing found
  two throughput bottlenecks; pipelining fixed them, taking a 100 payments/s backlog that had grown to
  84 s of outbox lag down to draining **within 5 s**.
- Designed **reconciliation** against the provider's independent records that automatically repairs
  crash and timeout gaps (e.g. provider approved, process died before commit) and reports
  unrecoverable discrepancies. It is backed by **103 automated tests** using Testcontainers, Mockito
  failure injection and paused Kafka/Redis containers.
- Containerized the stack (multi-arch images, Caddy HTTPS, GitHub Actions CI with secret scanning)
  and added Prometheus/OpenTelemetry observability. Measured **~125 payment flows/s** at saturation
  on a single laptop (p95 331 ms at 100/s including 240 ms of simulated provider latency).
  [Public demo p95 latency: TBD after deployment] [Production uptime: not applicable / TBD]

Notes for honesty in interviews:
- Throughput was measured on one laptop with k6, the database, Kafka and the backend sharing the
  machine; it is not a production capacity claim.
- "Zero duplicates" refers to the load and concurrency tests in this repository, not real traffic.
- There are no real users or production traffic; do not imply otherwise.
