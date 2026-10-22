import { NavLink, Outlet } from 'react-router-dom';
import { useMerchant } from '../api/hooks';
import { useSession } from '../session';
import { Icon } from './Icons';

export function SimulationBanner() {
  return (
    <div className="sim-banner" role="note">
      <Icon name="flask" size={14} />
      <strong>SIMULATION</strong>
      <span>Demo merchant · fake payment tokens · simulated provider · no real money moves</span>
    </div>
  );
}

const NAV = [
  { to: '/', label: 'Dashboard', icon: 'dashboard', end: true },
  { to: '/payments', label: 'Transactions', icon: 'payments', end: false },
  { to: '/orders', label: 'Orders', icon: 'orders', end: false },
  { to: '/orders/new', label: 'New order', icon: 'plus', end: true },
  { to: '/ledger', label: 'Ledger', icon: 'ledger', end: false },
] as const;

export function Layout() {
  const { signOut } = useSession();
  const merchant = useMerchant();

  return (
    <>
      <SimulationBanner />
      <div className="shell">
        <aside className="sidebar">
          <div className="brand">
            <div className="brand-mark" aria-hidden="true">F</div>
            <span>FinFlow</span>
          </div>
          <nav className="nav" aria-label="Main">
            {NAV.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} title={item.label}>
                <Icon name={item.icon} />
                <span>{item.label}</span>
              </NavLink>
            ))}
            <a href="/swagger-ui.html" target="_blank" rel="noreferrer" title="API docs">
              <Icon name="book" />
              <span>API docs</span>
            </a>
          </nav>
          <div className="sidebar-foot">
            <div style={{ fontWeight: 600 }}>{merchant.data?.name ?? '…'}</div>
            <div className="row" style={{ gap: 6, marginTop: 4 }}>
              {merchant.data?.demo && <span className="pill pill-sim">Demo account</span>}
              <span className="faint mono">{merchant.data?.apiKeyPrefix}…</span>
            </div>
            <button className="btn btn-sm" style={{ marginTop: 10, width: '100%' }} onClick={signOut}>
              <Icon name="logout" size={14} /> Leave demo
            </button>
          </div>
        </aside>
        <main className="main">
          <Outlet />
        </main>
      </div>
    </>
  );
}
