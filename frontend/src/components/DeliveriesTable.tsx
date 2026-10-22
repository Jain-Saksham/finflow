import { useNavigate } from 'react-router-dom';
import type { WebhookDelivery } from '../api/types';
import { formatRelative, shortId } from '../lib/format';
import { DeliveryBadge } from './DeliveryBadge';

export function DeliveriesTable({ deliveries }: { deliveries: WebhookDelivery[] }) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr><th>Event</th><th>Status</th><th className="right">Attempts</th><th>Last response</th><th>Next retry</th><th>Created</th></tr>
        </thead>
        <tbody>
          {deliveries.map((d) => (
            <tr key={d.id} className="clickable" onClick={() => navigate(`/webhooks/deliveries/${d.id}`)}>
              <td>
                <span className="mono">{d.eventType}</span>
                <div className="faint mono" style={{ fontSize: 11.5 }}>evt {shortId(d.eventId)}</div>
              </td>
              <td><DeliveryBadge status={d.status} /></td>
              <td className="right num">{d.attemptCount}</td>
              <td className="mono muted">{d.lastResponseStatus ?? (d.lastError ? 'error' : '—')}</td>
              <td className="muted nowrap">{d.status === 'RETRYING' && d.nextAttemptAt ? formatRelative(d.nextAttemptAt) : '—'}</td>
              <td className="muted nowrap">{formatRelative(d.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
