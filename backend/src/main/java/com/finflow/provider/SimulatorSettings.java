package com.finflow.provider;

import org.springframework.stereotype.Component;

/**
 * Runtime view of the simulator configuration. Values start from {@link SimulatorProperties};
 * tests adjust them to provoke slow or failing providers without restarting the application.
 * Deliberately not exposed through any HTTP endpoint.
 */
@Component
public class SimulatorSettings {

    private final SimulatorProperties defaults;
    private volatile long latencyMs;
    private volatile long timeoutMs;
    private volatile boolean outage;

    public SimulatorSettings(SimulatorProperties defaults) {
        this.defaults = defaults;
        reset();
    }

    public final void reset() {
        latencyMs = defaults.latencyMs();
        timeoutMs = defaults.timeoutMs();
        outage = defaults.outage();
    }

    public long latencyMs() {
        return latencyMs;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public boolean outage() {
        return outage;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setOutage(boolean outage) {
        this.outage = outage;
    }
}
