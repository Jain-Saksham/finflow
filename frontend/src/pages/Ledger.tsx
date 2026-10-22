import { Link, useSearchParams } from 'react-router-dom';
import { useLedgerBalances, useLedgerEntries } from '../api/hooks';
import { Icon } from '../components/Icons';
import { Pagination } from '../components/Pagination';
import { EmptyState, ErrorAlert, LoadingBlock } from '../components/States';
import { humanize } from '../components/StatusBadge';
import { formatDateTime, shortId } from '../lib/format';
import { formatMoney } from '../lib/money';

const ACCOUNT_HELP = {
  PROVIDER_RECEIVABLE: 'Asset: owed to us by the provider for captured payments',
  MERCHANT_BALANCE: 'Liability: owed by us to the merchant',
} as const;

function Balances() {
  const { data, isPending, error } = useLedgerBalances();
  if (isPending) return <div className="card"><LoadingBlock rows={3} /></div>;
  if (error) return <ErrorAlert error={error} title="Could not load balances" />;
  if (data.checks.length === 0) return null;
  return (
    <div className="grid grid-2">
      {data.checks.map((check) => (
        <section className="card" key={check.currency}>
          <div className="card-head">
            <h2>{check.currency}</h2>
            {check.balanced ? (
              <span className="badge tone-good with-icon"><Icon name="check" size={12} /> Balanced</span>
            ) : (
              <span className="badge tone-critical with-icon"><Icon name="alert" size={12} /> Out of balance</span>
            )}
          </div>
          <div className="card-body">
            <div className="table-wrap">
              <table>
                <thead><tr><th>Account</th><th className="right">Debits</th><th className="right">Credits</th><th className="right">Balance</th></tr></thead>
                <tbody>
                  {data.accounts.filter((a) => a.currency === check.currency).map((a) => (
                    <tr key={a.account}>
                      <td><span className="mono">{a.account}</span><div className="faint" style={{ fontSize: 12 }}>{ACCOUNT_HELP[a.account]}</div></td>
                      <td className="right num">{formatMoney(a.debitsMinor, a.currency)}</td>
                      <td className="right num">{formatMoney(a.creditsMinor, a.currency)}</td>
                      <td className="right num" style={{ fontWeight: 600 }}>{formatMoney(a.balanceMinor, a.currency)}</td>
                    </tr>
                  ))}
                  <tr>
                    <td className="muted">Total</td>
                    <td className="right num muted">{formatMoney(check.totalDebitsMinor, check.currency)}</td>
                    <td className="right num muted">{formatMoney(check.totalCreditsMinor, check.currency)}</td>
                    <td />
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </section>
      ))}
    </div>
  );
}

export function Ledger() {
  const [params, setParams] = useSearchParams();
  const paymentId = params.get('paymentId') ?? undefined;
  const page = Number(params.get('page') ?? 0) || 0;
  const { data, isPending, error, isFetching } = useLedgerEntries({ paymentId, page, size: 25 });

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Ledger</h1>
          <p>Double-entry, append-only. Captures debit the provider receivable and credit the merchant; refunds reverse that.</p>
        </div>
      </div>
      <div className="stack">
        {!paymentId && <Balances />}
        <section className="card">
          <div className="card-head">
            <div>
              <h2>Entries</h2>
              {paymentId && <p>Filtered to payment <span className="mono">{shortId(paymentId)}</span></p>}
            </div>
            {paymentId && <button className="btn btn-sm" onClick={() => setParams({})}>Clear filter</button>}
          </div>
          {error && <div className="card-body"><ErrorAlert error={error} title="Could not load ledger entries" /></div>}
          {isPending && <LoadingBlock rows={8} />}
          {data && data.totalElements === 0 && (
            <EmptyState title="No ledger entries yet">Entries appear when a payment is captured or refunded.</EmptyState>
          )}
          {data && data.totalElements > 0 && (
            <>
              <div className="table-wrap" style={{ padding: '6px 4px 0' }}>
                <table>
                  <thead>
                    <tr><th>#</th><th>Posted</th><th>Journal</th><th>Account</th><th className="right">Debit</th><th className="right">Credit</th><th>Payment</th></tr>
                  </thead>
                  <tbody>
                    {data.content.map((e) => (
                      <tr key={e.id}>
                        <td className="num faint">{e.id}</td>
                        <td className="muted nowrap">{formatDateTime(e.createdAt)}</td>
                        <td className="nowrap">{humanize(e.journalType)} <span className="mono faint">{shortId(e.journalId)}</span></td>
                        <td className="mono">{e.account}</td>
                        <td className="right num">{e.direction === 'DEBIT' ? formatMoney(e.amountMinor, e.currency) : ''}</td>
                        <td className="right num">{e.direction === 'CREDIT' ? formatMoney(e.amountMinor, e.currency) : ''}</td>
                        <td><Link className="mono" to={`/payments/${e.paymentId}`}>{shortId(e.paymentId)}</Link></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination
                page={data}
                busy={isFetching}
                onChange={(p) => {
                  const next = new URLSearchParams(params);
                  if (p) next.set('page', String(p)); else next.delete('page');
                  setParams(next);
                }}
              />
            </>
          )}
        </section>
      </div>
    </>
  );
}
