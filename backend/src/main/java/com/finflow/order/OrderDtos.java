package com.finflow.order;

import com.finflow.common.Currencies;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "may contain only letters, digits, '.', '_' and '-'")
            String merchantReference,
            @NotNull @Positive @Max(value = 100_000_000, message = "must not exceed 1,000,000.00 in major units")
            Long amountMinor,
            @NotBlank @Pattern(regexp = Currencies.PATTERN, message = Currencies.MESSAGE)
            String currency,
            @Size(max = 255)
            String description) {
    }

    public record OrderResponse(UUID id, String merchantReference, long amountMinor, String currency,
                                String description, OrderStatus status, Instant createdAt, Instant updatedAt) {

        public static OrderResponse from(MerchantOrder o) {
            return new OrderResponse(o.getId(), o.getMerchantReference(), o.getAmountMinor(), o.getCurrency(),
                    o.getDescription(), o.getStatus(), o.getCreatedAt(), o.getUpdatedAt());
        }
    }
}
