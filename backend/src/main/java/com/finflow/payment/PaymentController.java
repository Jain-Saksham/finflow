package com.finflow.payment;

import com.finflow.common.web.PageResponse;
import com.finflow.common.web.Paging;
import com.finflow.idempotency.IdempotencyService;
import com.finflow.payment.PaymentDtos.CreatePaymentRequest;
import com.finflow.payment.PaymentDtos.PaymentEventResponse;
import com.finflow.payment.PaymentDtos.PaymentResponse;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9_-]{8,255}$";
    public static final String IDEMPOTENCY_KEY_MESSAGE = "must be 8-255 characters of letters, digits, '-' or '_'";

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    /** What the fingerprint covers: the normalized request, so an omitted default equals an explicit one. */
    public record CreateFingerprint(UUID orderId, String paymentMethodToken, CaptureMode captureMode) {
    }

    record ActionFingerprint(UUID paymentId) {
    }

    @PostMapping
    @Operation(summary = "Create and authorize a payment for an order",
            description = "Repeating a request with the same Idempotency-Key returns the original status and body "
                    + "(header Idempotent-Replayed: true) instead of charging again. A different body with the same "
                    + "key is 409 IDEMPOTENCY_KEY_REUSED; a retry while the first is running is 409 "
                    + "IDEMPOTENCY_REQUEST_IN_PROGRESS.")
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal MerchantPrincipal merchant,
            @Parameter(description = "Unique per payment attempt, e.g. a UUID", required = true)
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE)
            String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        var fingerprint = new CreateFingerprint(request.orderId(), request.paymentMethodToken(), request.captureModeOrDefault());
        var outcome = idempotency.execute(merchant.merchantId(), idempotencyKey, "payments.create", fingerprint,
                PaymentResponse.class, () -> {
                    PaymentService.CreateResult result = paymentService.create(merchant.merchantId(), idempotencyKey, request);
                    return new IdempotencyService.Outcome<>(PaymentResponse.from(result.payment()),
                            result.replayed() ? 200 : 201, result.replayed(), result.payment().getId());
                });
        return respond(outcome);
    }

    static <T> ResponseEntity<T> respond(IdempotencyService.Outcome<T> outcome) {
        var builder = ResponseEntity.status(outcome.status());
        if (outcome.status() == 201 && outcome.resourceId() != null) {
            builder.location(URI.create("/api/v1/payments/" + outcome.resourceId()));
        }
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    private ResponseEntity<PaymentResponse> action(MerchantPrincipal merchant, UUID paymentId, String idempotencyKey,
                                                   String operation, java.util.function.Supplier<Payment> call) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(PaymentResponse.from(call.get()));
        }
        return respond(idempotency.execute(merchant.merchantId(), idempotencyKey, operation,
                new ActionFingerprint(paymentId), PaymentResponse.class,
                () -> new IdempotencyService.Outcome<>(PaymentResponse.from(call.get()), 200, false, paymentId)));
    }

    @GetMapping
    @Operation(summary = "List payments (transactions), newest first")
    public PageResponse<PaymentResponse> list(@AuthenticationPrincipal MerchantPrincipal merchant,
                                              @RequestParam(required = false) PaymentStatus status,
                                              @RequestParam(required = false) UUID orderId,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = Paging.DEFAULT_SIZE) @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return PageResponse.of(paymentService.list(merchant.merchantId(), status, orderId, Paging.newestFirst(page, size)),
                PaymentResponse::from);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment, including the actions currently allowed on it")
    public PaymentResponse get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.get(merchant.merchantId(), paymentId));
    }

    @GetMapping("/{paymentId}/events")
    @Operation(summary = "State-transition history of a payment, oldest first")
    public List<PaymentEventResponse> events(@AuthenticationPrincipal MerchantPrincipal merchant,
                                             @PathVariable UUID paymentId) {
        return paymentService.history(merchant.merchantId(), paymentId).stream().map(PaymentEventResponse::from).toList();
    }

    @PostMapping("/{paymentId}/capture")
    @Operation(summary = "Capture an AUTHORIZED payment (full amount)",
            description = "Optional Idempotency-Key: with it, a retried capture replays the original 200 instead of "
                    + "failing with 409 INVALID_STATE_TRANSITION.")
    public ResponseEntity<PaymentResponse> capture(
            @AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey) {
        return action(merchant, paymentId, idempotencyKey, "payments.capture",
                () -> paymentService.capture(merchant.merchantId(), paymentId));
    }

    @PostMapping("/{paymentId}/void")
    @Operation(summary = "Void (release) an AUTHORIZED payment", description = "Optional Idempotency-Key, as for capture.")
    public ResponseEntity<PaymentResponse> voidPayment(
            @AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey) {
        return action(merchant, paymentId, idempotencyKey, "payments.void",
                () -> paymentService.voidAuthorization(merchant.merchantId(), paymentId));
    }

    @PostMapping("/{paymentId}/sync")
    @Operation(summary = "Resolve a TIMED_OUT payment by looking up its status at the provider")
    public PaymentResponse sync(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.sync(merchant.merchantId(), paymentId));
    }
}
