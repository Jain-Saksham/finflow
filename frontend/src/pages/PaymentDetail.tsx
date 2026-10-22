import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { newIdempotencyKey } from '../api/client';
import { useCreateRefund, useLedgerEntries, usePayment, usePaymentAction, usePaymentEvents, useRefunds, useWebhookDeliveries } from '../api/hooks';
import { DeliveryBadge } from '../components/DeliveryBadge';
import type { Payment, PaymentAction } from '../api/types';
import { Icon } from '../components/Icons';
import { EmptyState, ErrorAlert, LoadingBlock, SuccessAlert } from '../components/States';
import { StatusBadge, humanize } from '../components/StatusBadge';
import { formatDateTime, shortId, tokenLabel } from '../lib/format';
import { formatMoney, minorToDecimalString, parseAmountToMinor } from '../lib/money';

const ACTION_LABELS: Record<Exclude<PaymentAction, 'REFUND'>, { verb: 'capture' | 'void' | 'sync'; label: string; help: string }> = {
  CAPTURE: { verb: 'capture', label: 'Capture', help: 'Collect the authorized funds.' },
  VOID: { verb: 'void', label: 'Void', help: 'Release the authorization without charging.' },
  SYNC: { verb: 'sync', label: 'Sync with provider', help: 'Ask the provider what happened to a timed-out request.' },
};

function Actions({ payment }: { payment: Payment }) {
  const [showAll, setShowAll] = useState(false);
  const action = usePaymentAction(payment.id);
  const shown = (showAll ? (['CAPTURE', 'VOID', 'SYNC'] as const) : payment.availableActions.filter((a) => a !== 'REFUND')) as
    (keyof typeof ACTION_LABELS)[];
  const canRefund = showAll || payment.availableActions.includes('REFUND');

  return (
    <section className="card">
      <div className="card-head">
        <div>
          <h2>Actions</h2>
          <p>Offered by the API from the payment state machine.</p>
        </div>
        <label className="checkbox" title="Show every action and let the backend reject the invalid ones">
          <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} />
          Show all actions
        </label>
      </div>
      <div className="card-body stack" style={{ gap: 12 }}>
        {showAll && (
          <div className="alert alert-info">
            <Icon name="flask" />
            <div>Invalid actions are not hidden here, so you can watch the backend reject them with a 409.</div>
          </div>
        )}
        {shown.length === 0 && !canRefund && (
          <p className="muted" style={{ margin: 0 }}>
            No actions are available for a {humanize(payment.status).toLowerCase()} payment.
          </p>
        )}
        {shown.length > 0 && (
          <div className="row">
            {shown.map((a) => (
              <button
                key={a}
                className={`btn ${a === 'VOID' ? 'btn-danger' : 'btn-primary'}`}
                title={ACTION_LABELS[a].help}
                disabled={action.isPending}
                onClick={() => action.mutate(ACTION_LABELS[a].verb)}
              >
                {action.isPending && action.variables === ACTION_LABELS[a].verb && <span className="spinner" />}
                {ACTION_LABELS[a].label}
              </button>
            ))}
          </div>
        )}
        {action.error && <ErrorAlert error={action.error} title="The API rejected the action" />}
        {action.data && !action.isPending && !action.error && (
          <SuccessAlert>Done. Payment is now <strong>{humanize(action.data.status)}</strong>.</SuccessAlert>
        )}
        {canRefund && <RefundForm payment={payment} />}
      </div>
    </section>
  );
}

