package com.finflow.reconciliation;

import com.finflow.payment.PaymentStatus;
import com.finflow.provider.ProviderTransaction;
import com.finflow.provider.ProviderTransaction.Type;
import com.finflow.reconciliation.Discrepancy.Action;
import com.finflow.reconciliation.Discrepancy.Severity;
import com.finflow.refund.RefundStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure comparison of FinFlow's records with the provider's independent records (no I/O, so it is
 * exhaustively unit-testable). Records younger than {@code staleAfter} are skipped: their request
 * may still be in flight, and "fixing" them could race the request itself.
 */
public final class ReconciliationMatcher {

    public record InternalPayment(UUID id, PaymentStatus status, long amountMinor, long capturedMinor,
                                  long refundedMinor, String currency, String providerReference, Instant updatedAt) {
    }

    public record InternalRefund(UUID id, UUID paymentId, RefundStatus status, long amountMinor, Instant updatedAt) {
    }

    /** Net PROVIDER_RECEIVABLE movement (debits - credits) posted for one payment. */
    public record LedgerNet(UUID paymentId, long netMinor) {
    }

    public record Result(List<Discrepancy> discrepancies, int matchedPayments, int skippedInFlight) {
    }

    private static final Set<PaymentStatus> CAPTURED_FAMILY =
            EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    private ReconciliationMatcher() {
    }

