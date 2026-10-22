import type { DeliveryStatus } from '../api/types';
import { humanize } from './StatusBadge';

const TONES: Record<DeliveryStatus, string> = {
  PENDING: 'neutral',
  IN_PROGRESS: 'info',
  RETRYING: 'warning',
  SUCCEEDED: 'good',
  FAILED: 'critical',
};

export function DeliveryBadge({ status }: { status: DeliveryStatus }) {
  return <span className={`badge tone-${TONES[status]}`}>{humanize(status)}</span>;
}
