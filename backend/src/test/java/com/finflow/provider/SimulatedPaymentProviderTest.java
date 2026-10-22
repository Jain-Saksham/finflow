package com.finflow.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.provider.PaymentProvider.AuthorizationRequest;
import com.finflow.provider.PaymentProvider.AuthorizationResult.Approved;
import com.finflow.provider.PaymentProvider.AuthorizationResult.Declined;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatedPaymentProviderTest {

    private final SimulatedPaymentProvider provider = new SimulatedPaymentProvider(new SimulatorProperties(0, 0, false));

    private static AuthorizationRequest request(UUID id, String token) {
        return new AuthorizationRequest(id, 10_00, "USD", token);
    }

    @Test
    void successTokenApprovesAndIsIdempotentPerPayment() {
        UUID id = UUID.randomUUID();
        var first = provider.authorize(request(id, "tok_success"));
        var second = provider.authorize(request(id, "tok_success"));
        assertThat(first).isInstanceOf(Approved.class).isEqualTo(second);
    }

    @Test
    void declineTokensReturnTheirDeclineCode() {
        assertThat(provider.authorize(request(UUID.randomUUID(), "tok_decline_insufficient_funds")))
                .isEqualTo(new Declined("insufficient_funds", "Declined: insufficient funds"));
        assertThat(provider.authorize(request(UUID.randomUUID(), "tok_decline_expired_card")))
                .isInstanceOf(Declined.class);
    }

    @Test
    void timeoutThrowsButTheProviderStillRecordsTheApproval() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> provider.authorize(request(id, "tok_timeout")))
                .isInstanceOf(ProviderTimeoutException.class);
        assertThat(provider.lookupAuthorization(id)).get().isInstanceOf(Approved.class);
    }

    @Test
    void unavailableThrowsAndRecordsNothing() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> provider.authorize(request(id, "tok_provider_unavailable")))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(provider.lookupAuthorization(id)).isEmpty();
    }

    @Test
    void outageModeFailsEveryCall() {
        var down = new SimulatedPaymentProvider(new SimulatorProperties(0, 0, true));
        assertThatThrownBy(() -> down.authorize(request(UUID.randomUUID(), "tok_success")))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThatThrownBy(() -> down.capture("pi_sim_x", 1)).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void refundReferenceIsDeterministicForRetries() {
        UUID refundId = UUID.randomUUID();
        assertThat(provider.refund("pi_sim_x", refundId, 1)).isEqualTo(provider.refund("pi_sim_x", refundId, 1));
    }
}
