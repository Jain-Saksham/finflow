package com.finflow.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port to an external payment provider (acquirer/PSP). The payment module depends only on this
 * interface; {@link SimulatedPaymentProvider} is the sole implementation in FinFlow.
 *
 * <p>Every operation is idempotent on the identifiers passed in (payment id for authorization,
 * provider reference for capture/void, refund id for refunds), which is what makes it safe for
 * callers to retry after a timeout or a crash.
 *
 * @throws ProviderTimeoutException     outcome unknown: the provider may or may not have acted
 * @throws ProviderUnavailableException the request was not processed and can safely be retried
 */
public interface PaymentProvider {

    String name();

    AuthorizationResult authorize(AuthorizationRequest request);

    void capture(Operation operation);

    void voidAuthorization(Operation operation);

    /** @return the provider's refund reference */
    String refund(Operation operation, UUID refundId, long refundAmountMinor);

    /** Asks the provider what happened to an authorization, e.g. after a timeout. */
    Optional<AuthorizationResult> lookupAuthorization(UUID paymentId);

    /** The provider's own transaction report for a merchant: the input to reconciliation. */
    List<ProviderTransaction> transactions(UUID merchantId);

    record AuthorizationRequest(UUID merchantId, UUID paymentId, long amountMinor, String currency,
                                String paymentMethodToken) {
    }

    /** Identifies an authorization at the provider for follow-up operations. */
    record Operation(UUID merchantId, UUID paymentId, String providerReference, long amountMinor, String currency) {
    }

    sealed interface AuthorizationResult {
        record Approved(String providerReference) implements AuthorizationResult {
        }

        record Declined(String code, String message) implements AuthorizationResult {
        }
    }
}
