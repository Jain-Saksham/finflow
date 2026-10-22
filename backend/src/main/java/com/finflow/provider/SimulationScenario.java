package com.finflow.provider;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fake payment-method tokens that select a simulated outcome, in the spirit of Stripe's test
 * cards. FinFlow never accepts card numbers.
 */
public enum SimulationScenario {
    SUCCESS("tok_success", Outcome.APPROVE, null,
            "Approved", "The provider approves the authorization."),
    DECLINE_INSUFFICIENT_FUNDS("tok_decline_insufficient_funds", Outcome.DECLINE, "insufficient_funds",
            "Declined: insufficient funds", "The issuer declines because the account lacks funds."),
    DECLINE_DO_NOT_HONOR("tok_decline_do_not_honor", Outcome.DECLINE, "do_not_honor",
            "Declined: do not honor", "Generic issuer decline with no further reason."),
    DECLINE_EXPIRED_CARD("tok_decline_expired_card", Outcome.DECLINE, "expired_card",
            "Declined: expired card", "The payment method has expired."),
    TIMEOUT("tok_timeout", Outcome.TIMEOUT, null,
            "Provider timeout", "The provider approves but the response is lost. The payment ends up TIMED_OUT "
            + "until it is synced with the provider (automatically after a few seconds, or manually)."),
    PROVIDER_UNAVAILABLE("tok_provider_unavailable", Outcome.UNAVAILABLE, null,
            "Provider unavailable", "The provider refuses the connection. Nothing is charged; a new attempt is safe."),
    CRASH_AFTER_PROVIDER_APPROVAL("tok_crash_after_provider_approval", Outcome.APPROVE, null,
            "FinFlow crash after approval", "Fault injection: the provider approves, then FinFlow fails before "
            + "saving the result. The payment stays CREATED until reconciliation recovers it.");

    public enum Outcome { APPROVE, DECLINE, TIMEOUT, UNAVAILABLE }

    private final String token;
    private final Outcome outcome;
    private final String declineCode;
    private final String label;
    private final String description;

    SimulationScenario(String token, Outcome outcome, String declineCode, String label, String description) {
        this.token = token;
        this.outcome = outcome;
        this.declineCode = declineCode;
        this.label = label;
        this.description = description;
    }

    public static Optional<SimulationScenario> fromToken(String token) {
        return Arrays.stream(values()).filter(s -> s.token.equals(token)).findFirst();
    }

    /** True for the demo fault-injection token that makes FinFlow fail after the provider call. */
    public boolean crashesBeforeLocalCommit() {
        return this == CRASH_AFTER_PROVIDER_APPROVAL;
    }

    public String token() {
        return token;
    }

    public Outcome outcome() {
        return outcome;
    }

    public String declineCode() {
        return declineCode;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
