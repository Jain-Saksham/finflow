package com.finflow.refund;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.common.persistence.AuditedEntity;
import com.finflow.ledger.Journal;
import com.finflow.ledger.LedgerService;
import com.finflow.payment.Payment;
import com.finflow.payment.PaymentEventType;
import com.finflow.payment.PaymentHistory;
import com.finflow.payment.PaymentRepository;
import com.finflow.provider.PaymentProvider;
import com.finflow.provider.ProviderException;
import com.finflow.provider.ProviderTimeoutException;
import com.finflow.refund.RefundDtos.CreateRefundRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds follow the same reserve → call provider → confirm pattern as payments. The PENDING
 * refund row is the reservation: it is created under the payment's row lock and counted against
 * the refundable amount, so concurrent refunds can never exceed what was captured.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentHistory history;
    private final LedgerService ledger;
    private final PaymentProvider provider;
    private final RefundPolicy policy;
    private final TransactionTemplate tx;

    public RefundService(RefundRepository refunds, PaymentRepository payments, PaymentHistory history,
                         LedgerService ledger, PaymentProvider provider, RefundPolicy policy,
                         PlatformTransactionManager transactionManager) {
        this.refunds = refunds;
        this.payments = payments;
        this.history = history;
        this.ledger = ledger;
        this.provider = provider;
        this.policy = policy;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record CreateResult(Refund refund, boolean replayed) {
    }

    public CreateResult create(UUID merchantId, UUID paymentId, String idempotencyKey, CreateRefundRequest request) {
        Optional<Refund> existing = refunds.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), paymentId, request);
        }

        Refund refund;
        String providerReference;
        try {
            var reserved = tx.execute(status -> {
                Payment payment = lock(merchantId, paymentId);
                long pending = refunds.sumPendingAmount(paymentId);
                long amount = request.amountMinor() != null
                        ? request.amountMinor()
                        : payment.refundableAmountMinor() - pending;
                policy.assertEligible(payment, amount, pending, AuditedEntity.now());
                Refund r = refunds.saveAndFlush(new Refund(payment, amount, blankToNull(request.reason()), idempotencyKey));
                return new Reservation(r, payment.getProviderReference());
            });
            refund = Objects.requireNonNull(reserved).refund();
            providerReference = reserved.providerReference();
        } catch (DataIntegrityViolationException e) {
            return refunds.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .map(r -> replay(r, paymentId, request))
                    .orElseThrow(() -> e);
        }

        try {
            String refundReference = provider.refund(providerReference, refund.getId(), refund.getAmountMinor());
            return new CreateResult(confirm(merchantId, refund.getId(), refundReference), false);
        } catch (ProviderException e) {
            log.warn("Refund {} failed at provider: {}", refund.getId(), e.getMessage());
            return new CreateResult(markFailed(merchantId, refund.getId(), e), false);
        }
    }

    @Transactional(readOnly = true)
    public List<Refund> listForPayment(UUID merchantId, UUID paymentId) {
        payments.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        return refunds.findByMerchantIdAndPaymentIdOrderByCreatedAtAsc(merchantId, paymentId);
    }

    private record Reservation(Refund refund, String providerReference) {
    }

    private Refund confirm(UUID merchantId, UUID refundId, String providerReference) {
        return tx.execute(status -> {
            Refund r = refunds.findById(refundId).orElseThrow();
            Payment payment = lock(merchantId, r.getPaymentId());
            r.succeed(providerReference);
            history.record(payment.applyRefund(r.getId(), r.getAmountMinor()));
            ledger.post(Journal.refund(merchantId, payment.getId(), r.getId(), r.getAmountMinor(), r.getCurrency()));
            return r;
        });
    }

    private Refund markFailed(UUID merchantId, UUID refundId, ProviderException e) {
        // Simplification: a timed-out refund is treated as failed. A real system would keep it
        // PENDING and confirm via the provider (the simulator never times out on refunds).
        String code = e instanceof ProviderTimeoutException ? "provider_timeout" : "provider_unavailable";
        return tx.execute(status -> {
            Refund r = refunds.findById(refundId).orElseThrow();
            Payment payment = lock(merchantId, r.getPaymentId());
            r.fail(code);
            history.record(payment.attemptFailed(PaymentEventType.REFUND_FAILED, "Refund " + r.getId() + ": " + code));
            return r;
        });
    }

    private Payment lock(UUID merchantId, UUID paymentId) {
        return payments.findForUpdate(paymentId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }

    private static CreateResult replay(Refund refund, UUID paymentId, CreateRefundRequest request) {
        boolean sameRequest = refund.getPaymentId().equals(paymentId)
                && (request.amountMinor() == null || request.amountMinor() == refund.getAmountMinor());
        if (!sameRequest) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request");
        }
        return new CreateResult(refund, true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
