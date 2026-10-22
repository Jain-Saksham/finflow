package com.finflow.dashboard;

import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Live payment KPIs for the calling merchant (last 14 days for the daily series)")
    public DashboardService.Summary summary(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return dashboardService.summary(merchant.merchantId());
    }
}
