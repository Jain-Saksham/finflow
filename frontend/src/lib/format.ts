export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function formatRelative(iso: string): string {
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  if (Math.abs(seconds) < 60) return rtf.format(-seconds, 'second');
  if (Math.abs(seconds) < 3600) return rtf.format(-Math.round(seconds / 60), 'minute');
  if (Math.abs(seconds) < 86400) return rtf.format(-Math.round(seconds / 3600), 'hour');
  return rtf.format(-Math.round(seconds / 86400), 'day');
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** Human label for a simulator token, e.g. tok_decline_expired_card -> "decline expired card". */
export function tokenLabel(token: string): string {
  return token.replace(/^tok_/, '').replace(/_/g, ' ');
}
