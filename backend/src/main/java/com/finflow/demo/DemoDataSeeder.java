package com.finflow.demo;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.merchant.Merchant;
import com.finflow.order.MerchantOrder;
import com.finflow.order.OrderDtos.CreateOrderRequest;
import com.finflow.order.OrderRepository;
import com.finflow.order.OrderService;
import com.finflow.payment.CaptureMode;
import com.finflow.payment.Payment;
import com.finflow.payment.PaymentDtos.CreatePaymentRequest;
import com.finflow.payment.PaymentService;
import com.finflow.payment.PaymentStatus;
import com.finflow.refund.RefundDtos.CreateRefundRequest;
import com.finflow.provider.ProviderRecords;
import com.finflow.reconciliation.ReconciliationService;
import com.finflow.refund.RefundService;
import com.finflow.webhook.SimulatedBehavior;
import com.finflow.webhook.WebhookEndpointService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the demo merchant by driving the <em>real</em> services (orders, provider simulator,
 * captures, refunds, ledger), so seeded data is indistinguishable from data created in the UI.
 * Afterwards each order's records are shifted back in time so the 14-day dashboard has history;
 * that timestamp shift is the only thing that is not produced by the normal flow.
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int ORDER_COUNT = 36;
    private static final long FOURTEEN_DAYS_SECONDS = 14L * 24 * 3600;

    private final DemoAccount demoAccount;
    private final DemoProperties properties;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookEndpointService webhooks;
    private final ProviderRecords providerRecords;
    private final ReconciliationService reconciliation;

    public DemoDataSeeder(DemoAccount demoAccount, DemoProperties properties, OrderService orderService,
                          OrderRepository orders, PaymentService paymentService, RefundService refundService,
                          JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                          WebhookEndpointService webhooks, ProviderRecords providerRecords,
                          ReconciliationService reconciliation) {
        this.demoAccount = demoAccount;
        this.properties = properties;
        this.orderService = orderService;
        this.orders = orders;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.webhooks = webhooks;
        this.providerRecords = providerRecords;
        this.reconciliation = reconciliation;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.enabled()) {
            return;
        }
        Merchant merchant = demoAccount.initialize();
        log.info("Demo merchant ready: {} ({})", merchant.getName(), merchant.getId());
        ensureWebhookEndpoint(merchant.getId());
        if (properties.seed() && orders.countByMerchantId(merchant.getId()) == 0) {
            // Provider calls have simulated latency; seed in the background so startup stays fast.
            Thread.ofVirtual().name("demo-seeder").start(() -> seed(merchant.getId()));
        }
    }

    /** Also for databases seeded before webhooks existed: the demo always has a receiver to watch. */
    private void ensureWebhookEndpoint(UUID merchantId) {
        if (webhooks.list(merchantId).isEmpty()) {
            webhooks.create(merchantId, null, SimulatedBehavior.FAIL_FIRST_ATTEMPT,
                    "Built-in simulated receiver (fails the first attempt of every event)");
        }
    }

    /** After a demo reset: recreate the endpoint and seed synchronously. */
    void reseed(UUID merchantId) {
        ensureWebhookEndpoint(merchantId);
        if (properties.seed()) {
            seed(merchantId);
        }
    }

    void seed(UUID merchantId) {
        Random random = new Random(42); // deterministic demo data
        List<String> products = List.of("Espresso beans 1kg", "Pour-over kit", "Ceramic mug set", "Barista course",
                "Cold brew subscription", "Grinder", "Gift card", "Decaf sampler");
        long started = System.currentTimeMillis();
        List<Payment> captured = new ArrayList<>();
        for (int i = 0; i < ORDER_COUNT; i++) {
            try {
                Payment p = seedOrder(merchantId, i, random, products);
                if (p != null && p.getStatus() == PaymentStatus.CAPTURED) {
                    captured.add(p);
                }
            } catch (RuntimeException e) {
                log.warn("Demo seeding of order {} failed: {}", i, e.getMessage());
            }
        }
        injectProviderAnomalies(merchantId, captured);
        try {
            reconciliation.run(merchantId, ReconciliationService.Trigger.SCHEDULED);
        } catch (RuntimeException e) {
            log.warn("Initial demo reconciliation failed: {}", e.getMessage());
        }
        log.info("Seeded {} demo orders in {} ms", ORDER_COUNT, System.currentTimeMillis() - started);
    }

    /**
     * Provider-side discrepancies, labelled as injected in the provider's own records, so the
     * reconciliation report has something unrecoverable to show. These never touch FinFlow's tables.
     */
    private void injectProviderAnomalies(UUID merchantId, List<Payment> captured) {
        if (captured.size() >= 2) {
            providerRecords.injectDuplicateCapture(captured.get(0).getId());
            providerRecords.deleteCapture(captured.get(1).getId());
        }
        providerRecords.injectOrphanCapture(merchantId, 42_00, "USD");
    }

    private Payment seedOrder(UUID merchantId, int i, Random random, List<String> products) {
        String currency = random.nextInt(10) < 7 ? "USD" : random.nextBoolean() ? "EUR" : "GBP";
        long amount = 500 + random.nextInt(240) * 100L + random.nextInt(100);
        MerchantOrder order = orderService.create(merchantId, new CreateOrderRequest(
                "DEMO-%04d".formatted(1001 + i), amount, currency, products.get(random.nextInt(products.size()))));

        int roll = random.nextInt(100);
        String token = i == 7 ? "tok_crash_after_provider_approval" // recovered by the initial reconciliation
                : roll < 62 ? "tok_success"
                : roll < 72 ? "tok_decline_insufficient_funds"
                : roll < 79 ? "tok_decline_do_not_honor"
                : roll < 84 ? "tok_decline_expired_card"
                : roll < 93 ? "tok_provider_unavailable"
                : "tok_timeout";
        CaptureMode mode = random.nextInt(4) == 0 ? CaptureMode.MANUAL : CaptureMode.AUTOMATIC;
        Payment payment;
        try {
            payment = paymentService.create(merchantId, "seed-" + order.getId(),
                    new CreatePaymentRequest(order.getId(), token, mode)).payment();
        } catch (ApiException e) {
            if (e.code() != ErrorCode.SIMULATED_CRASH) {
                throw e;
            }
            backdate(order.getId(), (long) (random.nextDouble() * FOURTEEN_DAYS_SECONDS));
            return null;
        }

        if (payment.getStatus() == PaymentStatus.FAILED && random.nextBoolean()) {
            // The customer retries after a provider outage, which is allowed because nothing was charged.
            payment = paymentService.create(merchantId, "seed-retry-" + order.getId(),
                    new CreatePaymentRequest(order.getId(), "tok_success", CaptureMode.AUTOMATIC)).payment();
        }
        if (payment.getStatus() == PaymentStatus.TIMED_OUT && random.nextBoolean()) {
            try {
                payment = paymentService.sync(merchantId, payment.getId());
            } catch (ApiException e) {
                payment = paymentService.get(merchantId, payment.getId()); // auto-recovery got there first
            }
        }
        if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
            int choice = random.nextInt(3);
            if (choice == 0) {
                payment = paymentService.capture(merchantId, payment.getId());
            } else if (choice == 1) {
                payment = paymentService.voidAuthorization(merchantId, payment.getId());
            } // otherwise leave it authorized, awaiting capture
        }
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            int refundRoll = random.nextInt(10);
            if (refundRoll < 2) {
                refundService.create(merchantId, payment.getId(), "seed-refund-" + payment.getId(),
                        new CreateRefundRequest(null, "Customer returned the item"));
            } else if (refundRoll < 4) {
                refundService.create(merchantId, payment.getId(), "seed-refund-" + payment.getId(),
                        new CreateRefundRequest(payment.getAmountMinor() / 3, "Partial refund: damaged packaging"));
            }
        }
        backdate(order.getId(), (long) (random.nextDouble() * FOURTEEN_DAYS_SECONDS));
        return paymentService.get(merchantId, payment.getId());
    }

    private void backdate(UUID orderId, long seconds) {
        tx.executeWithoutResult(status -> shiftTimestamps(orderId, seconds));
    }

    private void shiftTimestamps(UUID orderId, long seconds) {
        String shift = " - (? * interval '1 second')";
        String paymentIds = "(select id from payments where order_id = ?)";
        jdbc.update("update orders set created_at = created_at" + shift + ", updated_at = updated_at" + shift
                + " where id = ?", seconds, seconds, orderId);
        jdbc.update("update payments set created_at = created_at" + shift + ", updated_at = updated_at" + shift
                + ", authorized_at = authorized_at" + shift + ", captured_at = captured_at" + shift
                + " where order_id = ?", seconds, seconds, seconds, seconds, orderId);
        jdbc.update("update payment_events set created_at = created_at" + shift + " where payment_id in " + paymentIds,
                seconds, orderId);
        jdbc.update("update refunds set created_at = created_at" + shift + ", updated_at = updated_at" + shift
                + " where payment_id in " + paymentIds, seconds, seconds, orderId);
        // The append-only trigger on ledger_entries permits a created_at-only change when this
        // transaction-local flag is set; nothing outside demo seeding sets it.
        jdbc.queryForObject("select set_config('finflow.allow_ledger_backdating', 'on', true)", String.class);
        jdbc.update("update ledger_entries set created_at = created_at" + shift + " where payment_id in " + paymentIds,
                seconds, orderId);
    }
}
