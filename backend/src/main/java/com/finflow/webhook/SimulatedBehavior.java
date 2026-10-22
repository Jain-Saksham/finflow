package com.finflow.webhook;

/** How the built-in simulated receiver answers, to demonstrate retries and dead deliveries. */
public enum SimulatedBehavior {
    ALWAYS_SUCCEED,
    FAIL_FIRST_ATTEMPT,
    FAIL_FIRST_THREE,
    ALWAYS_FAIL;

    boolean succeedsOnAttempt(int attempt) {
        return switch (this) {
            case ALWAYS_SUCCEED -> true;
            case FAIL_FIRST_ATTEMPT -> attempt > 1;
            case FAIL_FIRST_THREE -> attempt > 3;
            case ALWAYS_FAIL -> false;
        };
    }
}
