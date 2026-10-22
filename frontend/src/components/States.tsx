import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import { Icon } from './Icons';

export function Skeleton({ height = 16, width = '100%' }: { height?: number; width?: number | string }) {
  return <div className="skeleton" style={{ height, width }} aria-hidden="true" />;
}

export function LoadingBlock({ rows = 4 }: { rows?: number }) {
  return (
    <div className="card-body stack" style={{ gap: 10 }} role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height={18} width={`${90 - i * 7}%`} />
      ))}
    </div>
  );
}

export function ErrorAlert({ error, title = 'Request failed' }: { error: unknown; title?: string }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <div className="alert alert-error" role="alert">
      <Icon name="alert" />
      <div>
        <div className="alert-title">{title}</div>
        <div>{apiError?.detail ?? (error instanceof Error ? error.message : 'Something went wrong.')}</div>
        {apiError && (
          <div className="mono" style={{ marginTop: 4, opacity: 0.85 }}>
            {apiError.status > 0 && `HTTP ${apiError.status} · `}
            {apiError.code}
            {apiError.requestId && ` · request ${apiError.requestId.slice(0, 8)}`}
          </div>
        )}
      </div>
    </div>
  );
}

export function SuccessAlert({ children }: { children: ReactNode }) {
  return (
    <div className="alert alert-success" role="status">
      <Icon name="check" />
      <div>{children}</div>
    </div>
  );
}

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children}
      {action && <div>{action}</div>}
    </div>
  );
}
