import { useSearchParams } from 'react-router-dom';
import { usePayments } from '../api/hooks';
import { PAYMENT_STATUSES, type PaymentStatus } from '../api/types';
import { Pagination } from '../components/Pagination';
import { PaymentsTable } from '../components/PaymentsTable';
import { EmptyState, ErrorAlert, LoadingBlock } from '../components/States';
import { humanize } from '../components/StatusBadge';

export function Payments() {
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') ?? '') as PaymentStatus | '';
  const page = Number(params.get('page') ?? 0) || 0;
  const { data, isPending, error, isFetching } = usePayments({ status, page, size: 20 });

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)));
    setParams(merged);
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Transactions</h1>
          <p>Every payment attempt, newest first. Select one to see its history and act on it.</p>
        </div>
        <select
          className="select-inline"
          aria-label="Filter by status"
          value={status}
          onChange={(e) => update({ status: e.target.value, page: '' })}
        >
          <option value="">All statuses</option>
          {PAYMENT_STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </select>
      </div>
      <div className="card">
        {error && <div className="card-body"><ErrorAlert error={error} title="Could not load transactions" /></div>}
        {isPending && <LoadingBlock rows={8} />}
        {data && data.totalElements === 0 && (
          <EmptyState title={status ? `No ${humanize(status).toLowerCase()} payments` : 'No payments yet'}>
            {status ? 'Try another status filter.' : 'Create an order and run a simulated payment.'}
          </EmptyState>
        )}
        {data && data.totalElements > 0 && (
          <>
            <div style={{ padding: '6px 4px 0' }}><PaymentsTable payments={data.content} /></div>
            <Pagination page={data} busy={isFetching} onChange={(p) => update({ page: p ? String(p) : '' })} />
          </>
        )}
      </div>
    </>
  );
}
