import { Link } from 'react-router-dom';
import { useDashboard } from '../api/hooks';
import { PAYMENT_STATUSES, type DashboardSummary } from '../api/types';
import { DailyOutcomesChart } from '../components/DailyOutcomesChart';
import { PaymentsTable } from '../components/PaymentsTable';
import { EmptyState, ErrorAlert, LoadingBlock, Skeleton } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { formatMoney } from '../lib/money';

function Kpis({ data }: { data: DashboardSummary }) {
  const [primary, ...others] = data.volumes;
  const pending = data.statusCounts.TIMED_OUT + data.statusCounts.CREATED;
  const awaitingCapture = data.statusCounts.AUTHORIZED;
  return (
    <div className="kpis">
      <div className="card kpi">
        <div className="kpi-label">Captured volume</div>
        <div className="kpi-value">{primary ? formatMoney(primary.capturedMinor, primary.currency) : '—'}</div>
        <div className="kpi-sub">{primary ? `${primary.capturedCount} captured payments` : 'No captures yet'}</div>
        {others.length > 0 && (
          <div className="kpi-sub">Also {others.map((v) => formatMoney(v.capturedMinor, v.currency)).join(' · ')}</div>
        )}
      </div>
      <div className="card kpi">
        <div className="kpi-label">Net after refunds</div>
        <div className="kpi-value">{primary ? formatMoney(primary.netMinor, primary.currency) : '—'}</div>
        <div className="kpi-sub">{primary ? `After ${formatMoney(primary.refundedMinor, primary.currency)} refunded` : '—'}</div>
        {others.length > 0 && (
          <div className="kpi-sub">Also {others.map((v) => formatMoney(v.netMinor, v.currency)).join(' · ')}</div>
        )}
      </div>
      <div className="card kpi">
        <div className="kpi-label">Authorization rate</div>
        <div className="kpi-value">{data.authorizationRatePercent === null ? '—' : `${data.authorizationRatePercent}%`}</div>
        <div className="kpi-sub">Approved ÷ (approved + declined + failed)</div>
      </div>
      <div className="card kpi">
        <div className="kpi-label">Payments</div>
        <div className="kpi-value num">{data.totalPayments}</div>
        <div className="kpi-sub">
          {data.totalOrders} orders · {awaitingCapture} awaiting capture{pending > 0 && ` · ${pending} unresolved`}
        </div>
      </div>
    </div>
  );
}

export function Dashboard() {
  const { data, isPending, error, isFetching } = useDashboard();

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p>
            Live figures computed by the API from stored payments.{' '}
            {isFetching && !isPending && <span className="faint">Refreshing…</span>}
          </p>
        </div>
        <Link className="btn btn-primary" to="/orders/new">New order</Link>
      </div>

      {error && <ErrorAlert error={error} title="Could not load the dashboard" />}

      {isPending && (
        <>
          <div className="kpis">
            {[0, 1, 2, 3].map((i) => (
              <div className="card kpi" key={i}><Skeleton width="50%" /><div style={{ height: 10 }} /><Skeleton height={26} width="70%" /></div>
            ))}
          </div>
          <div className="card"><LoadingBlock rows={6} /></div>
        </>
      )}

      {data && data.totalPayments === 0 && (
        <div className="card">
          <EmptyState
            title="No payments yet"
            action={<Link className="btn btn-primary" to="/orders/new">Create your first order</Link>}
          >
            Demo data may still be loading. You can also create an order and run a simulated payment.
          </EmptyState>
        </div>
      )}

      {data && data.totalPayments > 0 && (
        <>
          <Kpis data={data} />
          <div className="grid grid-3-1" style={{ marginBottom: 16 }}>
            <section className="card">
              <div className="card-head">
                <div>
                  <h2>Payments per day</h2>
                  <p>Last 14 days (UTC), by current outcome</p>
                </div>
              </div>
              <div className="card-body"><DailyOutcomesChart data={data.daily} /></div>
            </section>
            <section className="card">
              <div className="card-head"><h2>By status</h2></div>
              <div className="card-body">
                {PAYMENT_STATUSES.filter((s) => data.statusCounts[s] > 0).map((s) => (
                  <Link key={s} to={`/payments?status=${s}`} className="row spread" style={{ padding: '6px 0', color: 'inherit' }}>
                    <StatusBadge status={s} />
                    <span className="num" style={{ fontWeight: 600 }}>{data.statusCounts[s]}</span>
                  </Link>
                ))}
              </div>
            </section>
          </div>
          <div className="grid grid-3-1">
            <section className="card">
              <div className="card-head">
                <h2>Recent payments</h2>
                <Link to="/payments" className="btn btn-sm">View all</Link>
              </div>
              <div className="card-body" style={{ padding: '8px 4px 4px' }}>
                <PaymentsTable payments={data.recentPayments} compact />
              </div>
            </section>
            <section className="card">
              <div className="card-head">
                <div>
                  <h2>Failure reasons</h2>
                  <p>Declined, failed and timed-out payments</p>
                </div>
              </div>
              <div className="card-body">
                {data.failureReasons.length === 0 ? (
                  <p className="muted" style={{ margin: 0 }}>No failures recorded.</p>
                ) : (
                  data.failureReasons.map((r) => {
                    const max = data.failureReasons[0].count;
                    return (
                      <div className="hbar" key={r.code}>
                        <span className="mono muted" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.code}</span>
                        <div className="hbar-track"><div className="hbar-fill" style={{ width: `${(r.count / max) * 100}%` }} /></div>
                        <span className="num" style={{ fontWeight: 600 }}>{r.count}</span>
                      </div>
                    );
                  })
                )}
              </div>
            </section>
          </div>
        </>
      )}
    </>
  );
}
