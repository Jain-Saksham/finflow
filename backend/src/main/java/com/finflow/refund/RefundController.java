package com.finflow.refund;

import com.finflow.idempotency.IdempotencyService;
import com.finflow.payment.PaymentController;
import com.finflow.refund.RefundDtos.CreateRefundRequest;
import com.finflow.refund.RefundDtos.RefundResponse;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/refunds")
@Tag(name = "Refunds")
public class RefundController {

    private final RefundService refundService;
    private final IdempotencyService idempotency;

    public RefundController(RefundService refundService, IdempotencyService idempotency) {
        this.refundService = refundService;
        this.idempotency = idempotency;
    }

    record RefundFingerprint(UUID paymentId, Long amountMinor, String reason) {
    }

    @PostMapping
    @Operation(summary = "Refund a captured payment, fully or partially",
            description = "The response status is SUCCEEDED or FAILED (e.g. provider unavailable). "
                    + "Eligibility violations are rejected with 409/422 and create nothing.")
    public ResponseEntity<RefundResponse> create(
            @AuthenticationPrincipal MerchantPrincipal merchant,
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = PaymentController.IDEMPOTENCY_KEY_PATTERN, message = PaymentController.IDEMPOTENCY_KEY_MESSAGE)
            String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        var outcome = idempotency.execute(merchant.merchantId(), idempotencyKey, "refunds.create",
                new RefundFingerprint(paymentId, request.amountMinor(), request.reason()), RefundResponse.class, () -> {
                    RefundService.CreateResult result =
                            refundService.create(merchant.merchantId(), paymentId, idempotencyKey, request);
                    return new IdempotencyService.Outcome<>(RefundResponse.from(result.refund()),
                            result.replayed() ? 200 : 201, result.replayed(), result.refund().getId());
                });
        var builder = ResponseEntity.status(outcome.status());
        if (outcome.status() == 201) {
            builder.location(URI.create("/api/v1/payments/" + paymentId + "/refunds/" + outcome.resourceId()));
        }
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    @GetMapping
    @Operation(summary = "List refunds of a payment, oldest first")
    public List<RefundResponse> list(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId) {
        return refundService.listForPayment(merchant.merchantId(), paymentId).stream().map(RefundResponse::from).toList();
    }
}
