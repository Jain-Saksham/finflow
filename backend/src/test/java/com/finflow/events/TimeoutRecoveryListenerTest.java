package com.finflow.events;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.payment.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TimeoutRecoveryListenerTest {

    private final EventParser parser = new EventParser(JsonMapper.builder().build());
    private final PaymentService payments = mock(PaymentService.class);
    private final TimeoutRecoveryListener listener =
            new TimeoutRecoveryListener(parser, payments, new SimpleMeterRegistry(), Duration.ZERO);
    private final UUID merchant = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();

    private ConsumerRecord<String, String> event(String type) {
        var data = new PaymentEventMessage.Data(payment, UUID.randomUUID(), "TIMED_OUT", "CREATED", 100, "USD", 0, 0,
                null, "AUTOMATIC", null, "provider_timeout", "timeout", null);
        var message = new PaymentEventMessage(UUID.randomUUID(), type, "v", Instant.now(), merchant, null, data);
        return new ConsumerRecord<>("t", 0, 0, payment.toString(), parser.write(message));
    }

    @Test
    void ignoresOtherEventTypes() throws Exception {
        listener.onEvent(event("payment.captured"));
        verify(payments, never()).resolveFromProvider(any(), any(), any());
    }

    @Test
    void duplicateDeliveryOfAnAlreadyResolvedPaymentIsANoOp() {
        when(payments.resolveFromProvider(eq(merchant), eq(payment), any()))
                .thenThrow(new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "already CAPTURED"));
        assertThatCode(() -> listener.onEvent(event("payment.timed_out"))).doesNotThrowAnyException();
    }

    @Test
    void providerOutageIsRethrownSoTheErrorHandlerRetries() {
        when(payments.resolveFromProvider(eq(merchant), eq(payment), any()))
                .thenThrow(new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, "down"));
        assertThatThrownBy(() -> listener.onEvent(event("payment.timed_out"))).isInstanceOf(ApiException.class);
    }

    @Test
    void unparseablePayloadIsAPoisonMessage() {
        assertThatThrownBy(() -> listener.onEvent(new ConsumerRecord<>("t", 0, 0, "k", "{not json")))
                .isInstanceOf(PoisonMessageException.class);
    }
}
