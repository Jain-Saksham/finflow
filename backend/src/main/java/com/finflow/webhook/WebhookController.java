package com.finflow.webhook;

import com.finflow.common.error.ApiException;
import com.finflow.common.web.PageResponse;
import com.finflow.common.web.Paging;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Webhooks")
public class WebhookController {

    private final WebhookEndpointService endpointService;
    private final WebhookDeliveryStore deliveries;
    private final WebhookProperties properties;

    public WebhookController(WebhookEndpointService endpointService, WebhookDeliveryStore deliveries,
                             WebhookProperties properties) {
        this.endpointService = endpointService;
        this.deliveries = deliveries;
        this.properties = properties;
    }

    public record CreateEndpointRequest(@Size(max = 500) String url, SimulatedBehavior simulatedBehavior,
                                        @Size(max = 255) String description) {
    }

    /** {@code secret} is only returned once, on creation. */
    public record EndpointResponse(UUID id, String url, String description, boolean enabled,
                                   SimulatedBehavior simulatedBehavior, String secret, String secretPreview,
                                   Instant createdAt) {

        static EndpointResponse from(WebhookEndpoint e, boolean includeSecret) {
            return new EndpointResponse(e.getId(), e.getUrl(), e.getDescription(), e.isEnabled(),
                    e.getSimulatedBehavior(), includeSecret ? e.getSecret() : null,
                    e.getSecret().substring(0, 10) + "…", e.getCreatedAt());
        }
    }

    public record DeliveryDetail(WebhookDeliveryStore.Delivery delivery, List<WebhookDeliveryStore.Attempt> attempts,
                                 int maxAttempts) {
    }

    @PostMapping("/webhook-endpoints")
    @Operation(summary = "Register a webhook endpoint",
            description = "Omit url to use FinFlow's built-in simulated receiver with the chosen behavior. "
                    + "External URLs are only accepted when the operator allow-lists them.")
    public ResponseEntity<EndpointResponse> createEndpoint(@AuthenticationPrincipal MerchantPrincipal merchant,
                                                           @Valid @RequestBody CreateEndpointRequest request) {
        WebhookEndpoint endpoint = endpointService.create(merchant.merchantId(), request.url(),
                request.simulatedBehavior(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(EndpointResponse.from(endpoint, true));
    }

    @GetMapping("/webhook-endpoints")
    @Operation(summary = "List webhook endpoints")
    public List<EndpointResponse> endpoints(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return endpointService.list(merchant.merchantId()).stream().map(e -> EndpointResponse.from(e, false)).toList();
    }

    @PostMapping("/webhook-endpoints/{endpointId}/disable")
    @Operation(summary = "Stop delivering to an endpoint")
    public EndpointResponse disable(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID endpointId) {
        return EndpointResponse.from(endpointService.setEnabled(merchant.merchantId(), endpointId, false), false);
    }

    @PostMapping("/webhook-endpoints/{endpointId}/enable")
    @Operation(summary = "Resume delivering to an endpoint")
    public EndpointResponse enable(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID endpointId) {
        return EndpointResponse.from(endpointService.setEnabled(merchant.merchantId(), endpointId, true), false);
    }

    @GetMapping("/webhook-deliveries")
    @Operation(summary = "List webhook deliveries, newest first")
    public PageResponse<WebhookDeliveryStore.Delivery> deliveries(
            @AuthenticationPrincipal MerchantPrincipal merchant,
            @RequestParam(required = false) WebhookDeliveryStore.Status status,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = Paging.DEFAULT_SIZE) @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return deliveries.list(merchant.merchantId(), status == null ? null : status.name(), paymentId, page, size);
    }

    @GetMapping("/webhook-deliveries/{deliveryId}")
    @Operation(summary = "A delivery with its full attempt history")
    public DeliveryDetail delivery(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID deliveryId) {
        var delivery = deliveries.find(merchant.merchantId(), deliveryId)
                .orElseThrow(() -> ApiException.notFound("Webhook delivery", deliveryId));
        return new DeliveryDetail(delivery, deliveries.attempts(deliveryId), properties.maxAttempts());
    }

    @PostMapping("/webhook-deliveries/{deliveryId}/redeliver")
    @Operation(summary = "Start a new retry cycle for a FAILED or SUCCEEDED delivery")
    public DeliveryDetail redeliver(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID deliveryId) {
        if (deliveries.redeliver(merchant.merchantId(), deliveryId) == 0) {
            deliveries.find(merchant.merchantId(), deliveryId)
                    .orElseThrow(() -> ApiException.notFound("Webhook delivery", deliveryId));
            throw new ApiException(com.finflow.common.error.ErrorCode.INVALID_STATE_TRANSITION,
                    "Only FAILED or SUCCEEDED deliveries can be redelivered");
        }
        return delivery(merchant, deliveryId);
    }
}
