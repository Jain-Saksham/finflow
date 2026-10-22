-- Phase 2: idempotency records, transactional outbox, dead letters, webhooks, the provider
-- simulator's independent records, reconciliation, and database-enforced ledger invariants.

-- ===================================================================== Idempotency
-- One row per (merchant, Idempotency-Key). The fingerprint detects a key reused with a different
-- request; the stored response is replayed for retries; the lease marks a request in flight.
create table idempotency_records (
    id                  bigint generated always as identity primary key,
    merchant_id         uuid         not null references merchants (id),
    idempotency_key     varchar(255) not null,
    operation           varchar(64)  not null,
    request_fingerprint varchar(64)  not null,
    status              varchar(16)  not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    locked_until        timestamptz,
    response_status     int,
    response_body       text,
    error_code          varchar(64),
    error_message       varchar(500),
    resource_id         uuid,
    created_at          timestamptz  not null,
    completed_at        timestamptz,
    constraint ux_idempotency_merchant_key unique (merchant_id, idempotency_key)
);

create index ix_idempotency_created on idempotency_records (created_at);

-- ===================================================================== Transactional outbox
-- Written in the same transaction as the business change; a background publisher relays rows to
-- Kafka. The event id doubles as the consumer-side deduplication key.
create table outbox_events (
    id               uuid primary key,
    sequence_no      bigint generated always as identity,
    aggregate_type   varchar(40)  not null,
    aggregate_id     uuid         not null,
    merchant_id      uuid         not null references merchants (id),
    event_type       varchar(64)  not null,
    payload          text         not null,
    correlation_id   varchar(64),
    status           varchar(16)  not null check (status in ('PENDING', 'PUBLISHED', 'FAILED')),
    publish_attempts int          not null default 0,
    next_attempt_at  timestamptz  not null,
    last_error       varchar(500),
    created_at       timestamptz  not null,
    published_at     timestamptz,
    constraint ux_outbox_sequence unique (sequence_no)
);

create index ix_outbox_pending on outbox_events (sequence_no) where status = 'PENDING';
create index ix_outbox_aggregate on outbox_events (aggregate_id, sequence_no);

-- ===================================================================== Kafka dead letters
create table dead_letters (
    id              bigint generated always as identity primary key,
    topic           varchar(255)  not null,
    kafka_partition int           not null,
    kafka_offset    bigint        not null,
    message_key     varchar(255),
    payload         text,
    original_topic  varchar(255),
    consumer_group  varchar(255),
    error_class     varchar(255),
    error_message   varchar(1000),
    received_at     timestamptz   not null,
    replay_count    int           not null default 0,
    last_replayed_at timestamptz,
    constraint ux_dead_letters_position unique (topic, kafka_partition, kafka_offset)
);

-- ===================================================================== Webhooks
create table webhook_endpoints (
    id                 uuid primary key,
    merchant_id        uuid         not null references merchants (id),
    url                varchar(500) not null,
    secret             varchar(100) not null,
    description        varchar(255),
    enabled            boolean      not null,
    simulated_behavior varchar(32) check (simulated_behavior in
        ('ALWAYS_SUCCEED', 'FAIL_FIRST_ATTEMPT', 'FAIL_FIRST_THREE', 'ALWAYS_FAIL')),
    version            bigint       not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null
);

create index ix_webhook_endpoints_merchant on webhook_endpoints (merchant_id);

create table webhook_deliveries (
    id                   uuid primary key,
    endpoint_id          uuid         not null references webhook_endpoints (id),
    merchant_id          uuid         not null references merchants (id),
    event_id             uuid         not null,
    event_type           varchar(64)  not null,
    payment_id           uuid,
    payload              text         not null,
    status               varchar(16)  not null check (status in
        ('PENDING', 'IN_PROGRESS', 'RETRYING', 'SUCCEEDED', 'FAILED')),
    attempt_count        int          not null default 0,
    next_attempt_at      timestamptz,
    locked_until         timestamptz,
    last_response_status int,
    last_error           varchar(500),
    correlation_id       varchar(64),
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    succeeded_at         timestamptz,
    -- Consumer-side dedup: a re-delivered Kafka event cannot create a second delivery.
    constraint ux_webhook_delivery_event unique (endpoint_id, event_id)
);

create index ix_webhook_deliveries_due on webhook_deliveries (next_attempt_at)
    where status in ('PENDING', 'RETRYING', 'IN_PROGRESS');
create index ix_webhook_deliveries_merchant on webhook_deliveries (merchant_id, created_at desc);
create index ix_webhook_deliveries_payment on webhook_deliveries (payment_id);

create table webhook_delivery_attempts (
    id              bigint generated always as identity primary key,
    delivery_id     uuid          not null references webhook_deliveries (id),
    attempt_no      int           not null,
    outcome         varchar(16)   not null check (outcome in ('SUCCEEDED', 'FAILED')),
    response_status int,
    response_body   varchar(1000),
    error           varchar(500),
    duration_ms     int           not null,
    started_at      timestamptz   not null,
    constraint ux_webhook_attempt_no unique (delivery_id, attempt_no)
);

-- What the built-in simulated receiver has seen; demonstrates receiver-side deduplication.
create table webhook_receipts (
    endpoint_id          uuid        not null references webhook_endpoints (id),
    event_id             uuid        not null,
    attempts             int         not null,
    duplicate_deliveries int         not null default 0,
    first_received_at    timestamptz not null,
    processed_at         timestamptz,
    primary key (endpoint_id, event_id)
);

-- ===================================================================== Provider simulator
-- The simulated provider's OWN records, in a separate schema with no foreign keys into FinFlow's
-- tables. It commits independently of FinFlow's transactions, like a real external system, which
-- is what makes reconciliation meaningful.
create schema provider_sim;

