import { useNavigate } from 'react-router-dom';
import type { Payment } from '../api/types';
import { formatRelative, shortId, tokenLabel } from '../lib/format';
import { formatMoney } from '../lib/money';
import { StatusBadge } from './StatusBadge';

export function PaymentsTable({ payments, compact = false }: { payments: Payment[]; compact?: boolean }) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Payment</th>
            <th className="right">Amount</th>
            <th>Status</th>
            {!compact && <th>Scenario</th>}
            {!compact && <th>Capture</th>}
            {!compact && <th>Reason</th>}
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          {payments.map((p) => (
            <tr key={p.id} className="clickable" onClick={() => navigate(`/payments/${p.id}`)}>
              <td>
                <a href={`/payments/${p.id}`} className="mono" onClick={(e) => { e.preventDefault(); navigate(`/payments/${p.id}`); }}>
                  {shortId(p.id)}
                </a>
              </td>
              <td className="right num nowrap">
                {formatMoney(p.amountMinor, p.currency)}
                {p.refundedAmountMinor > 0 && (
                  <div className="faint" style={{ fontSize: 12 }}>−{formatMoney(p.refundedAmountMinor, p.currency)} refunded</div>
                )}
              </td>
              <td><StatusBadge status={p.status} /></td>
              {!compact && <td className="muted nowrap">{tokenLabel(p.paymentMethodToken)}</td>}
              {!compact && <td className="muted">{p.captureMode === 'AUTOMATIC' ? 'Auto' : 'Manual'}</td>}
              {!compact && <td className="mono faint">{p.failureCode ?? ''}</td>}
              <td className="muted nowrap" title={new Date(p.createdAt).toLocaleString()}>{formatRelative(p.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
