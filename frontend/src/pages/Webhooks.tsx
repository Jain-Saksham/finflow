import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useCreateWebhookEndpoint, useToggleWebhookEndpoint, useWebhookDeliveries, useWebhookEndpoints } from '../api/hooks';
import type { DeliveryStatus, SimulatedBehavior } from '../api/types';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { Icon } from '../components/Icons';
import { Pagination } from '../components/Pagination';
import { EmptyState, ErrorAlert, LoadingBlock } from '../components/States';
import { humanize } from '../components/StatusBadge';

const BEHAVIORS: { value: SimulatedBehavior; label: string }[] = [
  { value: 'ALWAYS_SUCCEED', label: 'Always succeeds' },
  { value: 'FAIL_FIRST_ATTEMPT', label: 'Fails the first attempt' },
  { value: 'FAIL_FIRST_THREE', label: 'Fails the first three attempts' },
  { value: 'ALWAYS_FAIL', label: 'Always fails (dead delivery)' },
];
const STATUSES: DeliveryStatus[] = ['PENDING', 'IN_PROGRESS', 'RETRYING', 'SUCCEEDED', 'FAILED'];

function Endpoints() {
  const endpoints = useWebhookEndpoints();
  const create = useCreateWebhookEndpoint();
  const toggle = useToggleWebhookEndpoint();
  const [behavior, setBehavior] = useState<SimulatedBehavior>('FAIL_FIRST_ATTEMPT');

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate({ simulatedBehavior: behavior, description: BEHAVIORS.find((b) => b.value === behavior)?.label });
  }

  return (
    <section className="card">
      <div className="card-head">
        <div>
          <h2>Endpoints</h2>
          <p>Every payment event is POSTed, HMAC-signed, to each enabled endpoint.</p>
        </div>
      </div>
      <div className="card-body stack" style={{ gap: 12 }}>
        <div className="alert alert-info">
          <Icon name="flask" />
          <div>
            The demo delivers only to FinFlow's built-in <strong>simulated receiver</strong>, which verifies the
            signature, deduplicates on the event id and fails on purpose according to its behavior. External URLs are
            disabled on this deployment.
          </div>
        </div>
        {endpoints.isPending && <LoadingBlock rows={2} />}
        {endpoints.error && <ErrorAlert error={endpoints.error} />}
        {endpoints.data?.map((ep) => (
          <div key={ep.id} className="row spread" style={{ borderBottom: '1px solid var(--border)', paddingBottom: 10 }}>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600 }}>{ep.description ?? 'Webhook endpoint'}</div>
              <div className="mono faint" style={{ fontSize: 11.5, overflowWrap: 'anywhere' }}>{ep.url}</div>
              <div className="faint" style={{ fontSize: 12 }}>
                Secret <span className="mono">{ep.secretPreview}</span>
                {ep.simulatedBehavior && <> · {humanize(ep.simulatedBehavior)}</>}
              </div>
            </div>
            <div className="row">
              <span className={`badge ${ep.enabled ? 'tone-good' : 'tone-neutral'}`}>{ep.enabled ? 'Enabled' : 'Disabled'}</span>
              <button className="btn btn-sm" disabled={toggle.isPending}
                onClick={() => toggle.mutate({ id: ep.id, enable: !ep.enabled })}>
                {ep.enabled ? 'Disable' : 'Enable'}
              </button>
            </div>
          </div>
        ))}
        <form className="row" onSubmit={submit}>
          <select className="select-inline" aria-label="Receiver behavior" value={behavior}
            onChange={(e) => setBehavior(e.target.value as SimulatedBehavior)}>
            {BEHAVIORS.map((b) => <option key={b.value} value={b.value}>{b.label}</option>)}
          </select>
          <button className="btn" disabled={create.isPending}>Add simulated endpoint</button>
        </form>
        {create.error && <ErrorAlert error={create.error} title="Could not add the endpoint" />}
        {create.data?.secret && (
          <div className="alert alert-warning">
            <Icon name="alert" />
            <div>
              Signing secret, shown only once: <span className="mono">{create.data.secret}</span>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

export function Webhooks() {
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') ?? '') as DeliveryStatus | '';
  const page = Number(params.get('page') ?? 0) || 0;
  const deliveries = useWebhookDeliveries({ status, page, size: 15 });

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Webhooks</h1>
          <p>Events flow: payment change → outbox (same DB transaction) → Kafka → fan-out → signed delivery with retries.</p>
        </div>
      </div>
      <div className="grid grid-3-1">
        <section className="card" style={{ alignSelf: 'start' }}>
          <div className="card-head">
            <div><h2>Deliveries</h2><p>Refreshes every few seconds while retries run.</p></div>
            <select className="select-inline" aria-label="Filter by status" value={status}
              onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
              <option value="">All statuses</option>
              {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
            </select>
          </div>
          {deliveries.error && <div className="card-body"><ErrorAlert error={deliveries.error} /></div>}
          {deliveries.isPending && <LoadingBlock rows={8} />}
          {deliveries.data && deliveries.data.totalElements === 0 && (
            <EmptyState title="No deliveries">Deliveries appear a moment after payment events, once an endpoint exists.</EmptyState>
          )}
          {deliveries.data && deliveries.data.totalElements > 0 && (
            <>
              <div style={{ padding: '6px 4px 0' }}><DeliveriesTable deliveries={deliveries.data.content} /></div>
              <Pagination page={deliveries.data} busy={deliveries.isFetching}
                onChange={(p) => setParams({ ...(status ? { status } : {}), ...(p ? { page: String(p) } : {}) })} />
            </>
          )}
        </section>
        <Endpoints />
      </div>
    </>
  );
}
