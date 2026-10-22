package com.finflow.payment;

import static com.finflow.payment.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    /** The full expected transition table; any change to the state machine must update this test. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            CREATED, EnumSet.of(AUTHORIZED, DECLINED, FAILED, TIMED_OUT),
            TIMED_OUT, EnumSet.of(AUTHORIZED, DECLINED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, VOIDED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class),
            DECLINED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            VOIDED, EnumSet.noneOf(PaymentStatus.class));

    @Test
    void transitionTableMatchesSpecificationExactly() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void terminalStatusesAcceptNoTransitions() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream().filter(PaymentStatus::isTerminal))
                .containsExactlyInAnyOrder(REFUNDED, DECLINED, FAILED, VOIDED);
    }

    @Test
    void onlyCapturedPaymentsAreRefundable() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream().filter(PaymentStatus::isRefundable))
                .containsExactlyInAnyOrder(CAPTURED, PARTIALLY_REFUNDED);
    }

    @Test
    void activeStatusesAreThoseThatMayHoldOrMoveMoney() {
        assertThat(PaymentStatus.ACTIVE)
                .containsExactlyInAnyOrder(CREATED, AUTHORIZED, TIMED_OUT, CAPTURED, PARTIALLY_REFUNDED, REFUNDED)
                .doesNotContain(DECLINED, FAILED, VOIDED);
    }
}
