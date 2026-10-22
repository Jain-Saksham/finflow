package com.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIntegrityIntegrationTest extends IntegrationTestBase {

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LedgerService ledgerService;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String capturedPayment(String key, long amount) throws Exception {
        return payAndRead(key, createOrder(key, amount), "tok_success", "AUTOMATIC").read("$.id");
    }

    private UUID captureJournal(String paymentId) {
        return jdbc.queryForObject("select distinct journal_id from ledger_entries where payment_id = ?::uuid "
                + "and journal_type = 'CAPTURE'", UUID.class, paymentId);
    }

    @Test
    void theDatabaseRefusesToCommitAnUnbalancedJournal() throws Exception {
        String key = newMerchantKey();
        // Authorized only, so no capture journal exists yet and only the balance rule can object.
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "MANUAL").read("$.id");
        UUID merchantId = merchantIdOf(key);
        UUID journal = UUID.randomUUID();

        // Bypass all Java validation: write one-sided rows directly with SQL.
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
            jdbc.update("""
                    insert into ledger_entries (journal_id, journal_type, merchant_id, payment_id, account, direction,
                        amount_minor, currency, description, created_at)
                    values (?, 'CAPTURE', ?, ?::uuid, 'PROVIDER_RECEIVABLE', 'DEBIT', 500, 'USD', 'bad', now())""",
                    journal, merchantId, paymentId);
        })).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("is unbalanced by 500");

        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where journal_id = ?", Integer.class, journal))
                .isZero();
    }

    @Test
    void postedEntriesCannotBeUpdatedOrDeleted() throws Exception {
        String paymentId = capturedPayment(newMerchantKey(), 10_00);
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount_minor = 1 where payment_id = ?::uuid", paymentId))
                .rootCause().hasMessageContaining("append-only: UPDATE");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where payment_id = ?::uuid", paymentId))
                .rootCause().hasMessageContaining("append-only: DELETE");
        assertThatThrownBy(() -> jdbc.execute("truncate ledger_entries cascade"))
                .rootCause().hasMessageContaining("append-only: TRUNCATE");
    }

    @Test
    void theSameCaptureCannotBePostedTwice() throws Exception {
        String key = newMerchantKey();
        String paymentId = capturedPayment(key, 10_00);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> ledgerService.post(
                Journal.capture(merchantIdOf(key), UUID.fromString(paymentId), 10_00, "USD"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where payment_id = ?::uuid", Integer.class,
                paymentId)).isEqualTo(2);
    }

    @Test
    void everyJournalInTheDatabaseBalancesInItsCurrency() throws Exception {
        String key = newMerchantKey();
        String a = capturedPayment(key, 33_33);
        refund(key, a, 11_11L);
        refund(key, a, null);
        capturedPayment(key, 1);

        List<UUID> unbalanced = jdbc.queryForList("""
                select journal_id from ledger_entries
                group by journal_id
                having sum(case direction when 'DEBIT' then amount_minor else -amount_minor end) <> 0
                    or count(distinct currency) <> 1""", UUID.class);
        assertThat(unbalanced).isEmpty();
    }

    @Test
    void reversalMirrorsTheJournalOnceAndIsVisibleToReconciliation() throws Exception {
        String key = newMerchantKey();
        UUID merchantId = merchantIdOf(key);
        String paymentId = capturedPayment(key, 50_00);
        UUID journal = captureJournal(paymentId);
        String url = "/api/ops/merchants/" + merchantId + "/ledger/journals/" + journal + "/reverse";
        String body = "{\"reason\":\"Posted in error\"}";

        // Operator credentials are required; a merchant API key is not enough.
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String reversalId = json(mvc.perform(post(url).header("X-Ops-Token", OPS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn()).read("$.reversalJournalId");

        // Originals untouched, mirror posted, account balances back to zero for this merchant.
        getJson(key, "/api/v1/ledger/entries?paymentId=" + paymentId).andExpect(jsonPath("$.totalElements").value(4));
        getJson(key, "/api/v1/ledger/balances")
                .andExpect(jsonPath("$.accounts[?(@.account=='PROVIDER_RECEIVABLE')].balanceMinor").value(0))
                .andExpect(jsonPath("$.checks[0].balanced").value(true));

        mvc.perform(post(url).header("X-Ops-Token", OPS_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEDGER_JOURNAL_ALREADY_REVERSED"));
        mvc.perform(post("/api/ops/merchants/" + merchantId + "/ledger/journals/" + reversalId + "/reverse")
                        .header("X-Ops-Token", OPS_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // The payment still says CAPTURED, but its ledger no longer does: reconciliation must flag it.
        postJson(key, "/api/v1/reconciliation/runs", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].type").value("LEDGER_MISMATCH"))
                .andExpect(jsonPath("$.findings[0].resolution").value("OPEN"));
    }
}
