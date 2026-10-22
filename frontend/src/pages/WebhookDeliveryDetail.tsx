import { Link, useParams } from 'react-router-dom';
import { useRedeliver, useWebhookDelivery } from '../api/hooks';
import { DeliveryBadge } from '../components/DeliveryBadge';
import { ErrorAlert, LoadingBlock, SuccessAlert } from '../components/States';
import { formatDateTime, shortId } from '../lib/format';

export function WebhookDeliveryDetail() {
  const { deliveryId = '' } = useParams();
  const { data, isPending, error } = useWebhookDelivery(deliveryId);
  const redeliver = useRedeliver(deliveryId);

  if (isPending) return <div className="card"><LoadingBlock rows={6} /></div>;
  if (error) return <ErrorAlert error={error} title="Could not load the delivery" />;
  const d = data.delivery;
  const canRedeliver = d.status === 'FAILED' || d.status === 'SUCCEEDED';

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs"><Link to="/webhooks">Webhooks</Link> / <span className="mono">{shortId(d.id)}</span></div>
          <div className="row" style={{ gap: 12 }}>
            <h1 className="mono" style={{ fontSize: 20 }}>{d.eventType}</h1>
            <DeliveryBadge status={d.status} />
          </div>
          <p>Attempt {d.attemptCount} of {data.maxAttempts} in the current cycle</p>
        </div>
        {canRedeliver && (
          <button className="btn" onClick={() => redeliver.mutate()} disabled={redeliver.isPending}>Redeliver</button>
        )}
      </div>
      <div className="stack">
        {redeliver.error && <ErrorAlert error={redeliver.error} />}
        {redeliver.isSuccess && <SuccessAlert>Queued for redelivery. The receiver should deduplicate it by event id.</SuccessAlert>}
        <section className="card">
          <div className="card-body">
            <dl className="facts">
              <div><dt>Event id (dedup key)</dt><dd className="mono">{d.eventId}</dd></div>
              <div><dt>Delivery id</dt><dd className="mono">{d.id}</dd></div>
              <div><dt>Endpoint</dt><dd className="mono" style={{ fontSize: 12 }}>{d.endpointUrl}</dd></div>
              <div><dt>Payment</dt><dd>{d.paymentId ? <Link className="mono" to={`/payments/${d.paymentId}`}>{shortId(d.paymentId)}</Link> : '—'}</dd></div>
              <div><dt>Created</dt><dd>{formatDateTime(d.createdAt)}</dd></div>
              <div><dt>Next attempt</dt><dd>{d.status === 'RETRYING' ? formatDateTime(d.nextAttemptAt) : '—'}</dd></div>
            </dl>
          </div>
        </section>
        <section className="card">
          <div className="card-head"><div><h2>Attempts</h2><p>Every HTTP attempt is recorded, including failures.</p></div></div>
          <div className="table-wrap" style={{ padding: '6px 4px 4px' }}>
            {data.attempts.length === 0 ? (
              <p className="muted" style={{ padding: '8px 12px' }}>No attempts yet.</p>
            ) : (
              <table>
                <thead><tr><th>#</th><th>Outcome</th><th>HTTP</th><th className="right">Duration</th><th>Response / error</th><th>At</th></tr></thead>
                <tbody>
                  {data.attempts.map((a) => (
                    <tr key={a.attemptNo}>
                      <td className="num">{a.attemptNo}</td>
                      <td><span className={`badge ${a.outcome === 'SUCCEEDED' ? 'tone-good' : 'tone-critical'}`}>{a.outcome === 'SUCCEEDED' ? 'Succeeded' : 'Failed'}</span></td>
                      <td className="mono">{a.responseStatus ?? '—'}</td>
                      <td className="right num">{a.durationMs} ms</td>
                      <td className="mono faint" style={{ fontSize: 12, maxWidth: 360, overflowWrap: 'anywhere' }}>{a.responseBody || a.error}</td>
                      <td className="muted nowrap">{formatDateTime(a.startedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
      </div>
    </>
  );
}
