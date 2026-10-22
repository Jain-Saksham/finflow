package com.finflow.provider;

import com.finflow.provider.PaymentProvider.AuthorizationResult.Approved;
import com.finflow.provider.PaymentProvider.AuthorizationResult.Declined;
import com.finflow.provider.ProviderTransaction.Status;
import com.finflow.provider.ProviderTransaction.Type;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real provider. It keeps its own durable transaction records (see
 * {@link ProviderRecords}) keyed so that every operation is idempotent, and can be looked up after a
 * timeout or listed for reconciliation, just like a real provider's API and settlement reports.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private final SimulatorSettings settings;
    private final ProviderRecords records;

    public SimulatedPaymentProvider(SimulatorSettings settings, ProviderRecords records) {
        this.settings = settings;
        this.records = records;
    }

    @Override
    public String name() {
        return "SIMULATOR";
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        simulateNetwork();
        Optional<ProviderTransaction> previous = records.authorization(request.paymentId());
        if (previous.isPresent()) {
            return toResult(previous.get());
        }
        SimulationScenario scenario = SimulationScenario.fromToken(request.paymentMethodToken())
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulator token"));
        log.info("Simulator authorize payment={} scenario={}", request.paymentId(), scenario);
        return switch (scenario.outcome()) {
            case APPROVE -> toResult(recordAuthorization(request, Status.SUCCEEDED, null));
            case DECLINE -> toResult(recordAuthorization(request, Status.DECLINED, scenario.declineCode()));
            case TIMEOUT -> {
                // The provider does approve, but the caller never hears about it.
                recordAuthorization(request, Status.SUCCEEDED, null);
                sleep(settings.timeoutMs());
                throw new ProviderTimeoutException("Simulated provider did not respond within "
                        + settings.timeoutMs() + " ms");
            }
            case UNAVAILABLE -> throw new ProviderUnavailableException("Simulated provider is unavailable (HTTP 503)");
        };
    }

    @Override
    public void capture(Operation op) {
        simulateNetwork();
        records.record(Type.CAPTURE, op.providerReference(), Status.SUCCEEDED, op.merchantId(), op.paymentId(), null,
                op.providerReference(), op.amountMinor(), op.currency(), null, null);
    }

    @Override
    public void voidAuthorization(Operation op) {
        simulateNetwork();
        records.record(Type.VOID, op.providerReference(), Status.SUCCEEDED, op.merchantId(), op.paymentId(), null,
                op.providerReference(), op.amountMinor(), op.currency(), null, null);
    }

    @Override
    public String refund(Operation op, UUID refundId, long refundAmountMinor) {
        simulateNetwork();
        // Keyed by FinFlow's refund id, so a retried refund maps to the same provider refund.
        String reference = "re_sim_" + refundId.toString().replace("-", "").substring(0, 24);
        return records.record(Type.REFUND, refundId.toString(), Status.SUCCEEDED, op.merchantId(), op.paymentId(),
                refundId, reference, refundAmountMinor, op.currency(), null, null).providerReference();
    }

    @Override
    public Optional<AuthorizationResult> lookupAuthorization(UUID paymentId) {
        simulateNetwork();
        return records.authorization(paymentId).map(SimulatedPaymentProvider::toResult);
    }

    @Override
    public List<ProviderTransaction> transactions(UUID merchantId) {
        return records.forMerchant(merchantId);
    }

    private ProviderTransaction recordAuthorization(AuthorizationRequest request, Status status, String declineCode) {
        String reference = "pi_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return records.record(Type.AUTHORIZATION, request.paymentId().toString(), status, request.merchantId(),
                request.paymentId(), null, reference, request.amountMinor(), request.currency(), declineCode, null);
    }

    private static AuthorizationResult toResult(ProviderTransaction tx) {
        return tx.status() == Status.SUCCEEDED
                ? new Approved(tx.providerReference())
                : new Declined(tx.declineCode(), SimulationScenario.fromToken("tok_decline_" + tx.declineCode())
                        .map(SimulationScenario::label).orElse("Declined"));
    }

    private void simulateNetwork() {
        sleep(settings.latencyMs());
        if (settings.outage()) {
            throw new ProviderUnavailableException("Simulated provider outage is enabled");
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTimeoutException("Interrupted while waiting for the provider");
        }
    }
}
