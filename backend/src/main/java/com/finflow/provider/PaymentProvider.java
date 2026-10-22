package com.finflow.provider;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to an external payment provider (acquirer/PSP). The payment module only depends on this
 * interface; {@link SimulatedPaymentProvider} is the sole implementation in FinFlow.
 *
 * <p>Every operation is idempotent on the identifiers passed in, which is what makes it safe for
 * callers to retry after a timeout.
 *
 * @throws ProviderTimeoutException     outcome unknown: the provider may or may not have acted
 * @throws ProviderUnavailableException the request was not processed and can safely be retried
 */
public interface PaymentProvider {

    String name();

    AuthorizationResult authorize(AuthorizationRequest request);

    void capture(String providerReference, long amountMinor);

    void voidAuthorization(String providerReference);

    /** @return the provider's refund reference */
    String refund(String providerReference, UUID refundId, long amountMinor);

    /** Asks the provider what happened to an authorization, e.g. after a timeout. */
    Optional<AuthorizationResult> lookupAuthorization(UUID paymentId);

    record AuthorizationRequest(UUID paymentId, long amountMinor, String currency, String paymentMethodToken) {
    }

    sealed interface AuthorizationResult {
        record Approved(String providerReference) implements AuthorizationResult {
        }

        record Declined(String code, String message) implements AuthorizationResult {
        }
    }
}
