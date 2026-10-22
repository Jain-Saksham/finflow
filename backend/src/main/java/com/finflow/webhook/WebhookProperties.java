package com.finflow.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param retrySchedule delay before retry N (1-based); attempts = schedule size + 1, then FAILED
 * @param lease         how long a worker owns a claimed delivery; an expired lease (crashed worker)
 *                      makes the delivery claimable again
 */
@ConfigurationProperties("finflow.webhooks")
public record WebhookProperties(boolean workerEnabled, Duration pollInterval, int batchSize, Duration lease,
                                Duration requestTimeout, List<Duration> retrySchedule, int maxEndpointsPerMerchant,
                                Duration signatureTolerance, String internalReceiverBaseUrl,
                                List<String> allowedUrlPrefixes) {

    public int maxAttempts() {
        return retrySchedule.size() + 1;
    }
}
