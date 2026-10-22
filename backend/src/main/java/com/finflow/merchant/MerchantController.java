package com.finflow.merchant;

import com.finflow.common.error.ApiException;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@Tag(name = "Merchants")
public class MerchantController {

    private final MerchantRepository merchants;

    public MerchantController(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    public record MerchantResponse(UUID id, String name, String defaultCurrency, boolean demo,
                                   String apiKeyPrefix, Instant createdAt) {
    }

    @GetMapping("/me")
    @Operation(summary = "The merchant that owns the API key used for this request")
    public MerchantResponse me(@AuthenticationPrincipal MerchantPrincipal principal) {
        Merchant m = merchants.findById(principal.merchantId())
                .orElseThrow(() -> ApiException.notFound("Merchant", principal.merchantId()));
        return new MerchantResponse(m.getId(), m.getName(), m.getDefaultCurrency(), m.isDemo(),
                m.getApiKeyPrefix(), m.getCreatedAt());
    }
}
