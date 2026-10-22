package com.finflow.provider;

import com.finflow.provider.PaymentProvider.AuthorizationResult.Approved;
import com.finflow.provider.PaymentProvider.AuthorizationResult.Declined;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for a real provider. It remembers authorization outcomes by payment id so
 * that authorization is idempotent and a timed-out payment can later be looked up, just as one
 * would query a real provider. State is lost on restart (documented limitation).
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private final SimulatorProperties properties;
    private final Map<UUID, AuthorizationResult> authorizations = new ConcurrentHashMap<>();

    public SimulatedPaymentProvider(SimulatorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "SIMULATOR";
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        simulateNetwork();
        AuthorizationResult previous = authorizations.get(request.paymentId());
        if (previous != null) {
            return previous;
        }
        SimulationScenario scenario = SimulationScenario.fromToken(request.paymentMethodToken())
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulator token"));
        log.info("Simulator authorize payment={} scenario={}", request.paymentId(), scenario);
        return switch (scenario.outcome()) {
            case APPROVE -> authorizations.computeIfAbsent(request.paymentId(), id -> approved());
            case DECLINE -> authorizations.computeIfAbsent(request.paymentId(),
                    id -> new Declined(scenario.declineCode(), scenario.label()));
            case TIMEOUT -> {
                // The provider does approve, but the caller never hears about it.
                authorizations.computeIfAbsent(request.paymentId(), id -> approved());
                sleep(properties.timeoutMs());
                throw new ProviderTimeoutException("Simulated provider did not respond within "
                        + properties.timeoutMs() + " ms");
            }
            case UNAVAILABLE -> throw new ProviderUnavailableException("Simulated provider is unavailable (HTTP 503)");
        };
    }

    @Override
    public void capture(String providerReference, long amountMinor) {
        simulateNetwork();
        log.info("Simulator capture ref={} amount={}", providerReference, amountMinor);
    }

    @Override
    public void voidAuthorization(String providerReference) {
        simulateNetwork();
        log.info("Simulator void ref={}", providerReference);
    }

    @Override
    public String refund(String providerReference, UUID refundId, long amountMinor) {
        simulateNetwork();
        log.info("Simulator refund ref={} refund={} amount={}", providerReference, refundId, amountMinor);
        // Derived from our refund id, so a retried refund maps to the same provider refund.
        return "re_sim_" + refundId.toString().replace("-", "").substring(0, 24);
    }

    @Override
    public Optional<AuthorizationResult> lookupAuthorization(UUID paymentId) {
        simulateNetwork();
        return Optional.ofNullable(authorizations.get(paymentId));
    }

    private static Approved approved() {
        return new Approved("pi_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
    }

    private void simulateNetwork() {
        sleep(properties.latencyMs());
        if (properties.outage()) {
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
