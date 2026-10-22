package com.finflow.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** JDBC access used by the publisher. Every method must run inside the publisher's transaction. */
@Component
public class OutboxStore {

    /** Arbitrary constant: the pg advisory lock that makes the publisher a singleton across instances. */
    static final long PUBLISHER_LOCK_ID = 7_413_001L;

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingEvent(UUID id, UUID aggregateId, String eventType, String payload, String correlationId,
                               int attempts) {
    }

    /**
     * Only one publisher instance may run at a time (transaction-scoped advisory lock), which keeps
     * per-payment event order intact even when several backend instances are running.
     */
    public boolean tryAcquirePublisherLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class,
                PUBLISHER_LOCK_ID));
    }

    public List<PendingEvent> lockNextBatch(int limit) {
        return jdbc.query("""
                select id, aggregate_id, event_type, payload, correlation_id, publish_attempts
                from outbox_events
                where status = 'PENDING' and next_attempt_at <= now()
                order by sequence_no
                limit ?
                for update skip locked""",
                (rs, i) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"), rs.getString("payload"), rs.getString("correlation_id"),
                        rs.getInt("publish_attempts")),
                limit);
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("update outbox_events set status = 'PUBLISHED', published_at = now(), "
                + "publish_attempts = publish_attempts + 1, last_error = null where id = any(?)",
                (java.sql.PreparedStatement ps) -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())));
    }

    public void markAttemptFailed(UUID id, int attempts, boolean giveUp, Duration backoff, String error) {
        jdbc.update("update outbox_events set status = ?, publish_attempts = ?, last_error = ?, "
                        + "next_attempt_at = now() + (? * interval '1 millisecond') where id = ?",
                giveUp ? "FAILED" : "PENDING", attempts, truncate(error), backoff.toMillis(), id);
    }

    public record Stats(long pending, long failed, Instant oldestPendingCreatedAt) {
    }

    public Stats stats() {
        return jdbc.queryForObject("""
                select count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'FAILED') as failed,
                       min(created_at) filter (where status = 'PENDING') as oldest
                from outbox_events""",
                (rs, i) -> new Stats(rs.getLong("pending"), rs.getLong("failed"),
                        rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
