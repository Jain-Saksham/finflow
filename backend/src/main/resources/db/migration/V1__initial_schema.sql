-- FinFlow initial schema.
-- Money is always stored as BIGINT minor units (e.g. cents) alongside an ISO-4217 currency code.
-- Aggregates reference each other by id; foreign keys keep those references honest.

create table merchants (
    id               uuid primary key,
    name             varchar(120) not null,
    api_key_hash     varchar(64)  not null,
    api_key_prefix   varchar(16)  not null,
    default_currency varchar(3)   not null,
    demo             boolean      not null default false,
    version          bigint       not null,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    constraint ux_merchants_api_key_hash unique (api_key_hash)
);

-- At most one demo merchant, so the public demo session can never hand out another merchant's key.
create unique index ux_merchants_single_demo on merchants (demo) where demo;

create table orders (
    id                 uuid primary key,
    merchant_id        uuid         not null references merchants (id),
    merchant_reference varchar(64)  not null,
    amount_minor       bigint       not null check (amount_minor > 0),
    currency           varchar(3)   not null,
    description        varchar(255),
    status             varchar(20)  not null check (status in ('OPEN', 'PAID')),
    version            bigint       not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint ux_orders_merchant_reference unique (merchant_id, merchant_reference)
);

create index ix_orders_merchant_created on orders (merchant_id, created_at desc);

create table payments (
    id                    uuid primary key,
    merchant_id           uuid         not null references merchants (id),
    order_id              uuid         not null references orders (id),
    amount_minor          bigint       not null check (amount_minor > 0),
    currency              varchar(3)   not null,
    status                varchar(24)  not null check (status in
        ('CREATED', 'AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED',
         'DECLINED', 'FAILED', 'TIMED_OUT', 'VOIDED')),
    capture_mode          varchar(16)  not null check (capture_mode in ('AUTOMATIC', 'MANUAL')),
    payment_method_token  varchar(64)  not null,
    provider              varchar(32)  not null,
    provider_reference    varchar(64),
    failure_code          varchar(64),
    failure_message       varchar(255),
    captured_amount_minor bigint       not null default 0,
    refunded_amount_minor bigint       not null default 0,
    idempotency_key       varchar(255) not null,
    authorized_at         timestamptz,
    captured_at           timestamptz,
    version               bigint       not null,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    constraint ux_payments_idempotency_key unique (merchant_id, idempotency_key),
    constraint ck_payments_amounts check (
        captured_amount_minor between 0 and amount_minor
        and refunded_amount_minor between 0 and captured_amount_minor)
);

-- Double-charge guard: an order may have many failed attempts but only one payment that
-- could still move (or has moved) money. TIMED_OUT counts because its outcome is unknown.
create unique index ux_payments_one_active_per_order on payments (order_id)
    where status in ('CREATED', 'AUTHORIZED', 'TIMED_OUT', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED');

create index ix_payments_merchant_created on payments (merchant_id, created_at desc);
create index ix_payments_merchant_status on payments (merchant_id, status);
create index ix_payments_order on payments (order_id);

-- Append-only transaction history; the identity id gives a stable order within a payment.
create table payment_events (
    id           bigint generated always as identity primary key,
    payment_id   uuid         not null references payments (id),
    merchant_id  uuid         not null references merchants (id),
    type         varchar(40)  not null,
    from_status  varchar(24),
    to_status    varchar(24)  not null,
    amount_minor bigint,
    detail       varchar(255),
    created_at   timestamptz  not null
);

create index ix_payment_events_payment on payment_events (payment_id, id);

create table refunds (
    id                 uuid primary key,
    merchant_id        uuid         not null references merchants (id),
    payment_id         uuid         not null references payments (id),
    amount_minor       bigint       not null check (amount_minor > 0),
    currency           varchar(3)   not null,
    status             varchar(16)  not null check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    reason             varchar(255),
    provider_reference varchar(64),
    failure_code       varchar(64),
    idempotency_key    varchar(255) not null,
    version            bigint       not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    constraint ux_refunds_idempotency_key unique (merchant_id, idempotency_key)
);

create index ix_refunds_payment on refunds (payment_id, created_at);

-- Double-entry ledger. Every journal (one business event) writes balanced DEBIT/CREDIT rows.
create table ledger_entries (
    id           bigint generated always as identity primary key,
    journal_id   uuid         not null,
    journal_type varchar(20)  not null check (journal_type in ('CAPTURE', 'REFUND')),
    merchant_id  uuid         not null references merchants (id),
    payment_id   uuid         not null references payments (id),
    refund_id    uuid references refunds (id),
    account      varchar(40)  not null check (account in ('PROVIDER_RECEIVABLE', 'MERCHANT_BALANCE')),
    direction    varchar(6)   not null check (direction in ('DEBIT', 'CREDIT')),
    amount_minor bigint       not null check (amount_minor > 0),
    currency     varchar(3)   not null,
    description  varchar(255) not null,
    created_at   timestamptz  not null,
    constraint ck_ledger_refund_reference check ((journal_type = 'REFUND') = (refund_id is not null))
);

-- A capture or refund can be posted to the ledger only once.
create unique index ux_ledger_capture_once on ledger_entries (payment_id, account) where journal_type = 'CAPTURE';
create unique index ux_ledger_refund_once on ledger_entries (refund_id, account) where journal_type = 'REFUND';

create index ix_ledger_entries_merchant on ledger_entries (merchant_id, created_at desc, id desc);
create index ix_ledger_entries_payment on ledger_entries (payment_id);
