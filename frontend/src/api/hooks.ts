import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey } from './client';
import type {
  CaptureMode,
  DeliveryDetail,
  DeliveryStatus,
  ReconciliationReport,
  ReconciliationRun,
  SimulatedBehavior,
  WebhookDelivery,
  WebhookEndpoint,
  Currency,
  DashboardSummary,
  LedgerBalances,
  LedgerEntry,
  Merchant,
  Order,
  OrderStatus,
  Page,
  Payment,
  PaymentEvent,
  PaymentStatus,
  Refund,
  Scenarios,
} from './types';

function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') search.set(k, String(v));
  });
  const s = search.toString();
  return s ? `?${s}` : '';
}

export const useMerchant = () => useQuery({ queryKey: ['merchant'], queryFn: () => api<Merchant>('/api/v1/merchants/me') });

export const useDashboard = () =>
  useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api<DashboardSummary>('/api/v1/dashboard/summary'),
    refetchInterval: 15_000,
  });

export const useScenarios = () =>
  useQuery({ queryKey: ['scenarios'], queryFn: () => api<Scenarios>('/api/v1/simulator/scenarios'), staleTime: Infinity });

export function usePayments(filters: { status?: PaymentStatus | ''; orderId?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['payments', filters],
    queryFn: () => api<Page<Payment>>('/api/v1/payments' + query(filters)),
    placeholderData: keepPreviousData,
  });
}

export const usePayment = (id: string) =>
  useQuery({ queryKey: ['payment', id], queryFn: () => api<Payment>(`/api/v1/payments/${id}`) });

export const usePaymentEvents = (id: string) =>
  useQuery({ queryKey: ['payment', id, 'events'], queryFn: () => api<PaymentEvent[]>(`/api/v1/payments/${id}/events`) });

export const useRefunds = (paymentId: string) =>
  useQuery({ queryKey: ['payment', paymentId, 'refunds'], queryFn: () => api<Refund[]>(`/api/v1/payments/${paymentId}/refunds`) });

export function useOrders(filters: { status?: OrderStatus | ''; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['orders', filters],
    queryFn: () => api<Page<Order>>('/api/v1/orders' + query(filters)),
    placeholderData: keepPreviousData,
  });
}

export const useOrder = (id: string) => useQuery({ queryKey: ['order', id], queryFn: () => api<Order>(`/api/v1/orders/${id}`) });

export function useLedgerEntries(filters: { paymentId?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['ledger', 'entries', filters],
    queryFn: () => api<Page<LedgerEntry>>('/api/v1/ledger/entries' + query(filters)),
    placeholderData: keepPreviousData,
  });
}

export const useLedgerBalances = () =>
  useQuery({ queryKey: ['ledger', 'balances'], queryFn: () => api<LedgerBalances>('/api/v1/ledger/balances') });

/** Anything that changes a payment can change orders, ledger and dashboard too. */
function useInvalidateMoneyState() {
  const client = useQueryClient();
  return () =>
    Promise.all(
      [['payments'], ['payment'], ['orders'], ['order'], ['ledger'], ['dashboard'], ['webhooks']].map((queryKey) =>
        client.invalidateQueries({ queryKey }),
      ),
    );
}

export function useCreateOrder() {
  const invalidate = useInvalidateMoneyState();
  return useMutation({
    mutationFn: (body: { merchantReference: string; amountMinor: number; currency: Currency; description?: string }) =>
      api<Order>('/api/v1/orders', { method: 'POST', body }),
    onSuccess: invalidate,
  });
}

export function useCreatePayment() {
  const invalidate = useInvalidateMoneyState();
  return useMutation({
    mutationFn: (vars: { idempotencyKey: string; orderId: string; paymentMethodToken: string; captureMode: CaptureMode }) =>
      api<Payment>('/api/v1/payments', {
        method: 'POST',
        idempotencyKey: vars.idempotencyKey,
        body: { orderId: vars.orderId, paymentMethodToken: vars.paymentMethodToken, captureMode: vars.captureMode },
      }),
    onSuccess: invalidate,
  });
}

export function usePaymentAction(paymentId: string) {
  const invalidate = useInvalidateMoneyState();
  return useMutation({
    // Capture and void carry a fresh Idempotency-Key per click, so a network retry cannot repeat them.
    mutationFn: (action: 'capture' | 'void' | 'sync') =>
      api<Payment>(`/api/v1/payments/${paymentId}/${action}`, {
        method: 'POST',
        idempotencyKey: action === 'sync' ? undefined : newIdempotencyKey(),
      }),
    onSettled: invalidate, // a rejected action may still have recorded a failed attempt in history
  });
}

export function useCreateRefund(paymentId: string) {
  const invalidate = useInvalidateMoneyState();
  return useMutation({
    mutationFn: (vars: { idempotencyKey: string; amountMinor?: number; reason?: string }) =>
      api<Refund>(`/api/v1/payments/${paymentId}/refunds`, {
        method: 'POST',
        idempotencyKey: vars.idempotencyKey,
        body: { amountMinor: vars.amountMinor, reason: vars.reason },
      }),
    onSettled: invalidate,
  });
}

// ---------- Webhooks ----------

export const useWebhookEndpoints = () =>
  useQuery({ queryKey: ['webhooks', 'endpoints'], queryFn: () => api<WebhookEndpoint[]>('/api/v1/webhook-endpoints') });

export function useCreateWebhookEndpoint() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { simulatedBehavior: SimulatedBehavior; description?: string }) =>
      api<WebhookEndpoint>('/api/v1/webhook-endpoints', { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['webhooks'] }),
  });
}

export function useToggleWebhookEndpoint() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: string; enable: boolean }) =>
      api<WebhookEndpoint>(`/api/v1/webhook-endpoints/${vars.id}/${vars.enable ? 'enable' : 'disable'}`, { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['webhooks'] }),
  });
}

export function useWebhookDeliveries(filters: { status?: DeliveryStatus | ''; paymentId?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['webhooks', 'deliveries', filters],
    queryFn: () => api<Page<WebhookDelivery>>('/api/v1/webhook-deliveries' + query(filters)),
    placeholderData: keepPreviousData,
    refetchInterval: 5_000, // retries happen in the background
  });
}

export const useWebhookDelivery = (id: string) =>
  useQuery({
    queryKey: ['webhooks', 'delivery', id],
    queryFn: () => api<DeliveryDetail>(`/api/v1/webhook-deliveries/${id}`),
    refetchInterval: 3_000,
  });

export function useRedeliver(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api<DeliveryDetail>(`/api/v1/webhook-deliveries/${id}/redeliver`, { method: 'POST' }),
    onSettled: () => client.invalidateQueries({ queryKey: ['webhooks'] }),
  });
}

// ---------- Reconciliation ----------

export function useReconciliationRuns(page = 0) {
  return useQuery({
    queryKey: ['reconciliation', 'runs', page],
    queryFn: () => api<Page<ReconciliationRun>>('/api/v1/reconciliation/runs' + query({ page, size: 10 })),
    placeholderData: keepPreviousData,
  });
}

export const useReconciliationReport = (runId: string | undefined) =>
  useQuery({
    queryKey: ['reconciliation', 'run', runId],
    queryFn: () => api<ReconciliationReport>(`/api/v1/reconciliation/runs/${runId}`),
    enabled: !!runId,
  });

export function useRunReconciliation() {
  const invalidate = useInvalidateMoneyState();
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api<ReconciliationReport>('/api/v1/reconciliation/runs', { method: 'POST' }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['reconciliation'] });
      await invalidate(); // automatic repairs may have changed payments and the ledger
    },
  });
}
