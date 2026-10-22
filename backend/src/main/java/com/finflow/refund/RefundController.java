package com.finflow.refund;

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

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
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
        RefundService.CreateResult result = refundService.create(merchant.merchantId(), paymentId, idempotencyKey, request);
        RefundResponse body = RefundResponse.from(result.refund());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/payments/" + paymentId + "/refunds/" + body.id())).body(body);
    }

    @GetMapping
    @Operation(summary = "List refunds of a payment, oldest first")
    public List<RefundResponse> list(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID paymentId) {
        return refundService.listForPayment(merchant.merchantId(), paymentId).stream().map(RefundResponse::from).toList();
    }
}
