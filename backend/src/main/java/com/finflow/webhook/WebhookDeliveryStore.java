package com.finflow.webhook;

import com.finflow.common.web.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WebhookDeliveryStore {

    private final JdbcTemplate jdbc;

    public WebhookDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Status { PENDING, IN_PROGRESS, RETRYING, SUCCEEDED, FAILED }

    public record Delivery(UUID id, UUID endpointId, String endpointUrl, UUID eventId, String eventType, UUID paymentId,
                           Status status, int attemptCount, Instant nextAttemptAt, Integer lastResponseStatus,
                           String lastError, Instant createdAt, Instant updatedAt, Instant succeededAt) {
    }

    public record Attempt(int attemptNo, String outcome, Integer responseStatus, String responseBody, String error,
                          int durationMs, Instant startedAt) {
    }

    public record Claimed(UUID id, UUID endpointId, UUID eventId, String eventType, String payload, int attemptCount,
                          String correlationId) {
    }

    /**
     * One row per enabled endpoint of the merchant. ON CONFLICT on (endpoint_id, event_id) makes this
     * idempotent: Kafka redelivering the same event creates nothing new.
     *
     * @return number of deliveries created (0 for a duplicate event)
     */
    public int fanOut(UUID merchantId, UUID eventId, String eventType, UUID paymentId, String payload,
                      String correlationId) {
        return jdbc.update("""
                insert into webhook_deliveries (id, endpoint_id, merchant_id, event_id, event_type, payment_id, payload,
                    status, attempt_count, next_attempt_at, correlation_id, created_at, updated_at)
                select gen_random_uuid(), e.id, e.merchant_id, ?, ?, ?, ?, 'PENDING', 0, now(), ?, now(), now()
                from webhook_endpoints e
                where e.merchant_id = ? and e.enabled
                on conflict (endpoint_id, event_id) do nothing""",
                eventId, eventType, paymentId, payload, correlationId, merchantId);
    }

    /** Claims due deliveries, including ones whose previous worker's lease expired (crash recovery). */
    public List<Claimed> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                update webhook_deliveries d
                set status = 'IN_PROGRESS', locked_until = now() + (? * interval '1 millisecond'), updated_at = now()
                where d.id in (
                    select id from webhook_deliveries
                    where (status in ('PENDING', 'RETRYING') and next_attempt_at <= now())
                       or (status = 'IN_PROGRESS' and locked_until < now())
                    order by next_attempt_at
                    limit ?
                    for update skip locked)
                returning d.id, d.endpoint_id, d.event_id, d.event_type, d.payload, d.attempt_count, d.correlation_id""",
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("endpoint_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempt_count"), rs.getString("correlation_id")),
                lease.toMillis(), limit);
    }

    /**
     * Records the attempt and moves the delivery on. Guarded by {@code status = 'IN_PROGRESS'}: if a
     * slower worker whose lease expired finishes after the new owner, only its attempt is recorded.
     *
     * @return the new status
     */
    @Transactional
    public Status recordAttempt(UUID deliveryId, boolean success, Integer responseStatus, String responseBody,
                                String error, int durationMs, Instant startedAt, List<Duration> retrySchedule) {
        jdbc.queryForObject("select id from webhook_deliveries where id = ? for update", UUID.class, deliveryId);
        Integer attemptNo = jdbc.queryForObject(
                "select coalesce(max(attempt_no), 0) + 1 from webhook_delivery_attempts where delivery_id = ?",
                Integer.class, deliveryId);
        jdbc.update("""
                insert into webhook_delivery_attempts (delivery_id, attempt_no, outcome, response_status, response_body,
                    error, duration_ms, started_at) values (?, ?, ?, ?, ?, ?, ?, ?)""",
                deliveryId, attemptNo, success ? "SUCCEEDED" : "FAILED", responseStatus, truncate(responseBody, 1000),
                truncate(error, 500), durationMs, Timestamp.from(startedAt));
        Integer cycleAttempts = jdbc.queryForObject(
                "select attempt_count + 1 from webhook_deliveries where id = ?", Integer.class, deliveryId);
        Status next;
        Duration delay = null;
        if (success) {
            next = Status.SUCCEEDED;
        } else if (cycleAttempts > retrySchedule.size()) {
            next = Status.FAILED;
        } else {
            next = Status.RETRYING;
            delay = retrySchedule.get(cycleAttempts - 1);
        }
        int updated = jdbc.update("""
                update webhook_deliveries
                set status = ?, attempt_count = attempt_count + 1, last_response_status = ?, last_error = ?,
                    locked_until = null, updated_at = now(),
                    next_attempt_at = case when ? then now() + (? * interval '1 millisecond') else null end,
                    succeeded_at = case when ? then now() else succeeded_at end
                where id = ? and status = 'IN_PROGRESS'""",
                next.name(), responseStatus, success ? null : truncate(error, 500),
                delay != null, delay == null ? 0 : delay.toMillis(), success, deliveryId);
        return updated == 1 ? next : Status.IN_PROGRESS;
    }

    public void markFailed(UUID deliveryId, String reason) {
        jdbc.update("update webhook_deliveries set status = 'FAILED', last_error = ?, locked_until = null, "
                + "next_attempt_at = null, updated_at = now() where id = ?", reason, deliveryId);
    }

    /** Manual redelivery: a fresh retry cycle; previous attempts stay in the history. */
    public int redeliver(UUID merchantId, UUID deliveryId) {
        return jdbc.update("""
                update webhook_deliveries set status = 'PENDING', attempt_count = 0, next_attempt_at = now(),
                    locked_until = null, updated_at = now()
                where id = ? and merchant_id = ? and status in ('FAILED', 'SUCCEEDED')""", deliveryId, merchantId);
    }

    public PageResponse<Delivery> list(UUID merchantId, String status, UUID paymentId, int page, int size) {
        StringBuilder where = new StringBuilder(" where d.merchant_id = ?");
        List<Object> args = new ArrayList<>(List.of(merchantId));
        if (status != null) {
            where.append(" and d.status = ?");
            args.add(status);
        }
        if (paymentId != null) {
            where.append(" and d.payment_id = ?");
            args.add(paymentId);
        }
        Long total = jdbc.queryForObject("select count(*) from webhook_deliveries d" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Delivery> content = jdbc.query("select d.*, e.url from webhook_deliveries d join webhook_endpoints e "
                + "on e.id = d.endpoint_id" + where + " order by d.created_at desc, d.id limit ? offset ?",
                WebhookDeliveryStore::mapDelivery, pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, page, size, t, (int) Math.ceil(t / (double) size));
    }

    public Optional<Delivery> find(UUID merchantId, UUID deliveryId) {
        return jdbc.query("select d.*, e.url from webhook_deliveries d join webhook_endpoints e on e.id = d.endpoint_id "
                + "where d.id = ? and d.merchant_id = ?", WebhookDeliveryStore::mapDelivery, deliveryId, merchantId)
                .stream().findFirst();
    }

    public List<Attempt> attempts(UUID deliveryId) {
        return jdbc.query("select * from webhook_delivery_attempts where delivery_id = ? order by attempt_no",
                (rs, i) -> new Attempt(rs.getInt("attempt_no"), rs.getString("outcome"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body"), rs.getString("error"),
                        rs.getInt("duration_ms"), rs.getTimestamp("started_at").toInstant()),
                deliveryId);
    }

    public long countDue() {
        Long n = jdbc.queryForObject("select count(*) from webhook_deliveries where status in ('PENDING', 'RETRYING', "
                + "'IN_PROGRESS')", Long.class);
        return n == null ? 0 : n;
    }

    private static Delivery mapDelivery(ResultSet rs, int i) throws SQLException {
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("endpoint_id", UUID.class), rs.getString("url"),
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getObject("payment_id", UUID.class),
                Status.valueOf(rs.getString("status")), rs.getInt("attempt_count"), instant(rs, "next_attempt_at"),
                (Integer) rs.getObject("last_response_status"), rs.getString("last_error"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "succeeded_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
