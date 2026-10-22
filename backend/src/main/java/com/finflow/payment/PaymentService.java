package com.finflow.payment;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.common.persistence.AuditedEntity;
import com.finflow.ledger.Journal;
import com.finflow.ledger.LedgerService;
import com.finflow.order.MerchantOrder;
import com.finflow.order.OrderRepository;
import com.finflow.order.OrderStatus;
import com.finflow.payment.PaymentDtos.CreatePaymentRequest;
import com.finflow.provider.PaymentProvider;
import com.finflow.provider.PaymentProvider.AuthorizationRequest;
import com.finflow.provider.PaymentProvider.AuthorizationResult;
import com.finflow.provider.ProviderException;
import com.finflow.provider.ProviderTimeoutException;
import com.finflow.provider.ProviderUnavailableException;
import com.finflow.provider.SimulationScenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates payments against the provider.
 *
 * <p>Key design rule: <b>never hold a database transaction open across a provider call</b>.
 * Each operation is (1) a short transaction that validates and records intent, (2) the provider
 * call with no locks held, then (3) a short transaction that re-locks the payment and applies the
 * outcome. The state machine re-validates in step 3, so a concurrent change made during the
 * provider call is detected rather than overwritten. If the process dies between (2) and (3), the
 * provider's record and the payment disagree until reconciliation repairs it via
 * {@link #resolveFromProvider} or {@link #capture}, both of which are safe to repeat.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final OrderRepository orders;
    private final PaymentHistory history;
    private final LedgerService ledger;
    private final PaymentProvider provider;
    private final TransactionTemplate tx;
    private final MeterRegistry registry;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events, OrderRepository orders,
                          PaymentHistory history, LedgerService ledger, PaymentProvider provider,
                          PlatformTransactionManager transactionManager, MeterRegistry registry) {
        this.payments = payments;
        this.events = events;
        this.orders = orders;
        this.history = history;
        this.ledger = ledger;
        this.provider = provider;
        this.tx = new TransactionTemplate(transactionManager);
        this.registry = registry;
    }

    /** @param replayed true when the idempotency key matched an earlier payment and nothing new happened */
    public record CreateResult(Payment payment, boolean replayed) {
    }

    public CreateResult create(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            CreateResult result = doCreate(merchantId, idempotencyKey, request);
            outcome = result.replayed() ? "replayed" : result.payment().getStatus().name();
            return result;
        } finally {
            sample.stop(Timer.builder("finflow.payment.processing")
                    .description("End-to-end time to create and authorize (and auto-capture) a payment")
                    .tag("outcome", outcome).register(registry));
        }
    }

    private CreateResult doCreate(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        SimulationScenario scenario = SimulationScenario.fromToken(request.paymentMethodToken())
                .orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_PAYMENT_METHOD, "Unknown payment method token '"
                        + request.paymentMethodToken() + "'. See GET /api/v1/simulator/scenarios."));
        // Second line of defence behind the idempotency_records table: the key is unique per merchant
        // on the payment row itself, so even an expired or bypassed idempotency record cannot create
        // a duplicate payment.
        Optional<Payment> existing = payments.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        Payment payment;
        try {
            payment = tx.execute(status -> {
                MerchantOrder order = orders.findByIdAndMerchantId(request.orderId(), merchantId)
                        .orElseThrow(() -> ApiException.notFound("Order", request.orderId()));
                if (order.getStatus() != OrderStatus.OPEN) {
                    throw new ApiException(ErrorCode.ORDER_NOT_PAYABLE, "Order is " + order.getStatus());
                }
                if (payments.existsByOrderIdAndStatusIn(order.getId(), PaymentStatus.ACTIVE)) {
                    throw activePaymentConflict();
                }
                Payment p = payments.saveAndFlush(new Payment(order, request.paymentMethodToken(),
                        request.captureModeOrDefault(), provider.name(), idempotencyKey));
                history.record(p, p.creationEvent());
                return p;
            });
        } catch (DataIntegrityViolationException e) {
            // A concurrent request won the race: either it used the same idempotency key (replay it)
            // or it created another active payment for this order (the partial unique index fired).
            return payments.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .map(p -> replay(p, request))
                    .orElseThrow(PaymentService::activePaymentConflict);
        }
        return new CreateResult(authorize(Objects.requireNonNull(payment), scenario), false);
    }

    public Payment capture(UUID merchantId, UUID paymentId) {
        Payment payment = get(merchantId, paymentId);
        payment.assertCan(PaymentStatus.CAPTURED, "capture");
        try {
            provider.capture(operation(payment));
        } catch (ProviderException e) {
            throw recordAttemptFailure(merchantId, paymentId, PaymentEventType.CAPTURE_FAILED, e);
        }
        return tx.execute(status -> {
            Payment p = lock(merchantId, paymentId);
            history.record(p, p.capture());
            orders.findByIdAndMerchantId(p.getOrderId(), merchantId).orElseThrow().markPaid();
            ledger.post(Journal.capture(merchantId, p.getId(), p.getCapturedAmountMinor(), p.getCurrency()));
            return p;
        });
    }

    public Payment voidAuthorization(UUID merchantId, UUID paymentId) {
        Payment payment = get(merchantId, paymentId);
        payment.assertCan(PaymentStatus.VOIDED, "void");
        try {
            provider.voidAuthorization(operation(payment));
        } catch (ProviderException e) {
            throw recordAttemptFailure(merchantId, paymentId, PaymentEventType.VOID_FAILED, e);
        }
        return tx.execute(status -> {
            Payment p = lock(merchantId, paymentId);
            history.record(p, p.voidAuthorization());
            return p;
        });
    }

    /**
     * Resolves a TIMED_OUT payment by asking the provider what actually happened. Retrying the
     * authorization blindly could charge the customer twice; a status lookup cannot.
     */
    public Payment sync(UUID merchantId, UUID paymentId) {
        Payment payment = get(merchantId, paymentId);
        if (payment.getStatus() != PaymentStatus.TIMED_OUT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only TIMED_OUT payments can be synced; this payment is " + payment.getStatus());
        }
        return resolveFromProvider(merchantId, paymentId, Duration.ZERO);
    }

    /**
     * Used by sync, the timeout-recovery consumer and reconciliation. Applies the provider's view to
     * a payment whose outcome is unknown: TIMED_OUT, or CREATED for longer than {@code staleAfter}
     * (the process died between the provider call and saving the result). Younger CREATED payments
     * are left alone because their request may still be in flight. Safe to call repeatedly.
     */
    public Payment resolveFromProvider(UUID merchantId, UUID paymentId, Duration staleAfter) {
        Payment payment = get(merchantId, paymentId);
        if (!isUnresolved(payment, staleAfter)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Payment " + paymentId + " is " + payment.getStatus() + " and does not need resolving");
        }
        Optional<AuthorizationResult> result;
        try {
            result = provider.lookupAuthorization(paymentId);
        } catch (ProviderException e) {
            throw toApiException(e);
        }
        Payment updated = tx.execute(status -> {
            Payment p = lock(merchantId, paymentId);
            if (!isUnresolved(p, staleAfter)) {
                return p; // resolved concurrently while we were asking the provider
            }
            history.record(p, result.map(r -> applyAuthorization(p, r))
                    .orElseGet(() -> p.fail("not_found_at_provider",
                            "Provider has no record of this authorization; nothing was charged")));
            return p;
        });
        return captureIfAutomatic(Objects.requireNonNull(updated));
    }

    private static boolean isUnresolved(Payment p, Duration staleAfter) {
        return p.getStatus() == PaymentStatus.TIMED_OUT
                || (p.getStatus() == PaymentStatus.CREATED
                && !p.getCreatedAt().isAfter(AuditedEntity.now().minus(staleAfter)));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID merchantId, UUID paymentId) {
        return payments.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentEvent> history(UUID merchantId, UUID paymentId) {
        get(merchantId, paymentId); // enforces merchant scoping before exposing events
        return events.findByPaymentIdOrderByIdAsc(paymentId);
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(UUID merchantId, PaymentStatus status, UUID orderId, Pageable pageable) {
        List<Specification<Payment>> filters = new ArrayList<>();
        filters.add((root, query, cb) -> cb.equal(root.get("merchantId"), merchantId));
        if (status != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (orderId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("orderId"), orderId));
        }
        return payments.findAll(Specification.allOf(filters), pageable);
    }

    private Payment authorize(Payment payment, SimulationScenario scenario) {
        var request = new AuthorizationRequest(payment.getMerchantId(), payment.getId(), payment.getAmountMinor(),
                payment.getCurrency(), payment.getPaymentMethodToken());
        AuthorizationResult result = null;
        ProviderException error = null;
        try {
            result = provider.authorize(request);
        } catch (ProviderException e) {
            error = e;
        }
        if (scenario.crashesBeforeLocalCommit()) {
            // Demo fault injection: behave exactly as if the process died right here.
            throw new ApiException(ErrorCode.SIMULATED_CRASH, "Simulated crash after the provider approved payment "
                    + payment.getId() + ". It stays CREATED until reconciliation recovers it.");
        }
        final AuthorizationResult outcome = result;
        final ProviderException failure = error;
        Payment updated = tx.execute(status -> {
            Payment p = lock(payment.getMerchantId(), payment.getId());
            PaymentEvent event = switch (failure) {
                case null -> applyAuthorization(p, outcome);
                case ProviderTimeoutException t -> p.timeOut(t.getMessage());
                case ProviderUnavailableException u -> p.fail("provider_unavailable", u.getMessage());
            };
            history.record(p, event);
            return p;
        });
        return captureIfAutomatic(Objects.requireNonNull(updated));
    }

    private static PaymentEvent applyAuthorization(Payment p, AuthorizationResult result) {
        return switch (result) {
            case AuthorizationResult.Approved a -> p.authorize(a.providerReference());
            case AuthorizationResult.Declined d -> p.decline(d.code(), d.message());
        };
    }

    private Payment captureIfAutomatic(Payment payment) {
        if (payment.getStatus() == PaymentStatus.AUTHORIZED && payment.getCaptureMode() == CaptureMode.AUTOMATIC) {
            try {
                return capture(payment.getMerchantId(), payment.getId());
            } catch (ApiException e) {
                // Authorization succeeded; a failed auto-capture leaves it AUTHORIZED (recorded in
                // history) so the merchant can capture manually rather than losing the payment.
                return get(payment.getMerchantId(), payment.getId());
            }
        }
        return payment;
    }

    private ApiException recordAttemptFailure(UUID merchantId, UUID paymentId, PaymentEventType type,
                                              ProviderException e) {
        tx.executeWithoutResult(status -> {
            Payment p = lock(merchantId, paymentId);
            history.record(p, p.attemptFailed(type, e.getMessage()));
        });
        return toApiException(e);
    }

    static ApiException toApiException(ProviderException e) {
        return switch (e) {
            case ProviderTimeoutException t -> new ApiException(ErrorCode.PROVIDER_TIMEOUT,
                    "The provider did not respond in time. The operation is safe to retry.");
            case ProviderUnavailableException u -> new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "The payment provider is unavailable. Nothing was changed; retry later.");
        };
    }

    public static PaymentProvider.Operation operation(Payment p) {
        return new PaymentProvider.Operation(p.getMerchantId(), p.getId(), p.getProviderReference(), p.getAmountMinor(),
                p.getCurrency());
    }

    private Payment lock(UUID merchantId, UUID paymentId) {
        return payments.findForUpdate(paymentId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }

    private static CreateResult replay(Payment payment, CreatePaymentRequest request) {
        boolean sameRequest = payment.getOrderId().equals(request.orderId())
                && payment.getPaymentMethodToken().equals(request.paymentMethodToken())
                && payment.getCaptureMode() == request.captureModeOrDefault();
        if (!sameRequest) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body");
        }
        return new CreateResult(payment, true);
    }

    private static ApiException activePaymentConflict() {
        return new ApiException(ErrorCode.ORDER_HAS_ACTIVE_PAYMENT,
                "The order already has a payment that is in progress or succeeded");
    }
}
