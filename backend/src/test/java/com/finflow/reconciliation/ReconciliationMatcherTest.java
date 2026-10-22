package com.finflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.payment.PaymentStatus;
import com.finflow.provider.ProviderTransaction;
import com.finflow.provider.ProviderTransaction.Status;
import com.finflow.provider.ProviderTransaction.Type;
import com.finflow.reconciliation.Discrepancy.Action;
import com.finflow.reconciliation.ReconciliationMatcher.InternalPayment;
import com.finflow.reconciliation.ReconciliationMatcher.InternalRefund;
import com.finflow.reconciliation.ReconciliationMatcher.LedgerNet;
import com.finflow.refund.RefundStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationMatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofMinutes(10));
    private static final Duration STALE = Duration.ofMinutes(1);
    private final UUID merchant = UUID.randomUUID();
    private final List<ProviderTransaction> provider = new ArrayList<>();
    private final List<InternalPayment> payments = new ArrayList<>();
    private final List<InternalRefund> refunds = new ArrayList<>();
    private final List<LedgerNet> ledger = new ArrayList<>();

    private UUID payment(PaymentStatus status, long captured, long refunded, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        payments.add(new InternalPayment(id, status, 100_00, captured, refunded, "USD", "pi_" + id, updatedAt));
        if (captured - refunded != 0) {
            ledger.add(new LedgerNet(id, captured - refunded));
        }
        return id;
    }

    private void tx(Type type, UUID paymentId, long amount) {
        tx(type, paymentId, null, amount, Status.SUCCEEDED);
    }

    private void tx(Type type, UUID paymentId, UUID refundId, long amount, Status status) {
        provider.add(new ProviderTransaction(provider.size() + 1, type, status, merchant, paymentId, refundId,
                "pi_" + paymentId, amount, "USD", null, null, OLD));
    }

    private ReconciliationMatcher.Result run() {
        return ReconciliationMatcher.match(provider, payments, refunds, ledger, NOW, STALE);
    }

    @Test
    void consistentRecordsMatch() {
        UUID captured = payment(PaymentStatus.CAPTURED, 100_00, 0, OLD);
        tx(Type.AUTHORIZATION, captured, 100_00);
        tx(Type.CAPTURE, captured, 100_00);
        UUID declined = payment(PaymentStatus.DECLINED, 0, 0, OLD);
        tx(Type.AUTHORIZATION, declined, null, 100_00, Status.DECLINED);
        payment(PaymentStatus.FAILED, 0, 0, OLD); // provider unavailable: no provider record at all

        var result = run();
        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.matchedPayments()).isEqualTo(3);
    }

    @Test
    void timedOutAndStaleCreatedPaymentsAreRecoverable() {
        UUID timedOut = payment(PaymentStatus.TIMED_OUT, 0, 0, NOW);
        tx(Type.AUTHORIZATION, timedOut, 100_00);
        UUID crashed = payment(PaymentStatus.CREATED, 0, 0, OLD);
        tx(Type.AUTHORIZATION, crashed, 100_00);

        var result = run();
        assertThat(result.discrepancies()).extracting(Discrepancy::type, Discrepancy::action).containsOnly(
                org.assertj.core.groups.Tuple.tuple(FindingType.UNRESOLVED_PAYMENT, Action.RESOLVE_FROM_PROVIDER));
        assertThat(result.discrepancies()).hasSize(2);
    }

    @Test
    void youngCreatedPaymentIsInFlightAndLeftAlone() {
        payment(PaymentStatus.CREATED, 0, 0, NOW.minusSeconds(5));
        var result = run();
        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.skippedInFlight()).isEqualTo(1);
    }

    @Test
    void providerCaptureOfAnAuthorizedPaymentIsAppliedLocally() {
        UUID id = payment(PaymentStatus.AUTHORIZED, 0, 0, OLD);
        tx(Type.AUTHORIZATION, id, 100_00);
        tx(Type.CAPTURE, id, 100_00);
        assertThat(run().discrepancies()).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Action.APPLY_CAPTURE));
    }

    @Test
    void duplicateMissingAndOrphanCapturesAreUnrecoverable() {
        UUID duplicated = payment(PaymentStatus.CAPTURED, 100_00, 0, OLD);
        tx(Type.AUTHORIZATION, duplicated, 100_00);
        tx(Type.CAPTURE, duplicated, 100_00);
        tx(Type.CAPTURE, duplicated, 100_00);
        UUID missing = payment(PaymentStatus.CAPTURED, 100_00, 0, OLD);
        tx(Type.AUTHORIZATION, missing, 100_00);
        tx(Type.CAPTURE, UUID.randomUUID(), 42_00); // orphan

        var types = run().discrepancies().stream().map(Discrepancy::type).toList();
        assertThat(types).containsExactlyInAnyOrder(FindingType.DUPLICATE_PROVIDER_CAPTURE,
                FindingType.MISSING_AT_PROVIDER, FindingType.ORPHAN_PROVIDER_TRANSACTION);
        assertThat(run().discrepancies()).noneMatch(Discrepancy::recoverable);
    }

    @Test
    void declinedLocallyButAuthorizedAtProviderIsCritical() {
        UUID id = payment(PaymentStatus.FAILED, 0, 0, OLD);
        tx(Type.AUTHORIZATION, id, 100_00);
        assertThat(run().discrepancies()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(FindingType.STATUS_MISMATCH);
            assertThat(d.severity()).isEqualTo(Discrepancy.Severity.CRITICAL);
        });
    }

    @Test
    void pendingRefundsAreConfirmedOrFailedDependingOnTheProvider() {
        UUID id = payment(PaymentStatus.CAPTURED, 100_00, 0, OLD);
        tx(Type.AUTHORIZATION, id, 100_00);
        tx(Type.CAPTURE, id, 100_00);
        UUID refundedAtProvider = UUID.randomUUID();
        UUID neverSent = UUID.randomUUID();
        refunds.add(new InternalRefund(refundedAtProvider, id, RefundStatus.PENDING, 10_00, OLD));
        refunds.add(new InternalRefund(neverSent, id, RefundStatus.PENDING, 5_00, OLD));
        tx(Type.REFUND, id, refundedAtProvider, 10_00, Status.SUCCEEDED);

        assertThat(run().discrepancies()).extracting(Discrepancy::refundId, Discrepancy::action).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(refundedAtProvider, Action.CONFIRM_REFUND),
                org.assertj.core.groups.Tuple.tuple(neverSent, Action.FAIL_REFUND));
    }

    @Test
    void ledgerThatDisagreesWithCapturedMinusRefundedIsFlagged() {
        UUID id = payment(PaymentStatus.CAPTURED, 100_00, 0, OLD);
        tx(Type.AUTHORIZATION, id, 100_00);
        tx(Type.CAPTURE, id, 100_00);
        ledger.clear(); // e.g. the capture journal was reversed
        assertThat(run().discrepancies()).singleElement()
                .satisfies(d -> assertThat(d.type()).isEqualTo(FindingType.LEDGER_MISMATCH));
    }
}
