package com.finflow.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DemoResetIntegrationTest extends IntegrationTestBase {

    @Autowired
    PlatformTransactionManager transactionManager;

    private int ledgerRows(String merchantSql, Object arg) {
        Integer n = jdbc.queryForObject("select count(*) from ledger_entries where merchant_id " + merchantSql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    @Test
    void resetWipesOnlyTheDemoMerchantAndNeedsAnOperator() throws Exception {
        String realMerchant = newMerchantKey();
        String realPayment = payAndRead(realMerchant, createOrder(realMerchant, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        refund(realMerchant, realPayment, 2_00L);

        String demoKey = json(postJson(null, "/api/v1/demo/session", null).andReturn()).read("$.apiKey");
        String demoPayment = payAndRead(demoKey, createOrder(demoKey, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        var realId = merchantIdOf(realMerchant);
        int realLedgerBefore = ledgerRows("= ?", realId);

        // Demo visitors cannot reset (or reach any ops function); only the operator token can.
        mvc.perform(post("/api/ops/demo/reset").header("X-API-Key", demoKey)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/demo/reset").header("X-Ops-Token", OPS_TOKEN)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from payments where id = ?::uuid", Integer.class, demoPayment)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries l join merchants m on m.id = l.merchant_id "
                + "where m.demo", Integer.class)).isZero();
        // The real merchant's records are untouched.
        assertThat(ledgerRows("= ?", realId)).isEqualTo(realLedgerBefore);
        getJson(realMerchant, "/api/v1/payments/" + realPayment).andExpect(status().isOk());
        // The demo still works and has its simulated webhook receiver again.
        getJson(demoKey, "/api/v1/webhook-endpoints").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from webhook_endpoints e join merchants m on m.id = e.merchant_id "
                + "where m.demo", Integer.class)).isEqualTo(1);
    }

    @Test
    void evenWithTheResetFlagTheDatabaseRefusesToDeleteARealMerchantsLedger() throws Exception {
        String realMerchant = newMerchantKey();
        payAndRead(realMerchant, createOrder(realMerchant, 10_00), "tok_success", "AUTOMATIC");
        var realId = merchantIdOf(realMerchant);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbc.queryForObject("select set_config('finflow.allow_demo_reset', 'on', true)", String.class);
            jdbc.update("delete from ledger_entries where merchant_id = ?", realId);
        })).rootCause().hasMessageContaining("append-only: DELETE");
        assertThat(ledgerRows("= ?", realId)).isEqualTo(2);
    }
}
