import { useState, type FormEvent } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { ApiError, newIdempotencyKey } from '../api/client';
import { useCreatePayment, useOrder, usePayments, useScenarios } from '../api/hooks';
import type { CaptureMode, Order } from '../api/types';
import { Icon } from '../components/Icons';
import { PaymentsTable } from '../components/PaymentsTable';
import { EmptyState, ErrorAlert, LoadingBlock, SuccessAlert } from '../components/States';
import { StatusBadge, humanize } from '../components/StatusBadge';
import { formatDateTime } from '../lib/format';
import { formatMoney } from '../lib/money';

function PaymentSimulator({ order }: { order: Order }) {
  const scenarios = useScenarios();
  const createPayment = useCreatePayment();
  const [token, setToken] = useState('tok_success');
  const [captureMode, setCaptureMode] = useState<CaptureMode>('AUTOMATIC');
  // Reused until the API answers, so retrying after a network failure can never double-charge.
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);

  function submit(e: FormEvent) {
    e.preventDefault();
    createPayment.mutate(
      { idempotencyKey, orderId: order.id, paymentMethodToken: token, captureMode },
      {
        onSuccess: () => setIdempotencyKey(newIdempotencyKey()),
        onError: (err) => {
          // Keep the key when the outcome is unknown (network error, 5xx, still in progress): retrying
          // with it returns the original payment instead of charging again. A definite 4xx rejection
          // created nothing, so the next attempt gets a new key.
          const keep = !(err instanceof ApiError) || err.status === 0 || err.status >= 500
            || err.code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS';
          if (!keep) setIdempotencyKey(newIdempotencyKey());
        },
      },
    );
  }

  const result = createPayment.data;
  const pendingScenario = scenarios.data?.scenarios.find((s) => s.token === createPayment.variables?.paymentMethodToken);

  return (
    <section className="card">
      <div className="card-head">
        <div>
          <h2>Payment simulator</h2>
          <p>Choose a fake payment token. Each one makes the simulated provider behave differently.</p>
        </div>
        <span className="pill pill-sim">Test tokens only</span>
      </div>
      <form className="card-body stack" onSubmit={submit}>
        {scenarios.isPending && <LoadingBlock rows={3} />}
        {scenarios.error && <ErrorAlert error={scenarios.error} title="Could not load simulator scenarios" />}
        {scenarios.data && (
          <>
            {scenarios.data.outage && (
              <div className="alert alert-warning"><Icon name="alert" /><div>Simulated provider outage is switched on: every call will fail.</div></div>
            )}
            <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="sr-only" style={{ position: 'absolute', left: -9999 }}>Scenario</legend>
              <div className="choices">
                {scenarios.data.scenarios.map((s) => (
                  <label className="choice" key={s.token}>
                    <input type="radio" name="scenario" value={s.token} checked={token === s.token} onChange={() => setToken(s.token)} />
                    <strong>{s.label}</strong>
                    <small>{s.description}</small>
                    <code className="faint" style={{ fontSize: 11 }}>{s.token}</code>
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="row spread">
              <div className="row">
                <span className="muted" style={{ fontSize: 13, fontWeight: 600 }}>Capture</span>
                <div className="segmented" role="radiogroup" aria-label="Capture mode">
                  {(['AUTOMATIC', 'MANUAL'] as const).map((m) => (
                    <label key={m}>
                      <input type="radio" name="capture" checked={captureMode === m} onChange={() => setCaptureMode(m)} />
                      {m === 'AUTOMATIC' ? 'Automatic' : 'Manual (authorize only)'}
                    </label>
                  ))}
                </div>
              </div>
              <span className="faint mono" style={{ fontSize: 11.5 }}>Idempotency-Key {idempotencyKey.slice(0, 13)}…</span>
              <button className="btn btn-primary" disabled={createPayment.isPending}>
                {createPayment.isPending && <span className="spinner" />}
                {createPayment.isPending ? 'Contacting simulated provider…' : `Pay ${formatMoney(order.amountMinor, order.currency)}`}
              </button>
            </div>
            {createPayment.isPending && pendingScenario?.outcome === 'TIMEOUT' && (
              <p className="faint" style={{ margin: 0 }}>Waiting for a provider that will not answer ({scenarios.data.timeoutMs} ms)…</p>
            )}
          </>
        )}
        {createPayment.error && <ErrorAlert error={createPayment.error} title="Payment was not created" />}
        {result && !createPayment.isPending && !createPayment.error && (
          ['CAPTURED', 'AUTHORIZED'].includes(result.status) ? (
            <SuccessAlert>
              Payment <strong>{humanize(result.status)}</strong>.{' '}
              <Link to={`/payments/${result.id}`}>Open payment</Link>
              {result.status === 'AUTHORIZED' && ' to capture or void it.'}
            </SuccessAlert>
          ) : (
            <div className="alert alert-warning" role="status">
              <Icon name={result.status === 'TIMED_OUT' ? 'clock' : 'alert'} />
              <div>
                Payment <strong>{humanize(result.status)}</strong>
                {result.failureCode && <> · <span className="mono">{result.failureCode}</span></>}.{' '}
                {result.status === 'TIMED_OUT'
                  ? 'The outcome is unknown, so a new attempt is blocked until you sync it with the provider. '
                  : 'Nothing was charged; you can try again. '}
                <Link to={`/payments/${result.id}`}>Open payment</Link>
              </div>
            </div>
          )
        )}
      </form>
    </section>
  );
}

export function OrderDetail() {
  const { orderId = '' } = useParams();
  const location = useLocation();
  const { data: order, isPending, error } = useOrder(orderId);
  const attempts = usePayments({ orderId, size: 50 });

  if (isPending) return <div className="card"><LoadingBlock rows={5} /></div>;
  if (error) return <ErrorAlert error={error} title="Could not load the order" />;

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs"><Link to="/orders">Orders</Link> / <span className="mono">{order.merchantReference}</span></div>
          <div className="row" style={{ gap: 12 }}>
            <span className="hero-amount">{formatMoney(order.amountMinor, order.currency)}</span>
            <StatusBadge status={order.status} />
          </div>
          <p>{order.description ?? 'No description'} · created {formatDateTime(order.createdAt)}</p>
        </div>
      </div>
      <div className="stack">
        {(location.state as { created?: boolean } | null)?.created && order.status === 'OPEN' && (
          <SuccessAlert>Order created. Now run a simulated payment for it.</SuccessAlert>
        )}
        {order.status === 'OPEN' ? (
          <PaymentSimulator order={order} />
        ) : (
          <div className="alert alert-info"><Icon name="check" /><div>This order is paid. Refunds are issued from the payment.</div></div>
        )}
        <section className="card">
          <div className="card-head"><div><h2>Payment attempts</h2><p>Failed attempts stay on record; only one can succeed.</p></div></div>
          {attempts.isPending && <LoadingBlock rows={2} />}
          {attempts.error && <div className="card-body"><ErrorAlert error={attempts.error} /></div>}
          {attempts.data && attempts.data.totalElements === 0 && <EmptyState title="No payment attempts yet" />}
          {attempts.data && attempts.data.totalElements > 0 && (
            <div style={{ padding: '6px 4px 4px' }}><PaymentsTable payments={attempts.data.content} /></div>
          )}
        </section>
      </div>
    </>
  );
}
