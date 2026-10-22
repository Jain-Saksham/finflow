import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type {
  CaptureMode,
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
      [['payments'], ['payment'], ['orders'], ['order'], ['ledger'], ['dashboard']].map((queryKey) =>
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
    mutationFn: (action: 'capture' | 'void' | 'sync') =>
      api<Payment>(`/api/v1/payments/${paymentId}/${action}`, { method: 'POST' }),
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
