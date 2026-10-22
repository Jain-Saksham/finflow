package com.finflow.provider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Decorator recording {@code finflow.provider.latency} per operation and outcome. */
@Component
@Primary
public class MeteredPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final MeterRegistry registry;

    public MeteredPaymentProvider(SimulatedPaymentProvider delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        return timed("authorize", () -> delegate.authorize(request), r ->
                r instanceof AuthorizationResult.Approved ? "approved" : "declined");
    }

    @Override
    public void capture(Operation operation) {
        timed("capture", () -> {
            delegate.capture(operation);
            return null;
        }, r -> "ok");
    }

    @Override
    public void voidAuthorization(Operation operation) {
        timed("void", () -> {
            delegate.voidAuthorization(operation);
            return null;
        }, r -> "ok");
    }

    @Override
    public String refund(Operation operation, UUID refundId, long refundAmountMinor) {
        return timed("refund", () -> delegate.refund(operation, refundId, refundAmountMinor), r -> "ok");
    }

    @Override
    public Optional<AuthorizationResult> lookupAuthorization(UUID paymentId) {
        return timed("lookup", () -> delegate.lookupAuthorization(paymentId), r -> r.isPresent() ? "found" : "not_found");
    }

    @Override
    public List<ProviderTransaction> transactions(UUID merchantId) {
        return delegate.transactions(merchantId);
    }

    private <T> T timed(String operation, Supplier<T> call, java.util.function.Function<T, String> outcome) {
        Timer.Sample sample = Timer.start(registry);
        String result = "error";
        try {
            T value = call.get();
            result = outcome.apply(value);
            return value;
        } catch (ProviderTimeoutException e) {
            result = "timeout";
            throw e;
        } catch (ProviderUnavailableException e) {
            result = "unavailable";
            throw e;
        } finally {
            sample.stop(Timer.builder("finflow.provider.latency")
                    .description("Latency of calls to the payment provider")
                    .tag("operation", operation)
                    .tag("outcome", result)
                    .register(registry));
        }
    }
}
