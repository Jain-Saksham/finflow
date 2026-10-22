package com.finflow.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finflow.reconciliation")
public record ReconciliationProperties(boolean scheduleEnabled, Duration interval, Duration staleAfter) {
}
