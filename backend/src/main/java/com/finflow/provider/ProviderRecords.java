package com.finflow.provider;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The simulated provider's own storage (schema {@code provider_sim}). Writes run in their own
 * REQUIRES_NEW transactions: like a real external system, the provider's record survives even if
 * FinFlow's surrounding work later rolls back or crashes.
 */
@Component
public class ProviderRecords {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate independentTx;

    public ProviderRecords(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Inserts unless a row with the same (type, idempotency key) exists; returns the stored row. */
    public ProviderTransaction record(ProviderTransaction.Type type, String idempotencyKey, ProviderTransaction.Status status,
                                      UUID merchantId, UUID paymentId, UUID refundId, String reference,
                                      long amountMinor, String currency, String declineCode, String anomaly) {
        return independentTx.execute(s -> {
            jdbc.update("""
                    insert into provider_sim.transactions (idempotency_key, type, status, merchant_id, payment_id,
                        refund_id, provider_reference, amount_minor, currency, decline_code, injected_anomaly)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (type, idempotency_key) do nothing""",
                    idempotencyKey, type.name(), status.name(), merchantId, paymentId, refundId, reference,
                    amountMinor, currency, declineCode, anomaly);
            return jdbc.queryForObject("select * from provider_sim.transactions where type = ? and idempotency_key = ?",
                    ProviderRecords::map, type.name(), idempotencyKey);
        });
    }

    public Optional<ProviderTransaction> authorization(UUID paymentId) {
        return jdbc.query("select * from provider_sim.transactions where type = 'AUTHORIZATION' and idempotency_key = ?",
                ProviderRecords::map, paymentId.toString()).stream().findFirst();
    }

    public List<ProviderTransaction> forMerchant(UUID merchantId) {
        return jdbc.query("select * from provider_sim.transactions where merchant_id = ? order by id",
                ProviderRecords::map, merchantId);
    }

    /** Anomaly injection for demos/tests: the provider captures the same payment a second time. */
    public int injectDuplicateCapture(UUID paymentId) {
        return independentTx.execute(s -> jdbc.update("""
                insert into provider_sim.transactions (idempotency_key, type, status, merchant_id, payment_id, refund_id,
                    provider_reference, amount_minor, currency, injected_anomaly)
                select idempotency_key || '#duplicate', type, status, merchant_id, payment_id, refund_id,
                    provider_reference, amount_minor, currency, 'DUPLICATE_CAPTURE'
                from provider_sim.transactions where type = 'CAPTURE' and payment_id = ?""", paymentId));
    }

    /** Anomaly injection: a provider capture for a payment FinFlow never created. */
    public void injectOrphanCapture(UUID merchantId, long amountMinor, String currency) {
        UUID paymentId = UUID.randomUUID();
        record(ProviderTransaction.Type.CAPTURE, "orphan-" + paymentId, ProviderTransaction.Status.SUCCEEDED, merchantId,
                paymentId, null, "pi_sim_orphan_" + paymentId.toString().substring(0, 8), amountMinor, currency, null,
                "ORPHAN_CAPTURE");
    }

    /** Anomaly injection for demos/tests: the provider "loses" a record. */
    public int deleteCapture(UUID paymentId) {
        return independentTx.execute(s -> jdbc.update(
                "delete from provider_sim.transactions where type = 'CAPTURE' and payment_id = ?", paymentId));
    }

    private static ProviderTransaction map(ResultSet rs, int row) throws SQLException {
        return new ProviderTransaction(rs.getLong("id"),
                ProviderTransaction.Type.valueOf(rs.getString("type")),
                ProviderTransaction.Status.valueOf(rs.getString("status")),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("refund_id", UUID.class),
                rs.getString("provider_reference"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("decline_code"),
                rs.getString("injected_anomaly"),
                rs.getTimestamp("created_at").toInstant());
    }
}
