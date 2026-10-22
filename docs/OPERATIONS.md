# FinFlow operations runbook

Operator endpoints live under `/api/ops/**`. They require the `X-Ops-Token` header and are disabled
unless `FINFLOW_OPS_TOKEN` is set. They are not in the public OpenAPI document and are unreachable
with any merchant API key, including the shared demo key.

```bash
OPS="-H X-Ops-Token:$FINFLOW_OPS_TOKEN"
API=http://localhost:8080
```

## Health

| Check | Command | Expected |
|---|---|---|
| Liveness | `curl $API/actuator/health/liveness` | `UP` |
| Readiness (database only) | `curl $API/actuator/health/readiness` | `UP` |
| Outbox backlog | `curl $OPS $API/api/ops/outbox` | `pending` near 0, `failed` 0 |
| Metrics | `curl $API/actuator/prometheus \| grep finflow_` | see docs/ARCHITECTURE.md §10 |

Alert-worthy signals (Grafana "FinFlow overview" dashboard):
`finflow_outbox_lag_seconds` > 60, `finflow_outbox_failed` > 0,
`increase(finflow_kafka_consumer_dead_lettered_total[15m])` > 0,
`increase(finflow_webhook_deliveries_failed_total[1h])` rising,
open reconciliation findings, `finflow_redis_failures_total` rising.

## Kafka is down

Effect: the API keeps working; payments, refunds and ledger postings commit normally. Events
accumulate in `outbox_events` (`status = 'PENDING'`); webhooks and automatic timeout recovery pause.

1. Restore the broker. The publisher retries each event with exponential backoff (up to 60 s).
2. Watch `finflow_outbox_pending` fall to 0.
3. Events that exhausted `finflow.outbox.max-attempts` (20) are `FAILED`. Re-queue them:
   `curl -X POST $OPS $API/api/ops/outbox/retry-failed`.

Re-publishing is safe: every consumer deduplicates on the event id.

## Dead letters (replay procedure)

A record lands in `finflow.payment-events.dlt` when a consumer failed 3 retries (transient
error) or immediately (unparseable "poison" message). The DLT recorder copies each one into
`dead_letters` with the failing consumer group and exception.

1. Inspect: `curl $OPS "$API/api/ops/dead-letters?size=20"`.
2. Fix the cause (deploy a fix, restore a dependency). Do **not** replay poison messages; they will
   simply dead-letter again.
3. Replay one record to its original topic:
   `curl -X POST $OPS $API/api/ops/dead-letters/{id}/replay`.
   Every consumer group on that topic sees it again; all of them are idempotent, so groups that had
   already processed it ignore it. `replay_count` / `last_replayed_at` are recorded.

Alternative without the API (e.g. bulk): `kafka-console-consumer` from the DLT piped into
`kafka-console-producer` on the main topic. Same idempotency argument applies.

## Webhooks failing

- `GET /api/v1/webhook-deliveries?status=FAILED` (merchant API) shows dead deliveries; each one's
  attempt history is at `GET /api/v1/webhook-deliveries/{id}`.
- After the receiver is fixed: `POST /api/v1/webhook-deliveries/{id}/redeliver` starts a fresh
  retry cycle. Receivers must deduplicate on `FinFlow-Event-Id`.
- A crashed worker needs no action: its claimed deliveries become claimable again when their
  30-second lease expires.

## Reconciliation findings

Runs every 10 minutes per merchant (`finflow.reconciliation.interval`) and on demand
(`POST /api/v1/reconciliation/runs`, or for any merchant
`POST /api/ops/merchants/{merchantId}/reconciliation-runs`). Safe cases are repaired automatically and
recorded as `AUTO_RESOLVED`. For `OPEN` findings:

| Finding | Action |
|---|---|
| `DUPLICATE_PROVIDER_CAPTURE` | refund the duplicate charge at the provider |
| `ORPHAN_PROVIDER_TRANSACTION` | identify the charge; refund at the provider if it is not ours |
| `STATUS_MISMATCH` (failed here, authorized there) | void the authorization at the provider |
| `MISSING_AT_PROVIDER`, `AMOUNT_MISMATCH` | escalate to the provider with the reference |
| `LEDGER_MISMATCH` | correct the ledger with a reversal (below), never by editing rows |

Findings and runs are append-only: a later clean run is the record that the issue was resolved.

## Correcting the ledger

Ledger rows cannot be updated or deleted (database trigger). Corrections are reversals:

```bash
curl -X POST $OPS -H 'Content-Type: application/json' \
  -d '{"reason":"Capture posted in error, ticket OPS-123"}' \
  $API/api/ops/merchants/{merchantId}/ledger/journals/{journalId}/reverse
```

The reversal mirrors the original journal with every direction swapped. A journal can be reversed
once, and reversals themselves cannot be reversed. Run reconciliation afterwards; it reports any
remaining difference between payments and the ledger.

## Redis is down

Effect: rate limiting is off (fail-open) and caches are bypassed. Each Redis call gives up after
250 ms, so latency rises slightly. No financial data is affected; readiness stays `UP`.
Restore Redis; nothing needs to be rebuilt (it only holds counters and caches). A key rotated
during the outage may stay valid for up to 5 minutes (API-key cache TTL).

## Stuck idempotency key

A client receiving `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` for longer than 30 s means the original
request died. The lease (`finflow.idempotency.lease`) expires automatically and the next retry with
the same key takes over. That is safe because payment and refund keys are also unique on the
domain rows. Completed records are purged after 24 h.

## Simulated provider outage (demo / drills)

`FINFLOW_SIMULATOR_OUTAGE=true` makes every provider call fail as unavailable, to rehearse the
behaviour above without touching real systems.
