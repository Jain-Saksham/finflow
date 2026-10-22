import type { OrderStatus, PaymentStatus, RefundStatus } from '../api/types';

type Tone = 'good' | 'warning' | 'critical' | 'info' | 'neutral' | 'violet';

const TONES: Record<PaymentStatus | OrderStatus | RefundStatus, Tone> = {
  CREATED: 'neutral',
  AUTHORIZED: 'info',
  CAPTURED: 'good',
  PARTIALLY_REFUNDED: 'violet',
  REFUNDED: 'violet',
  DECLINED: 'critical',
  FAILED: 'critical',
  TIMED_OUT: 'warning',
  VOIDED: 'neutral',
  OPEN: 'info',
  PAID: 'good',
  PENDING: 'warning',
  SUCCEEDED: 'good',
};

export function humanize(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ');
}

/** Status is always shown as text; the color only reinforces it. */
export function StatusBadge({ status }: { status: PaymentStatus | OrderStatus | RefundStatus }) {
  return <span className={`badge tone-${TONES[status] ?? 'neutral'}`}>{humanize(status)}</span>;
}
