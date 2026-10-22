package com.finflow.provider;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FinFlow must never be wired to a real payment provider by accident. Startup fails unless every
 * PaymentProvider bean is the simulator (or the metering decorator around it).
 */
@Component
public class SimulationGuard {

    private static final Logger log = LoggerFactory.getLogger(SimulationGuard.class);

    private final List<PaymentProvider> providers;

    public SimulationGuard(List<PaymentProvider> providers) {
        this.providers = providers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        for (PaymentProvider provider : providers) {
            if (!(provider instanceof SimulatedPaymentProvider) && !(provider instanceof MeteredPaymentProvider)) {
                throw new IllegalStateException("Non-simulated payment provider " + provider.getClass().getName()
                        + " is not allowed: FinFlow is a simulation and must never move real money");
            }
        }
        log.info("SIMULATION MODE: payments use the simulated provider only; no real money can move");
    }
}