create table provider_sim.transactions (
    id                 bigint generated always as identity primary key,
    idempotency_key    varchar(100) not null,
    type               varchar(16)  not null check (type in ('AUTHORIZATION', 'CAPTURE', 'VOID', 'REFUND')),
    status             varchar(16)  not null check (status in ('SUCCEEDED', 'DECLINED')),
    merchant_id        uuid         not null,
    payment_id         uuid         not null,
    refund_id          uuid,
    provider_reference varchar(64)  not null,
    amount_minor       bigint       not null,
    currency           varchar(3)   not null,
    decline_code       varchar(64),
    injected_anomaly   varchar(40),
    created_at         timestamptz  not null default now(),
    constraint ux_provider_tx_idempotency unique (type, idempotency_key)
);

create index ix_provider_tx_merchant on provider_sim.transactions (merchant_id);
create index ix_provider_tx_payment on provider_sim.transactions (payment_id);

-- ===================================================================== Reconciliation
create table reconciliation_runs (
    id                    uuid primary key,
    merchant_id           uuid        not null references merchants (id),
    trigger_type          varchar(16) not null check (trigger_type in ('MANUAL', 'SCHEDULED', 'OPS')),
    status                varchar(16) not null check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    provider_transactions int,
    internal_payments     int,
    matched_payments      int,
    in_flight_skipped     int,
    findings_total        int,
    auto_resolved         int,
    open_findings         int,
    error                 varchar(500),
    started_at            timestamptz not null,
    finished_at           timestamptz
);

-- At most one run in progress per merchant.
create unique index ux_reconciliation_one_running on reconciliation_runs (merchant_id) where status = 'RUNNING';
create index ix_reconciliation_runs_merchant on reconciliation_runs (merchant_id, started_at desc);

-- Findings are written once, after any automatic resolution was attempted: an append-only audit trail.
create table reconciliation_findings (
    id                 bigint generated always as identity primary key,
    run_id             uuid         not null references reconciliation_runs (id),
    merchant_id        uuid         not null references merchants (id),
    type               varchar(40)  not null,
    severity           varchar(16)  not null check (severity in ('INFO', 'WARNING', 'CRITICAL')),
    recoverable        boolean      not null,
    resolution         varchar(24)  not null check (resolution in ('AUTO_RESOLVED', 'OPEN', 'RESOLUTION_FAILED')),
    payment_id         uuid,
    refund_id          uuid,
    provider_reference varchar(64),
    expected           varchar(255),
    actual             varchar(255),
    detail             varchar(500) not null,
    resolution_detail  varchar(500),
    created_at         timestamptz  not null
);

create index ix_reconciliation_findings_run on reconciliation_findings (run_id, id);

-- ===================================================================== Ledger hardening
-- Corrections are new REVERSAL journals that mirror the original with directions swapped.
alter table ledger_entries drop constraint ledger_entries_journal_type_check;
alter table ledger_entries add constraint ck_ledger_journal_type
    check (journal_type in ('CAPTURE', 'REFUND', 'REVERSAL'));
alter table ledger_entries add column reverses_journal_id uuid;
alter table ledger_entries drop constraint ck_ledger_refund_reference;
alter table ledger_entries add constraint ck_ledger_references check (
    (journal_type = 'CAPTURE' and refund_id is null and reverses_journal_id is null)
    or (journal_type = 'REFUND' and refund_id is not null and reverses_journal_id is null)
    or (journal_type = 'REVERSAL' and reverses_journal_id is not null));

-- A journal can be reversed at most once.
create unique index ux_ledger_reversal_once on ledger_entries (reverses_journal_id, account)
    where journal_type = 'REVERSAL';
create index ix_ledger_entries_journal on ledger_entries (journal_id);

-- Every journal must balance and use one currency. Deferred to COMMIT so a journal's lines can be
-- inserted one at a time; an unbalanced transaction cannot commit no matter which code path wrote it.
create function ledger_assert_journal_balanced() returns trigger
    language plpgsql as
$$
declare
    imbalance  bigint;
    currencies int;
begin
    select coalesce(sum(case direction when 'DEBIT' then amount_minor else -amount_minor end), 0),
           count(distinct currency)
    into imbalance, currencies
    from ledger_entries
    where journal_id = new.journal_id;
    if imbalance <> 0 then
        raise exception 'ledger journal % is unbalanced by %', new.journal_id, imbalance
            using errcode = '23514';
    end if;
    if currencies <> 1 then
        raise exception 'ledger journal % mixes currencies', new.journal_id using errcode = '23514';
    end if;
    return null;
end
$$;

create constraint trigger trg_ledger_journal_balanced
    after insert on ledger_entries
    deferrable initially deferred
    for each row execute function ledger_assert_journal_balanced();

-- Append-only. The single exception: demo seeding may shift created_at (and nothing else) when it
-- explicitly sets the transaction-local flag finflow.allow_ledger_backdating = 'on'.
create function ledger_forbid_mutation() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'UPDATE'
        and current_setting('finflow.allow_ledger_backdating', true) = 'on'
        and (to_jsonb(new) - 'created_at') = (to_jsonb(old) - 'created_at') then
        return new;
    end if;
    raise exception 'ledger_entries is append-only: % is not allowed', tg_op using errcode = '42501';
end
$$;

create trigger trg_ledger_append_only
    before update or delete on ledger_entries
    for each row execute function ledger_forbid_mutation();

create trigger trg_ledger_no_truncate
    before truncate on ledger_entries
    for each statement execute function ledger_forbid_mutation();