function RefundForm({ payment }: { payment: Payment }) {
  const refund = useCreateRefund(payment.id);
  const [amount, setAmount] = useState(() => minorToDecimalString(Math.max(payment.refundableAmountMinor, 0)));
  const [reason, setReason] = useState('');
  // One key per refund attempt: a retried submit (e.g. after a network error) cannot refund twice.
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);
  const [inputError, setInputError] = useState<string | null>(null);
  // Keep the suggested amount in step with the refundable balance after each refund.
  const [suggestedFor, setSuggestedFor] = useState(payment.refundableAmountMinor);
  if (suggestedFor !== payment.refundableAmountMinor) {
    setSuggestedFor(payment.refundableAmountMinor);
    setAmount(minorToDecimalString(Math.max(payment.refundableAmountMinor, 0)));
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    const minor = parseAmountToMinor(amount);
    if (minor === null) {
      setInputError('Enter a positive amount with at most two decimals.');
      return;
    }
    setInputError(null);
    refund.mutate(
      { idempotencyKey, amountMinor: minor, reason: reason || undefined },
      {
        onSuccess: (r) => {
          setIdempotencyKey(newIdempotencyKey());
          if (r.status === 'SUCCEEDED') setReason('');
        },
      },
    );
  }

  return (
    <form onSubmit={submit} className="stack" style={{ gap: 10, borderTop: '1px solid var(--border)', paddingTop: 14 }}>
      <div className="row spread">
        <h3>Refund</h3>
        <span className="muted num" style={{ fontSize: 12.5 }}>
          Refundable: {formatMoney(payment.refundableAmountMinor, payment.currency)}
        </span>
      </div>
      <div className="form-grid">
        <div className="field">
          <label htmlFor="refund-amount">Amount</label>
          <div className="input-affix">
            <span>{payment.currency}</span>
            <input
              id="refund-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
              aria-invalid={inputError ? 'true' : undefined}
            />
          </div>
          {inputError && <span className="error">{inputError}</span>}
        </div>
        <div className="field">
          <label htmlFor="refund-reason">Reason (optional)</label>
          <input id="refund-reason" maxLength={255} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Customer returned the item" />
        </div>
      </div>
      <div className="row spread">
        <span className="faint mono" style={{ fontSize: 11.5 }}>Idempotency-Key {idempotencyKey.slice(0, 13)}…</span>
        <button className="btn btn-primary" disabled={refund.isPending}>
          {refund.isPending && <span className="spinner" />} Issue refund
        </button>
      </div>
      {refund.error && <ErrorAlert error={refund.error} title="Refund rejected" />}
      {refund.data && !refund.isPending && !refund.error && (
        refund.data.status === 'SUCCEEDED' ? (
          <SuccessAlert>Refunded {formatMoney(refund.data.amountMinor, refund.data.currency)}.</SuccessAlert>
        ) : (
          <div className="alert alert-warning" role="alert">
            <Icon name="alert" />
            <div>The provider could not process the refund ({refund.data.failureCode}). Nothing was refunded.</div>
          </div>
        )
      )}
    </form>
  );
}

