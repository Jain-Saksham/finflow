import { useState } from 'react';
import { SimulationBanner } from '../components/Layout';
import { ErrorAlert } from '../components/States';
import { useSession } from '../session';

export function Welcome() {
  const { startDemo } = useSession();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function enter() {
    setBusy(true);
    setError(null);
    try {
      await startDemo();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <SimulationBanner />
      <div className="welcome">
        <div className="card welcome-card">
          <div className="brand" style={{ padding: 0 }}>
            <div className="brand-mark" aria-hidden="true">F</div>
            <span>FinFlow</span>
            <span className="pill pill-sim">Simulator</span>
          </div>
          <h1>Payment orchestration, simulated end to end.</h1>
          <p className="muted" style={{ margin: 0 }}>
            Explore a demo merchant account backed by a real Spring Boot API and PostgreSQL. Payments go to a
            simulated provider using fake tokens. No card data is collected and no money moves.
          </p>
          <ul>
            <li>Create orders and run payments that succeed, decline, time out or hit a provider outage</li>
            <li>Inspect state transitions, capture or void authorizations, issue partial and full refunds</li>
            <li>See every capture and refund posted to a double-entry ledger</li>
            <li>Watch the dashboard update from live API data</li>
          </ul>
          {error !== null && <div style={{ marginBottom: 14 }}><ErrorAlert error={error} title="Could not start the demo" /></div>}
          <div className="row">
            <button className="btn btn-primary btn-lg" onClick={enter} disabled={busy}>
              {busy && <span className="spinner" />} Enter demo merchant
            </button>
            <a className="btn btn-lg" href="/swagger-ui.html" target="_blank" rel="noreferrer">API docs</a>
          </div>
          <p className="faint" style={{ fontSize: 12.5, marginTop: 16, marginBottom: 0 }}>
            The demo account is shared by all visitors and its data is synthetic.
          </p>
        </div>
      </div>
    </>
  );
}
