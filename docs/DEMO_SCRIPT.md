# Five-minute interviewer walkthrough

**Setup (before the call):** `docker compose --profile app up -d --build` (or the dev setup), wait
about 30 s for demo seeding, open http://localhost:8081 and click **Enter demo merchant**. Have
these tabs ready: [ARCHITECTURE.md](ARCHITECTURE.md), the GitHub Actions page, and a terminal in
`backend/`. Optional: `docker compose --profile monitoring up -d` and Grafana on :3000.

Screenshots of every step are in [screenshots/](screenshots/).

| Time | Step | Show | Say |
|---|---|---|---|
| 0:00 | **Problem** (30 s) | Welcome page, the yellow SIMULATION banner | "Taking a payment is one API call; keeping the money right when providers time out, clients retry and processes crash is the hard part. FinFlow is a simulator built to show those mechanics. No real money or card data." |
| 0:30 | **Architecture** (45 s) | ARCHITECTURE.md §1 and §4 diagrams | "Modular monolith on PostgreSQL. State, ledger and outbox event commit in one transaction; a publisher relays to Kafka; consumers fan out signed webhooks and recover timeouts; reconciliation checks us against the provider's own records. Redis only for rate limits and caches." |
| 1:15 | **Create a payment** (45 s) | New order → 49.90 → *Approved*, capture **Manual** → Pay → Open payment → **Capture** | Point at the history timeline (`CREATED → AUTHORIZED → CAPTURED`) and the two ledger lines (Dr receivable / Cr merchant balance). "Money is integer minor units end to end." |
| 2:00 | **Failure and recovery** (60 s) | New order → *Declined: insufficient funds* → Pay (order stays open) → *Approved* → Pay. Then a new order with *FinFlow crash after approval* → error shown → Reconciliation → Run (after 60 s) → finding `UNRESOLVED_PAYMENT`, **Auto resolved**, order now paid | "Failed attempts stay on record and don't block retries. The crash token approves at the provider, then FinFlow 'dies' before saving. The payment stays CREATED; reconciliation sees the provider's authorization and repairs it with the same idempotent operations. Records younger than 60 s are skipped because they may still be in flight." |
| 3:00 | **Duplicate requests** (40 s) | Terminal: `./mvnw test -Dtest=IdempotencyIntegrationTest` (about 30 s including container start), or the order page's displayed Idempotency-Key | "12 identical requests at once produce one payment and one provider authorization. The key is enforced with a unique constraint, not a cache, and a crashed request's key is taken over after a 30 s lease. Retrying during a provider timeout returns 409 IN_PROGRESS, then the original response." |
| 3:40 | **Ledger and reconciliation** (35 s) | Ledger page (per-currency *Balanced* badge); Reconciliation report: three red **Open** findings | "A database trigger checks every journal balances at commit; entries are append-only. The open findings are deliberately injected provider anomalies (a double charge, a lost capture, an orphan charge). Money moved wrongly, so a human must act; the system won't 'fix' those." |
| 4:15 | **Tests, metrics, deployment** (30 s) | GitHub Actions run (backend, frontend, gitleaks, multi-arch images); Webhooks page (attempt 1 failed 503, attempt 2 succeeded); Grafana dashboard or `/actuator/prometheus` | "103 backend tests against real PostgreSQL, Kafka and Redis, including paused containers and injected crashes. Load tested with k6 (about 125 payment flows/s on a laptop, zero duplicates). Deployment is one VM with Compose and Caddy: ready and verified locally, not yet live." |
| 4:45 | **Limitations** (15 s) | PROJECT_GUIDE §17 | "Workers run in the API process, one simulated provider, reconciliation loads full history, webhook secrets aren't encrypted. Next: separate worker deployment, settlement-file reconciliation, KMS for secrets." |

**Likely interruptions** (answers in [INTERVIEW_QUESTIONS.md](INTERVIEW_QUESTIONS.md)):
"Is this exactly-once?" (6.3), "Why not Redis for state?" (7.1), "Why not microservices?" (8.3),
"Why is a timeout not a failure?" (8.1).
