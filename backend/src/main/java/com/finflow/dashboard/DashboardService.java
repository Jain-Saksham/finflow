package com.finflow.dashboard;

import com.finflow.cache.CacheProperties;
import com.finflow.cache.JsonCache;
import com.finflow.events.PaymentStateChanged;
import com.finflow.order.OrderRepository;
import com.finflow.payment.PaymentDtos.PaymentResponse;
import com.finflow.payment.PaymentService;
import com.finflow.payment.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Dashboard aggregates computed live from the payments table with plain SQL (a read model:
 * GROUP BY / FILTER is clearer here than JPQL). Nothing on the dashboard is hardcoded.
 */
@Service
public class DashboardService {

    static final int DAYS = 14;

    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentService paymentService;
    private final OrderRepository orders;
    private final Clock clock;
    private final JsonCache cache;
    private final CacheProperties cacheProperties;

    static final String CACHE = "dashboard";

    public DashboardService(NamedParameterJdbcTemplate jdbc, PaymentService paymentService, OrderRepository orders,
                            JsonCache cache, CacheProperties cacheProperties) {
        this.jdbc = jdbc;
        this.paymentService = paymentService;
        this.orders = orders;
        this.clock = Clock.systemUTC();
        this.cache = cache;
        this.cacheProperties = cacheProperties;
    }

    public record CurrencyVolume(String currency, long capturedMinor, long refundedMinor, long netMinor,
                                 long capturedCount) {
    }

    public record DailyPoint(LocalDate date, long succeeded, long failed, long pending) {
    }

    public record FailureReason(String code, long count) {
    }

    /**
     * @param authorizationRatePercent authorized-or-better / (that + declined + failed); null when
     *                                 there are no completed attempts yet
     */
    public record Summary(long totalPayments, long totalOrders, Map<PaymentStatus, Long> statusCounts,
                          BigDecimal authorizationRatePercent, List<CurrencyVolume> volumes,
                          List<DailyPoint> daily, List<FailureReason> failureReasons,
                          List<PaymentResponse> recentPayments) {
    }

    /** Cached for at most {@code finflow.cache.dashboard-ttl}; evicted after any payment change commits. */
    public Summary summary(UUID merchantId) {
        return cache.getOrLoad(CACHE, merchantId.toString(), Summary.class, cacheProperties.dashboardTtl(),
                () -> compute(merchantId));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPaymentChanged(PaymentStateChanged event) {
        cache.evict(CACHE, event.merchantId().toString());
    }

    @Transactional(readOnly = true)
    public Summary compute(UUID merchantId) {
        var params = new MapSqlParameterSource("merchantId", merchantId);

        Map<PaymentStatus, Long> statusCounts = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus s : PaymentStatus.values()) {
            statusCounts.put(s, 0L);
        }
        jdbc.query("select status, count(*) as n from payments where merchant_id = :merchantId group by status",
                params, rs -> {
                    statusCounts.put(PaymentStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        long total = statusCounts.values().stream().mapToLong(Long::longValue).sum();

        long succeeded = count(statusCounts, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED,
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED, PaymentStatus.VOIDED);
        long failed = count(statusCounts, PaymentStatus.DECLINED, PaymentStatus.FAILED);
        BigDecimal rate = succeeded + failed == 0 ? null
                : BigDecimal.valueOf(succeeded * 100L).divide(BigDecimal.valueOf(succeeded + failed), 1, RoundingMode.HALF_UP);

        List<CurrencyVolume> volumes = jdbc.query("""
                select currency, sum(captured_amount_minor) as captured, sum(refunded_amount_minor) as refunded,
                       count(*) as n
                from payments
                where merchant_id = :merchantId and captured_amount_minor > 0
                group by currency order by captured desc""", params,
                (rs, i) -> new CurrencyVolume(rs.getString("currency"), rs.getLong("captured"), rs.getLong("refunded"),
                        rs.getLong("captured") - rs.getLong("refunded"), rs.getLong("n")));

        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(DAYS - 1);
        Map<LocalDate, DailyPoint> byDay = new HashMap<>();
        jdbc.query("""
                select (created_at at time zone 'UTC')::date as day,
                       count(*) filter (where status in ('AUTHORIZED','CAPTURED','PARTIALLY_REFUNDED','REFUNDED','VOIDED')) as succeeded,
                       count(*) filter (where status in ('DECLINED','FAILED')) as failed,
                       count(*) filter (where status in ('CREATED','TIMED_OUT')) as pending
                from payments
                where merchant_id = :merchantId and created_at >= :from
                group by day""",
                params.addValue("from", Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant())),
                rs -> {
                    LocalDate day = rs.getObject("day", LocalDate.class);
                    byDay.put(day, new DailyPoint(day, rs.getLong("succeeded"), rs.getLong("failed"), rs.getLong("pending")));
                });
        List<DailyPoint> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(byDay.getOrDefault(d, new DailyPoint(d, 0, 0, 0)));
        }

        List<FailureReason> failureReasons = jdbc.query("""
                select failure_code, count(*) as n from payments
                where merchant_id = :merchantId and status in ('DECLINED','FAILED','TIMED_OUT') and failure_code is not null
                group by failure_code order by n desc, failure_code""", params,
                (rs, i) -> new FailureReason(rs.getString("failure_code"), rs.getLong("n")));

        List<PaymentResponse> recent = paymentService
                .list(merchantId, null, null, PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(PaymentResponse::from)
                .getContent();

        return new Summary(total, orders.countByMerchantId(merchantId), statusCounts, rate, volumes, daily,
                failureReasons, recent);
    }

    private static long count(Map<PaymentStatus, Long> counts, PaymentStatus... statuses) {
        long n = 0;
        for (PaymentStatus s : statuses) {
            n += counts.get(s);
        }
        return n;
    }
}
