package com.finflow.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.api.IntegrationTestBase;
import com.finflow.dashboard.DashboardService;
import com.finflow.ledger.LedgerService;
import com.finflow.merchant.ApiKeys;
import com.finflow.merchant.Merchant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DemoDataSeederIntegrationTest extends IntegrationTestBase {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    DashboardService dashboardService;

    @Test
    void seedingProducesConsistentDataThroughTheRealFlows() {
        Merchant merchant = merchants.save(new Merchant("Seed test", "USD", false, ApiKeys.generate()));

        seeder.seed(merchant.getId());

        var summary = dashboardService.summary(merchant.getId());
        assertThat(summary.totalOrders()).isEqualTo(36);
        assertThat(summary.totalPayments()).isGreaterThanOrEqualTo(36);
        assertThat(summary.volumes()).isNotEmpty();
        assertThat(summary.daily().stream().filter(d -> d.succeeded() + d.failed() + d.pending() > 0).count())
                .as("seeded payments are spread over several days")
                .isGreaterThan(5);
        assertThat(ledgerService.balances(merchant.getId()).checks())
                .isNotEmpty()
                .allMatch(LedgerService.CurrencyCheck::balanced);
    }
}
