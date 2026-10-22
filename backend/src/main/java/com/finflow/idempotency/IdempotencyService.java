package com.finflow.idempotency;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable idempotency for dangerous operations (payment creation, refunds, capture, void).
 *
 * <p>Semantics for a repeated {@code Idempotency-Key} (scoped per merchant):
 * <ul>
 *   <li>same operation and identical request, first one completed: the stored status code and body
 *       are returned unchanged, with {@code Idempotent-Replayed: true}. Nothing executes again;</li>
 *   <li>same key, different operation or request body: {@code 409 IDEMPOTENCY_KEY_REUSED};</li>
 *   <li>same key while the first request is still running: {@code 409 IDEMPOTENCY_REQUEST_IN_PROGRESS}
 *       (retry later). A request that crashed leaves its lease to expire, after which a retry takes
 *       over and re-executes; that is safe because the operations themselves are idempotent on
 *       domain keys (unique payment/refund idempotency keys, the payment state machine).</li>
 * </ul>
 * Business rejections (4xx) are stored and replayed like successes. Transient failures (5xx,
 * provider unavailable, lock conflicts) release the key so the client can retry with it.
 *
 * <p>The INSERT ... ON CONFLICT on {@code (merchant_id, idempotency_key)} is what makes concurrent
 * duplicates safe: exactly one request can own a key. Redis is deliberately not involved.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Set<ErrorCode> TRANSIENT = Set.of(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.RATE_LIMITED);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final IdempotencyProperties properties;
    private final MeterRegistry registry;

    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json, IdempotencyProperties properties,
                              MeterRegistry registry) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.registry = registry;
    }

    /** Result of an idempotent call; {@code replayed} means the body came from storage. */
    public record Outcome<T>(T body, int status, boolean replayed, UUID resourceId) {
    }

    private record StoredRecord(long id, String operation, String fingerprint, String status, int responseStatus,
                                String responseBody, String errorCode, String errorMessage, UUID resourceId) {
    }

    public <T> Outcome<T> execute(UUID merchantId, String key, String operation, Object request, Class<T> type,
                                  Supplier<Outcome<T>> action) {
        String fingerprint = fingerprint(operation, request);
        long recordId;
        int inserted = jdbc.update("""
                insert into idempotency_records (merchant_id, idempotency_key, operation, request_fingerprint, status,
                                                 locked_until, created_at)
                values (?, ?, ?, ?, 'IN_PROGRESS', now() + (? * interval '1 millisecond'), now())
                on conflict (merchant_id, idempotency_key) do nothing""",
                merchantId, key, operation, fingerprint, properties.lease().toMillis());
        StoredRecord stored = find(merchantId, key).orElseThrow();
        if (inserted == 1) {
            recordId = stored.id();
            count("new");
        } else {
            if (!stored.operation().equals(operation) || !stored.fingerprint().equals(fingerprint)) {
                count("key_reused");
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request");
            }
            if (stored.status().equals("COMPLETED")) {
                count("replayed");
                return replay(stored, type);
            }
            int takenOver = jdbc.update("""
                    update idempotency_records set locked_until = now() + (? * interval '1 millisecond')
                    where id = ? and status = 'IN_PROGRESS' and locked_until < now()""",
                    properties.lease().toMillis(), stored.id());
            if (takenOver == 0) {
                count("in_progress");
                throw new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                        "A request with this Idempotency-Key is still being processed. Retry shortly.");
            }
            log.warn("Taking over idempotency key {} whose previous request did not finish", key);
            count("taken_over");
            recordId = stored.id();
        }

        try {
            Outcome<T> outcome = action.get();
            jdbc.update("""
                    update idempotency_records set status = 'COMPLETED', response_status = ?, response_body = ?,
                        resource_id = ?, completed_at = now(), locked_until = null
                    where id = ?""",
                    outcome.status(), json.writeValueAsString(outcome.body()), outcome.resourceId(), recordId);
            return outcome;
        } catch (ApiException e) {
            if (e.code().status().is4xxClientError() && !TRANSIENT.contains(e.code())) {
                jdbc.update("""
                        update idempotency_records set status = 'COMPLETED', response_status = ?, error_code = ?,
                            error_message = ?, completed_at = now(), locked_until = null
                        where id = ?""",
                        e.code().status().value(), e.code().name(), truncate(e.getMessage()), recordId);
            } else {
                release(recordId);
            }
            throw e;
        } catch (RuntimeException e) {
            release(recordId);
            throw e;
        }
    }

    private void release(long recordId) {
        jdbc.update("delete from idempotency_records where id = ? and status = 'IN_PROGRESS'", recordId);
    }

    private <T> Outcome<T> replay(StoredRecord stored, Class<T> type) {
        if (stored.errorCode() != null) {
            throw new ApiException(ErrorCode.valueOf(stored.errorCode()), stored.errorMessage());
        }
        return new Outcome<>(json.readValue(stored.responseBody(), type), stored.responseStatus(), true,
                stored.resourceId());
    }

    private Optional<StoredRecord> find(UUID merchantId, String key) {
        return jdbc.query("""
                select id, operation, request_fingerprint, status, response_status, response_body, error_code,
                       error_message, resource_id
                from idempotency_records where merchant_id = ? and idempotency_key = ?""",
                (rs, i) -> new StoredRecord(rs.getLong("id"), rs.getString("operation"),
                        rs.getString("request_fingerprint"), rs.getString("status"), rs.getInt("response_status"),
                        rs.getString("response_body"), rs.getString("error_code"), rs.getString("error_message"),
                        rs.getObject("resource_id", UUID.class)),
                merchantId, key).stream().findFirst();
    }

    String fingerprint(String operation, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((operation + "\n" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgeExpired() {
        int deleted = jdbc.update("delete from idempotency_records where created_at < now() - (? * interval '1 second')",
                properties.retention().toSeconds());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency records", deleted);
        }
    }

    private void count(String result) {
        registry.counter("finflow.idempotency.requests", "result", result).increment();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
