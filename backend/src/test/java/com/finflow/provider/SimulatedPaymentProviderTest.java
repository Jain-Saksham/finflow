package com.finflow.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finflow.provider.PaymentProvider.AuthorizationRequest;
import com.finflow.provider.PaymentProvider.AuthorizationResult.Approved;
import com.finflow.provider.PaymentProvider.AuthorizationResult.Declined;
import com.finflow.provider.ProviderTransaction.Status;
import com.finflow.provider.ProviderTransaction.Type;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Decision logic of the simulator with its storage mocked (storage itself is covered by integration tests). */
class SimulatedPaymentProviderTest {

    private ProviderRecords records;
    private SimulatorSettings settings;
    private SimulatedPaymentProvider provider;
    private final UUID merchant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        records = mock(ProviderRecords.class);
        settings = new SimulatorSettings(new SimulatorProperties(0, 0, false));
        provider = new SimulatedPaymentProvider(settings, records);
        when(records.authorization(any())).thenReturn(Optional.empty());
        when(records.record(any(), anyString(), any(), any(), any(), any(), anyString(), anyLong(), anyString(), any(), any()))
                .thenAnswer(inv -> new ProviderTransaction(1, inv.getArgument(0), inv.getArgument(2), merchant,
                        inv.getArgument(4), inv.getArgument(5), inv.getArgument(6), inv.getArgument(7),
                        inv.getArgument(8), inv.getArgument(9), null, Instant.now()));
    }

    private AuthorizationRequest request(UUID id, String token) {
        return new AuthorizationRequest(merchant, id, 10_00, "USD", token);
    }

    @Test
    void approvesAndRecordsTheAuthorization() {
        UUID id = UUID.randomUUID();
        assertThat(provider.authorize(request(id, "tok_success"))).isInstanceOf(Approved.class);
        verify(records).record(eq(Type.AUTHORIZATION), eq(id.toString()), eq(Status.SUCCEEDED), eq(merchant), eq(id),
                any(), anyString(), eq(10_00L), eq("USD"), any(), any());
    }

    @Test
    void repeatedAuthorizationReturnsTheStoredResultWithoutRecordingAgain() {
        UUID id = UUID.randomUUID();
        when(records.authorization(id)).thenReturn(Optional.of(new ProviderTransaction(1, Type.AUTHORIZATION,
                Status.SUCCEEDED, merchant, id, null, "pi_sim_existing", 10_00, "USD", null, null, Instant.now())));

        assertThat(provider.authorize(request(id, "tok_decline_do_not_honor"))).isEqualTo(new Approved("pi_sim_existing"));
        verify(records, never()).record(any(), anyString(), any(), any(), any(), any(), anyString(), anyLong(),
                anyString(), any(), any());
    }

    @Test
    void declineTokensReturnTheirDeclineCode() {
        assertThat(provider.authorize(request(UUID.randomUUID(), "tok_decline_insufficient_funds")))
                .isEqualTo(new Declined("insufficient_funds", "Declined: insufficient funds"));
    }

    @Test
    void timeoutRecordsTheApprovalBeforeFailing() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> provider.authorize(request(id, "tok_timeout"))).isInstanceOf(ProviderTimeoutException.class);
        verify(records).record(eq(Type.AUTHORIZATION), eq(id.toString()), eq(Status.SUCCEEDED), any(), any(), any(),
                anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void unavailableRecordsNothing() {
        assertThatThrownBy(() -> provider.authorize(request(UUID.randomUUID(), "tok_provider_unavailable")))
                .isInstanceOf(ProviderUnavailableException.class);
        verify(records, never()).record(any(), anyString(), any(), any(), any(), any(), anyString(), anyLong(),
                anyString(), any(), any());
    }

    @Test
    void outageFailsEveryCallBeforeTouchingStorage() {
        settings.setOutage(true);
        records = mock(ProviderRecords.class);
        provider = new SimulatedPaymentProvider(settings, records);
        assertThatThrownBy(() -> provider.authorize(request(UUID.randomUUID(), "tok_success")))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThatThrownBy(() -> provider.capture(new PaymentProvider.Operation(merchant, UUID.randomUUID(), "pi", 1, "USD")))
                .isInstanceOf(ProviderUnavailableException.class);
        verifyNoInteractions(records);
    }
}
