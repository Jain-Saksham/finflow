# Load tests

[k6](https://k6.io) scripts, run through the official Docker image so nothing needs installing.

```bash
# Backend running locally (docker compose up -d && ./mvnw spring-boot:run), rate limiting off so the
# limiter is not what gets measured:
FINFLOW_RATE_LIMIT_ENABLED=false java -jar backend/target/finflow-backend-0.1.0-SNAPSHOT.jar

cd loadtest
docker run --rm -v "$PWD":/scripts -e DURATION=60s -e PAYMENT_RATE=100 \
  grafana/k6:2.3.0 run --summary-export=/scripts/results/summary.json /scripts/finflow.js
```

`BASE_URL` defaults to `http://host.docker.internal:8080` (the host, seen from the k6 container).

## Workload

Four scenarios run in parallel for 60 s against the shared demo merchant (open model: constant
arrival rate, so a slow server shows up as latency and dropped iterations, not as fewer requests):

| Scenario | Rate | Per iteration |
|---|---|---|
| `payments` | `PAYMENT_RATE`/s | `POST /orders` + `POST /payments` (`tok_success`, automatic capture) |
| `reads` | 20/s | `GET /dashboard/summary` + `GET /payments?size=20` |
| `retries` | 2/s | one order, then the same payment request 3× with one `Idempotency-Key`; counts a duplicate if more than one payment exists for the order |
| `refunds` | 2/s | order + payment + partial refund |

Every payment calls the simulated provider twice (authorize, capture) with its configured
**120 ms artificial latency each**, so ~240 ms of every payment's response time is deliberate.
The demo merchant has a webhook endpoint whose receiver fails the first attempt of every event, so
the async path also does retries.

## Results (measured 2026-10-23)

Environment: Apple M4 laptop (10 cores, 16 GB), Docker Desktop VM with 10 CPUs / 7.6 GB running
PostgreSQL 17, Kafka 4.2 (native image, 1 broker, 3 partitions) and Redis 8.6. Backend as a single
JVM on the host (Java 25, virtual threads, Hikari pool 20). k6 in a container on the same machine, so
client and server compete for the same CPUs. These numbers describe this setup, not a production
deployment.

### 20 payment flows/s (`results/summary-rate20.json`)

| Metric | Value |
|---|---|
| HTTP requests | 5,768 in 60 s (95 req/s), **0 failed** |
| Payment flow (order + payment), p50 / p95 / p99 | 260 / 283 / 317 ms |
| Duplicate payments from retried requests | **0** |

### 100 payment flows/s (`results/summary-rate100.json`, `results/run-rate100.txt`)

Run after the throughput fixes described below.

| Endpoint | p50 | p95 | p99 |
|---|---|---|---|
| `POST /payments` (incl. 2 × 120 ms provider latency) | 275 ms | 331 ms | 409 ms |
| `POST /refunds` (incl. 120 ms provider latency) | 141 ms | 189 ms | 242 ms |
| `POST /orders` | 4 ms | 16 ms | 27 ms |
| `GET /payments` | 4 ms | 15 ms | 22 ms |
| `GET /dashboard/summary` | 19 ms | 52 ms | 71 ms |
| Payment flow (order + payment) | 282 ms | 345 ms | 422 ms |

- 15,373 HTTP requests in 60 s (254 req/s), **0 failed**, **0 duplicate payments**.
- The previous identical run (before failure logging was added to the script) had **5 failed
  requests out of 15,341 (0.03 %)** and 12 dropped iterations. The backend logged no error for them,
  so they never reached application code. The 200/s run below shows that the k6 container's
  connections time out (`dial: i/o timeout`) once the system saturates, which is the likely cause,
  but this was not proven for those 5 requests.
- Async path (measured on that previous run): when the 60 s load ended the outbox had **0**
  unpublished events and 578 webhook deliveries open (mostly scheduled first-attempt retries), all
  delivered **within 5 s**.
- The dashboard cache barely helps under this write rate: every committed payment change evicts it.

### 200 payment flows/s: saturation (`results/summary-rate200.json`, `results/run-rate200.txt`)

| Metric | Value |
|---|---|
| Completed iterations | 124/s (k6 dropped 5,763 iterations it could not start) |
| HTTP requests | 15,830 (256 req/s), 12 failed (0.07 %), all `dial: i/o timeout` from the k6 container |
| `POST /payments` p50 / p95 / p99 | 1.55 / 2.99 / 3.49 s |
| Outbox backlog at end of load | 10,268 events, published within 5 s after load stopped |

So on this machine the system saturates at roughly **125 payment flows/s** (≈255 HTTP requests/s for
this mix). It was not profiled further; the Hikari pool (20) and Postgres/Kafka sharing the same
Docker VM CPUs as k6 are the likely limits.

### Correctness after all runs (18,989 payments)

| Check | Result |
|---|---|
| Ledger journals that do not balance | 0 |
| Orders with more than one active payment | 0 |
| Captures posted to the ledger twice | 0 |
| Payments whose ledger receivable ≠ captured − refunded | 0 |
| Outbox events not published | 0 of 57,325 |
| Webhook deliveries not succeeded | 0; 57,325 deliveries for 57,325 events (one endpoint), so no duplicate deliveries were created despite at-least-once Kafka |
| Reconciliation over 18,989 payments vs 38,329 provider records | 3 findings, exactly the 3 anomalies injected by the demo seeder |

## What the load test changed

The first 100/s run found two real bottlenecks, both fixed before the numbers above:

1. **Outbox publisher** sent one record and waited for its acknowledgement before sending the next.
   It fell behind (lag reached 84 s and grew). Now a batch is sent pipelined and marked published with
   one statement.
2. **Webhook worker** delivered 20 deliveries per second, sequentially (17,112 deliveries pending
   after the run). Now each batch is delivered concurrently on virtual threads and the worker keeps
   draining while batches are full.

A Kafka outage test also showed `KafkaTemplate.send()` blocking for the producer's default
`max.block.ms` of 60 s while the publisher held its database transaction; it is now bounded to 5 s.
