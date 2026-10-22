package com.finflow.demo;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wipes and re-seeds the shared demo merchant so the public demo stays small and tidy.
 *
 * <p>Three independent guards keep this away from real data: demo mode must be enabled; every
 * DELETE is filtered on the merchant row having {@code demo = true}; and the ledger's append-only
 * trigger only lets a ledger row be deleted under the reset flag when its merchant is a demo
 * merchant (V3 migration). Operator-only (X-Ops-Token) or on an operator-configured cron.
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    /** Children before parents; every statement is scoped to the demo merchant. */
    private static final String[] DELETES = {
            "delete from webhook_delivery_attempts where delivery_id in (select id from webhook_deliveries where merchant_id = ?)",
            "delete from webhook_receipts where endpoint_id in (select id from webhook_endpoints where merchant_id = ?)",
            "delete from webhook_deliveries where merchant_id = ?",
            "delete from webhook_endpoints where merchant_id = ?",
            "delete from reconciliation_findings where merchant_id = ?",
            "delete from reconciliation_runs where merchant_id = ?",
            "delete from idempotency_records where merchant_id = ?",
            "delete from outbox_events where merchant_id = ?",
            "delete from ledger_entries where merchant_id = ?",
            "delete from refunds where merchant_id = ?",
            "delete from payment_events where merchant_id = ?",
            "delete from payments where merchant_id = ?",
            "delete from orders where merchant_id = ?",
            "delete from provider_sim.transactions where merchant_id = ?",
    };

    private final DemoProperties properties;
    private final DemoAccount demoAccount;
    private final DemoDataSeeder seeder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();

    public DemoResetService(DemoProperties properties, DemoAccount demoAccount, DemoDataSeeder seeder, JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.demoAccount = demoAccount;
        this.seeder = seeder;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${finflow.demo.reset-cron:-}", zone = "UTC")
    public void scheduledReset() {
        try {
            reset();
        } catch (RuntimeException e) {
            log.error("Scheduled demo reset failed", e);
        }
    }

    public Map<String, Object> reset() {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Demo mode is disabled; there is no demo data to reset");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "A demo reset is already running");
        }
        try {
            UUID merchantId = demoAccount.session().orElseThrow().merchant().getId();
            Boolean isDemo = jdbc.queryForObject("select demo from merchants where id = ?", Boolean.class, merchantId);
            if (!Boolean.TRUE.equals(isDemo)) {
                throw new IllegalStateException("Refusing to reset non-demo merchant " + merchantId);
            }
            int deleted = tx.execute(status -> {
                jdbc.queryForObject("select set_config('finflow.allow_demo_reset', 'on', true)", String.class);
                int n = 0;
                for (String sql : DELETES) {
                    n += jdbc.update(sql + " and ? in (select id from merchants where demo)", merchantId, merchantId);
                }
                return n;
            });
            log.warn("Demo reset deleted {} rows for demo merchant {}; re-seeding", deleted, merchantId);
            seeder.reseed(merchantId);
            return Map.of("merchantId", merchantId, "deletedRows", deleted);
        } finally {
            running.set(false);
        }
    }
}
