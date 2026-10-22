package com.finflow.demo;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo")
public class DemoController {

    private final DemoAccount demoAccount;

    public DemoController(DemoAccount demoAccount) {
        this.demoAccount = demoAccount;
    }

    public record DemoSessionResponse(String apiKey, UUID merchantId, String merchantName, boolean simulation,
                                      String notice) {
    }

    @PostMapping("/session")
    @SecurityRequirements
    @Operation(summary = "Get the API key of the shared demo merchant (demo mode only)")
    public DemoSessionResponse session() {
        DemoAccount.Session session = demoAccount.session()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Demo mode is disabled"));
        return new DemoSessionResponse(session.apiKey(), session.merchant().getId(), session.merchant().getName(), true,
                "Simulation only: fake merchant, fake payment tokens, no real money. Data is shared by all demo visitors.");
    }
}