function Timeline({ paymentId }: { paymentId: string }) {
  const events = usePaymentEvents(paymentId);
  if (events.isPending) return <LoadingBlock rows={3} />;
  if (events.error) return <div className="card-body"><ErrorAlert error={events.error} /></div>;
  return (
    <div className="card-body">
      <ol className="timeline">
        {events.data.map((e) => (
          <li key={e.id}>
            <div className="row" style={{ gap: 8 }}>
              <strong>{humanize(e.type)}</strong>
              {e.fromStatus && e.fromStatus !== e.toStatus && (
                <span className="faint" style={{ fontSize: 12.5 }}>
                  {humanize(e.fromStatus)} → {humanize(e.toStatus)}
                </span>
              )}
            </div>
            {e.detail && <div className="muted" style={{ fontSize: 13 }}>{e.detail}</div>}
            <div className="when">{formatDateTime(e.createdAt)}</div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function Refunds({ payment }: { payment: Payment }) {
  const refunds = useRefunds(payment.id);
  if (refunds.isPending) return <LoadingBlock rows={2} />;
  if (refunds.error) return <div className="card-body"><ErrorAlert error={refunds.error} /></div>;
  if (refunds.data.length === 0) return <EmptyState title="No refunds" />;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Refund</th><th className="right">Amount</th><th>Status</th><th>Reason</th><th>Created</th></tr></thead>
        <tbody>
          {refunds.data.map((r) => (
            <tr key={r.id}>
              <td className="mono">{shortId(r.id)}</td>
              <td className="right num">{formatMoney(r.amountMinor, r.currency)}</td>
              <td><StatusBadge status={r.status} /> {r.failureCode && <span className="mono faint">{r.failureCode}</span>}</td>
              <td className="muted">{r.reason ?? '—'}</td>
              <td className="muted nowrap">{formatDateTime(r.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PaymentLedger({ paymentId }: { paymentId: string }) {
  const entries = useLedgerEntries({ paymentId, size: 50 });
  if (entries.isPending) return <LoadingBlock rows={2} />;
  if (entries.error) return <div className="card-body"><ErrorAlert error={entries.error} /></div>;
  if (entries.data.totalElements === 0) {
    return <EmptyState title="No ledger entries">Entries are posted when money moves: on capture and on refund.</EmptyState>;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Journal</th><th>Account</th><th className="right">Debit</th><th className="right">Credit</th></tr></thead>
        <tbody>
          {entries.data.content.map((e) => (
            <tr key={e.id}>
              <td>{humanize(e.journalType)} <span className="mono faint">{shortId(e.journalId)}</span></td>
              <td className="mono">{e.account}</td>
              <td className="right num">{e.direction === 'DEBIT' ? formatMoney(e.amountMinor, e.currency) : ''}</td>
              <td className="right num">{e.direction === 'CREDIT' ? formatMoney(e.amountMinor, e.currency) : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PaymentWebhooks({ paymentId }: { paymentId: string }) {
  const deliveries = useWebhookDeliveries({ paymentId, size: 20 });
  return (
    <section className="card">
      <div className="card-head"><div><h2>Webhook deliveries</h2><p>One per event and endpoint</p></div></div>
      {deliveries.isPending && <LoadingBlock rows={2} />}
      {deliveries.error && <div className="card-body"><ErrorAlert error={deliveries.error} /></div>}
      {deliveries.data && deliveries.data.totalElements === 0 && (
        <EmptyState title="None yet">Events are delivered asynchronously via Kafka.</EmptyState>
      )}
      {deliveries.data && deliveries.data.totalElements > 0 && (
        <div className="card-body stack" style={{ gap: 8 }}>
          {deliveries.data.content.map((d) => (
            <Link key={d.id} to={`/webhooks/deliveries/${d.id}`} className="row spread" style={{ color: 'inherit' }}>
              <span className="mono" style={{ fontSize: 12.5 }}>{d.eventType}</span>
              <span className="row" style={{ gap: 6 }}>
                <span className="faint num" style={{ fontSize: 12 }}>×{d.attemptCount}</span>
                <DeliveryBadge status={d.status} />
              </span>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

export function PaymentDetail() {
  const { paymentId = '' } = useParams();
  const { data: payment, isPending, error } = usePayment(paymentId);

  if (isPending) return <div className="card"><LoadingBlock rows={6} /></div>;
  if (error) return <ErrorAlert error={error} title="Could not load the payment" />;

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs"><Link to="/payments">Transactions</Link> / <span className="mono">{shortId(payment.id)}</span></div>
          <div className="row" style={{ gap: 12 }}>
            <span className="hero-amount">{formatMoney(payment.amountMinor, payment.currency)}</span>
            <StatusBadge status={payment.status} />
          </div>
          {payment.failureMessage && (
            <p><span className="mono">{payment.failureCode}</span> · {payment.failureMessage}</p>
          )}
        </div>
        <Link className="btn" to={`/orders/${payment.orderId}`}>View order</Link>
      </div>

      <div className="grid grid-3-1">
        <div className="stack">
          <section className="card">
            <div className="card-body">
              <dl className="facts">
                <div><dt>Payment ID</dt><dd className="mono">{payment.id}</dd></div>
                <div><dt>Provider</dt><dd>{payment.provider}</dd></div>
                <div><dt>Provider reference</dt><dd className="mono">{payment.providerReference ?? '—'}</dd></div>
                <div><dt>Payment method (fake token)</dt><dd className="mono">{payment.paymentMethodToken}</dd></div>
                <div><dt>Scenario</dt><dd>{tokenLabel(payment.paymentMethodToken)}</dd></div>
                <div><dt>Capture mode</dt><dd>{humanize(payment.captureMode)}</dd></div>
                <div><dt>Captured</dt><dd className="num">{formatMoney(payment.capturedAmountMinor, payment.currency)}</dd></div>
                <div><dt>Refunded</dt><dd className="num">{formatMoney(payment.refundedAmountMinor, payment.currency)}</dd></div>
                <div><dt>Authorized at</dt><dd>{formatDateTime(payment.authorizedAt)}</dd></div>
                <div><dt>Captured at</dt><dd>{formatDateTime(payment.capturedAt)}</dd></div>
                <div><dt>Created</dt><dd>{formatDateTime(payment.createdAt)}</dd></div>
                <div><dt>Updated</dt><dd>{formatDateTime(payment.updatedAt)}</dd></div>
              </dl>
            </div>
          </section>
          <Actions payment={payment} />
          <section className="card">
            <div className="card-head"><h2>Refunds</h2></div>
            <div style={{ padding: '6px 4px 4px' }}><Refunds payment={payment} /></div>
          </section>
          <section className="card">
            <div className="card-head">
              <div><h2>Ledger entries</h2><p>Each journal balances: debits equal credits.</p></div>
              <Link className="btn btn-sm" to={`/ledger?paymentId=${payment.id}`}>Open in ledger</Link>
            </div>
            <div style={{ padding: '6px 4px 4px' }}><PaymentLedger paymentId={payment.id} /></div>
          </section>
        </div>
        <div className="stack" style={{ alignSelf: 'start' }}>
          <section className="card">
            <div className="card-head"><div><h2>History</h2><p>Every state transition, oldest first</p></div></div>
            <Timeline paymentId={payment.id} />
          </section>
          <PaymentWebhooks paymentId={payment.id} />
        </div>
      </div>
    </>
  );
}