    public static Result match(List<ProviderTransaction> provider, List<InternalPayment> payments,
                               List<InternalRefund> refunds, List<LedgerNet> ledger, Instant now, Duration staleAfter) {
        Map<UUID, List<ProviderTransaction>> providerByPayment = provider.stream()
                .collect(Collectors.groupingBy(ProviderTransaction::paymentId));
        Map<UUID, List<InternalRefund>> refundsByPayment = refunds.stream()
                .collect(Collectors.groupingBy(InternalRefund::paymentId));
        Map<UUID, Long> ledgerNet = new HashMap<>();
        ledger.forEach(l -> ledgerNet.put(l.paymentId(), l.netMinor()));
        Instant staleBefore = now.minus(staleAfter);

        List<Discrepancy> out = new ArrayList<>();
        Set<UUID> known = new HashSet<>();
        int matched = 0;
        int inFlight = 0;

        for (InternalPayment p : payments) {
            known.add(p.id());
            boolean stale = !p.updatedAt().isAfter(staleBefore);
            List<ProviderTransaction> txs = providerByPayment.getOrDefault(p.id(), List.of());
            List<ProviderTransaction> auths = of(txs, Type.AUTHORIZATION);
            List<ProviderTransaction> captures = of(txs, Type.CAPTURE);
            List<ProviderTransaction> voids = of(txs, Type.VOID);
            boolean authorizedAtProvider = auths.stream().anyMatch(t -> t.status() == ProviderTransaction.Status.SUCCEEDED);
            int before = out.size();

            switch (p.status()) {
                case CREATED, TIMED_OUT -> {
                    if (p.status() == PaymentStatus.CREATED && !stale) {
                        inFlight++;
                        continue;
                    }
                    out.add(new Discrepancy(FindingType.UNRESOLVED_PAYMENT, Severity.WARNING, Action.RESOLVE_FROM_PROVIDER,
                            p.id(), null, ref(auths), "a final status",
                            p.status() + (auths.isEmpty() ? ", provider has no authorization" : ", provider "
                                    + auths.getFirst().status()),
                            "Outcome never applied locally (timeout or crash between provider call and commit)"));
                }
                case DECLINED, FAILED -> {
                    if (authorizedAtProvider || !captures.isEmpty()) {
                        out.add(new Discrepancy(FindingType.STATUS_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(),
                                null, ref(auths), p.status().name(), captures.isEmpty() ? "AUTHORIZED" : "CAPTURED",
                                "Customer funds are held or taken at the provider for a payment FinFlow reports as "
                                        + p.status() + ". Void or refund it at the provider."));
                    }
                }
                case AUTHORIZED -> {
                    if (!authorizedAtProvider) {
                        out.add(new Discrepancy(FindingType.MISSING_AT_PROVIDER, Severity.CRITICAL, Action.NONE, p.id(),
                                null, p.providerReference(), "provider authorization", "none",
                                "FinFlow shows an authorization the provider does not have"));
                    } else if (captures.size() == 1 && captures.getFirst().amountMinor() == p.amountMinor() && stale) {
                        out.add(new Discrepancy(FindingType.STATUS_MISMATCH, Severity.WARNING, Action.APPLY_CAPTURE,
                                p.id(), null, p.providerReference(), "AUTHORIZED", "CAPTURED at provider",
                                "Capture succeeded at the provider but was not recorded (crash after provider call)"));
                    } else if (!captures.isEmpty() && stale) {
                        out.add(new Discrepancy(FindingType.STATUS_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(),
                                null, p.providerReference(), "AUTHORIZED",
                                captures.size() + " capture(s) at provider", "Unexpected provider captures"));
                    } else if (!voids.isEmpty() && stale) {
                        out.add(new Discrepancy(FindingType.STATUS_MISMATCH, Severity.WARNING, Action.NONE, p.id(),
                                null, p.providerReference(), "AUTHORIZED", "VOIDED at provider",
                                "Provider released the authorization; it can no longer be captured"));
                    }
                }
                case VOIDED -> {
                    if (!captures.isEmpty()) {
                        out.add(new Discrepancy(FindingType.STATUS_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(),
                                null, p.providerReference(), "VOIDED", "CAPTURED at provider",
                                "Provider captured a payment FinFlow voided. Refund it at the provider."));
                    }
                }
                case CAPTURED, PARTIALLY_REFUNDED, REFUNDED -> {
                    if (captures.isEmpty()) {
                        out.add(new Discrepancy(FindingType.MISSING_AT_PROVIDER, Severity.CRITICAL, Action.NONE, p.id(),
                                null, p.providerReference(), "capture of " + p.capturedMinor(), "none",
                                "FinFlow recorded a capture the provider has no record of; money may never arrive"));
                    } else if (captures.size() > 1) {
                        out.add(new Discrepancy(FindingType.DUPLICATE_PROVIDER_CAPTURE, Severity.CRITICAL, Action.NONE,
                                p.id(), null, p.providerReference(), "1 capture", captures.size() + " captures",
                                "Customer charged more than once at the provider. Refund the duplicate there."));
                    } else if (captures.getFirst().amountMinor() != p.capturedMinor()) {
                        out.add(new Discrepancy(FindingType.AMOUNT_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(),
                                null, p.providerReference(), String.valueOf(p.capturedMinor()),
                                String.valueOf(captures.getFirst().amountMinor()), "Captured amounts differ"));
                    }
                }
            }

            // Refunds of this payment.
            Map<UUID, List<ProviderTransaction>> providerRefunds = of(txs, Type.REFUND).stream()
                    .collect(Collectors.groupingBy(ProviderTransaction::refundId));
            Set<UUID> internalRefundIds = new HashSet<>();
            for (InternalRefund r : refundsByPayment.getOrDefault(p.id(), List.of())) {
                internalRefundIds.add(r.id());
                List<ProviderTransaction> pr = providerRefunds.getOrDefault(r.id(), List.of());
                boolean refundStale = !r.updatedAt().isAfter(staleBefore);
                if (pr.size() > 1) {
                    out.add(new Discrepancy(FindingType.DUPLICATE_PROVIDER_REFUND, Severity.CRITICAL, Action.NONE, p.id(),
                            r.id(), pr.getFirst().providerReference(), "1 refund", pr.size() + " refunds",
                            "Refund executed more than once at the provider"));
                } else if (r.status() == RefundStatus.SUCCEEDED && pr.isEmpty()) {
                    out.add(new Discrepancy(FindingType.MISSING_AT_PROVIDER, Severity.CRITICAL, Action.NONE, p.id(),
                            r.id(), null, "provider refund of " + r.amountMinor(), "none",
                            "FinFlow recorded a refund the provider has no record of"));
                } else if (r.status() == RefundStatus.SUCCEEDED && pr.getFirst().amountMinor() != r.amountMinor()) {
                    out.add(new Discrepancy(FindingType.AMOUNT_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(), r.id(),
                            pr.getFirst().providerReference(), String.valueOf(r.amountMinor()),
                            String.valueOf(pr.getFirst().amountMinor()), "Refund amounts differ"));
                } else if (r.status() == RefundStatus.PENDING && refundStale) {
                    boolean atProvider = !pr.isEmpty();
                    out.add(new Discrepancy(FindingType.PENDING_REFUND, Severity.WARNING,
                            atProvider ? Action.CONFIRM_REFUND : Action.FAIL_REFUND, p.id(), r.id(),
                            atProvider ? pr.getFirst().providerReference() : null, "SUCCEEDED or FAILED", "PENDING",
                            atProvider ? "Provider refunded; local confirmation was never committed"
                                    : "Provider never received the refund; the reservation is released"));
                } else if (r.status() == RefundStatus.FAILED && !pr.isEmpty()) {
                    out.add(new Discrepancy(FindingType.STATUS_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(), r.id(),
                            pr.getFirst().providerReference(), "FAILED", "REFUNDED at provider",
                            "Provider refunded although FinFlow marked the refund as failed"));
                }
            }
            providerRefunds.forEach((refundId, list) -> {
                if (!internalRefundIds.contains(refundId)) {
                    out.add(new Discrepancy(FindingType.ORPHAN_PROVIDER_TRANSACTION, Severity.CRITICAL, Action.NONE,
                            p.id(), refundId, list.getFirst().providerReference(), "no refund", "provider refund",
                            "Provider refund with no matching FinFlow refund"));
                }
            });

            // Ledger: PROVIDER_RECEIVABLE must have moved by exactly captured - refunded.
            long expectedNet = p.capturedMinor() - p.refundedMinor();
            long actualNet = ledgerNet.getOrDefault(p.id(), 0L);
            if (expectedNet != actualNet) {
                out.add(new Discrepancy(FindingType.LEDGER_MISMATCH, Severity.CRITICAL, Action.NONE, p.id(), null, null,
                        String.valueOf(expectedNet), String.valueOf(actualNet),
                        "Ledger receivable for the payment does not equal captured minus refunded. Correct it "
                                + "with a reversal or adjustment journal."));
            }

            if (out.size() == before) {
                matched++;
            }
        }

        // Provider transactions for payments FinFlow has never heard of.
        providerByPayment.forEach((paymentId, txs) -> {
            if (!known.contains(paymentId)) {
                ProviderTransaction first = txs.getFirst();
                out.add(new Discrepancy(FindingType.ORPHAN_PROVIDER_TRANSACTION, Severity.CRITICAL, Action.NONE, paymentId,
                        null, first.providerReference(), "no payment",
                        txs.stream().map(t -> t.type() + " " + t.amountMinor()).collect(Collectors.joining(", ")),
                        "Provider transaction(s) with no matching FinFlow payment"));
            }
        });
        return new Result(out, matched, inFlight);
    }

    private static List<ProviderTransaction> of(List<ProviderTransaction> txs, Type type) {
        return txs.stream().filter(t -> t.type() == type).toList();
    }

    private static String ref(List<ProviderTransaction> auths) {
        return auths.isEmpty() ? null : auths.getFirst().providerReference();
    }
}
