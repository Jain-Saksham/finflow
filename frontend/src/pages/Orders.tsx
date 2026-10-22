import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useOrders } from '../api/hooks';
import type { OrderStatus } from '../api/types';
import { Pagination } from '../components/Pagination';
import { EmptyState, ErrorAlert, LoadingBlock } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatRelative } from '../lib/format';
import { formatMoney } from '../lib/money';

export function Orders() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') ?? '') as OrderStatus | '';
  const page = Number(params.get('page') ?? 0) || 0;
  const { data, isPending, error, isFetching } = useOrders({ status, page, size: 20 });

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)));
    setParams(merged);
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Orders</h1>
          <p>Orders stay open until a payment for them is captured.</p>
        </div>
        <div className="row">
          <select className="select-inline" aria-label="Filter by status" value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}>
            <option value="">All orders</option>
            <option value="OPEN">Open</option>
            <option value="PAID">Paid</option>
          </select>
          <Link className="btn btn-primary" to="/orders/new">New order</Link>
        </div>
      </div>
      <div className="card">
        {error && <div className="card-body"><ErrorAlert error={error} title="Could not load orders" /></div>}
        {isPending && <LoadingBlock rows={8} />}
        {data && data.totalElements === 0 && (
          <EmptyState title="No orders" action={<Link className="btn btn-primary" to="/orders/new">Create an order</Link>} />
        )}
        {data && data.totalElements > 0 && (
          <>
            <div className="table-wrap" style={{ padding: '6px 4px 0' }}>
              <table>
                <thead>
                  <tr><th>Reference</th><th className="right">Amount</th><th>Status</th><th>Description</th><th>Created</th></tr>
                </thead>
                <tbody>
                  {data.content.map((o) => (
                    <tr key={o.id} className="clickable" onClick={() => navigate(`/orders/${o.id}`)}>
                      <td className="mono">{o.merchantReference}</td>
                      <td className="right num nowrap">{formatMoney(o.amountMinor, o.currency)}</td>
                      <td><StatusBadge status={o.status} /></td>
                      <td className="muted">{o.description ?? '—'}</td>
                      <td className="muted nowrap">{formatRelative(o.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={data} busy={isFetching} onChange={(p) => update({ page: p ? String(p) : '' })} />
          </>
        )}
      </div>
    </>
  );
}
