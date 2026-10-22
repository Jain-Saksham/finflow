// Thin fetch wrapper: attaches the API key, and turns RFC 9457 problem responses into ApiError.

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
const KEY_STORAGE = 'finflow.apiKey';
export const UNAUTHORIZED_EVENT = 'finflow:unauthorized';

export interface FieldViolation {
  field: string;
  message: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly errors: FieldViolation[] = [],
    readonly requestId?: string,
  ) {
    super(detail);
  }

  fieldError(field: string): string | undefined {
    return this.errors.find((e) => e.field === field)?.message;
  }
}

// sessionStorage can throw (privacy modes, blocked storage); the app then simply has no session.
export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem(KEY_STORAGE);
  } catch {
    return null;
  }
}

export function setApiKey(key: string | null): void {
  try {
    if (key) sessionStorage.setItem(KEY_STORAGE, key);
    else sessionStorage.removeItem(KEY_STORAGE);
  } catch {
    /* ignore */
  }
}

export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  idempotencyKey?: string;
}

/** Fetches an authenticated file (e.g. a CSV report) and hands it to the browser as a download. */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const apiKey = getApiKey();
  const response = await fetch(BASE_URL + path, { headers: apiKey ? { 'X-API-Key': apiKey } : {} });
  if (!response.ok) throw new ApiError(response.status, `HTTP_${response.status}`, 'Download failed');
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const apiKey = getApiKey();
  if (apiKey) headers['X-API-Key'] = apiKey;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  let response: Response;
  try {
    response = await fetch(BASE_URL + path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the FinFlow API. Is the backend running?');
  }

  if (response.status === 401 && apiKey) {
    setApiKey(null);
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
  }

  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    const problem = (data ?? {}) as Record<string, unknown>;
    throw new ApiError(
      response.status,
      typeof problem.code === 'string' ? problem.code : `HTTP_${response.status}`,
      typeof problem.detail === 'string' ? problem.detail : response.statusText || 'Request failed',
      Array.isArray(problem.errors) ? (problem.errors as FieldViolation[]) : [],
      typeof problem.requestId === 'string' ? problem.requestId : undefined,
    );
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
