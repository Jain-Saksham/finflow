import { useState } from 'react';
import { Link } from 'react-router-dom';
import { downloadFile } from '../api/client';
import { useReconciliationReport, useReconciliationRuns, useRunReconciliation } from '../api/hooks';
import type { ReconciliationFinding } from '../api/types';
import { Icon } from '../components/Icons';
import { EmptyState, ErrorAlert, LoadingBlock } from '../components/States';
import { humanize } from '../components/StatusBadge';
import { formatDateTime, shortId } from '../lib/format';

const SEVERITY_TONE = { INFO: 'neutral', WARNING: 'warning', CRITICAL: 'critical' } as const;
const RESOLUTION_TONE = { AUTO_RESOLVED: 'good', OPEN: 'critical', RESOLUTION_FAILED: 'critical' } as const;

function Findings({ findings }: { findings: ReconciliationFinding[] }) {
  if (findings.length === 0) {
    return <EmptyState title="No discrepancies">FinFlow and the provider agree on every payment and refund.</EmptyState>;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Finding</th><th>Severity</th><th>Resolution</th><th>Payment</th><th>Expected → actual</th></tr></thead>
        <tbody>
          {findings.map((f) => (
            <tr key={f.id}>
              <td style={{ minWidth: 240 }}>
                <div className="mono" style={{ fontWeight: 600, fontSize: 12.5 }}>{f.type}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{f.detail}</div>
                {f.resolutionDetail && <div className="faint" style={{ fontSize: 12 }}>↳ {f.resolutionDetail}</div>}
              </td>
              <td><span className={`badge tone-${SEVERITY_TONE[f.severity]}`}>{humanize(f.severity)}</span></td>
              <td>
                <span className={`badge tone-${RESOLUTION_TONE[f.resolution]}`}>{humanize(f.resolution)}</span>
                <div className="faint" style={{ fontSize: 11.5 }}>{f.recoverable ? 'Recoverable' : 'Needs a human'}</div>
              </td>
              <td>{f.paymentId ? <Link className="mono" to={`/payments/${f.paymentId}`}>{shortId(f.paymentId)}</Link> : '—'}</td>
              <td className="mono faint" style={{ fontSize: 12 }}>{f.expected ?? '—'} → {f.actual ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Reconciliation() {
  const runs = useReconciliationRuns();
  const runNow = useRunReconciliation();
  const [selected, setSelected] = useState<string | undefined>();
  const runId = selected ?? runs.data?.content[0]?.id;
  const report = useReconciliationReport(runId);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Reconciliation</h1>
          <p>Compares FinFlow's payments, refunds and ledger with the simulated provider's independent records.</p>
        </div>
        <button className="btn btn-primary" onClick={() => runNow.mutate(undefined, { onSuccess: (r) => setSelected(r.run.id) })}
          disabled={runNow.isPending}>
          {runNow.isPending && <span className="spinner" />} Run reconciliation
        </button>
      </div>
      <div className="stack">
        {runNow.error && <ErrorAlert error={runNow.error} title="Reconciliation did not run" />}
        <div className="alert alert-info">
          <Icon name="flask" />
          <div>
            Safe discrepancies are repaired automatically, such as a payment left unresolved by a timeout or crash, or a
            refund stuck pending. Anything that implies money moved wrongly at the provider stays open for a human. The
            demo's provider records include a few <strong>deliberately injected anomalies</strong>.
          </div>
        </div>
        {runs.isPending && <div className="card"><LoadingBlock rows={4} /></div>}
        {runs.error && <ErrorAlert error={runs.error} />}
        {runs.data && runs.data.totalElements === 0 && (
          <div className="card"><EmptyState title="No runs yet">Run reconciliation to produce the first report.</EmptyState></div>
        )}
        {report.data && (
          <section className="card">
            <div className="card-head">
              <div>
                <h2>Report · {formatDateTime(report.data.run.startedAt)}</h2>
                <p>{humanize(report.data.run.trigger)} run · {humanize(report.data.run.status)}</p>
              </div>
              <button className="btn btn-sm"
                onClick={() => downloadFile(`/api/v1/reconciliation/runs/${report.data.run.id}/report.csv`,
                  `reconciliation-${report.data.run.id}.csv`)}>
                Download CSV
              </button>
            </div>
            <div className="card-body">
              <div className="kpis" style={{ marginBottom: 12 }}>
                <div className="kpi" style={{ padding: 0 }}><div className="kpi-label">Provider records</div><div className="kpi-value">{report.data.run.providerTransactions ?? '—'}</div></div>
                <div className="kpi" style={{ padding: 0 }}><div className="kpi-label">Payments matched</div><div className="kpi-value">{report.data.run.matchedPayments ?? '—'}<span className="faint" style={{ fontSize: 14 }}> / {report.data.run.internalPayments}</span></div></div>
                <div className="kpi" style={{ padding: 0 }}><div className="kpi-label">Auto-resolved</div><div className="kpi-value">{report.data.run.autoResolved ?? 0}</div></div>
                <div className="kpi" style={{ padding: 0 }}><div className="kpi-label">Open findings</div><div className="kpi-value">{report.data.run.openFindings ?? 0}</div></div>
              </div>
              {!!report.data.run.inFlightSkipped && (
                <div className="alert alert-info" style={{ marginBottom: 8 }}>
                  <Icon name="clock" />
                  <div>
                    {report.data.run.inFlightSkipped} payment(s) younger than {report.data.run.staleAfterSeconds}s were
                    left alone because their request may still be in flight. Run again after that window to include them.
                  </div>
                </div>
              )}
              {report.data.run.error && <ErrorAlert error={new Error(report.data.run.error)} title="Run failed" />}
            </div>
            <div style={{ padding: '0 4px 4px' }}><Findings findings={report.data.findings} /></div>
          </section>
        )}
        {runs.data && runs.data.totalElements > 0 && (
          <section className="card">
            <div className="card-head"><h2>History</h2></div>
            <div className="table-wrap" style={{ padding: '6px 4px 4px' }}>
              <table>
                <thead><tr><th>Started</th><th>Trigger</th><th>Status</th><th className="right">Findings</th><th className="right">Auto-resolved</th><th className="right">Open</th></tr></thead>
                <tbody>
                  {runs.data.content.map((r) => (
                    <tr key={r.id} className="clickable" onClick={() => setSelected(r.id)}
                      style={r.id === runId ? { background: 'var(--accent-soft)' } : undefined}>
                      <td className="nowrap">{formatDateTime(r.startedAt)}</td>
                      <td className="muted">{humanize(r.trigger)}</td>
                      <td>{humanize(r.status)}</td>
                      <td className="right num">{r.findingsTotal ?? '—'}</td>
                      <td className="right num">{r.autoResolved ?? '—'}</td>
                      <td className="right num">{r.openFindings ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </div>
    </>
  );
}
