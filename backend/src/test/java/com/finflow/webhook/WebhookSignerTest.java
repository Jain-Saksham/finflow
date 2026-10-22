package com.finflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.captured\"}";
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void signatureRoundTrips() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        String header = WebhookSigner.sign(SECRET, now.getEpochSecond(), BODY);
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(WebhookSigner.verify(SECRET, header, BODY, now, TOLERANCE)).isTrue();
    }

    @Test
    void tamperedBodyWrongSecretOrMissingHeaderFail() {
        Instant now = Instant.now();
        String header = WebhookSigner.sign(SECRET, now.getEpochSecond(), BODY);
        assertThat(WebhookSigner.verify(SECRET, header, BODY.replace("captured", "refunded"), now, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify("whsec_other", header, BODY, now, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, null, BODY, now, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "garbage", BODY, now, TOLERANCE)).isFalse();
    }

    @Test
    void oldSignaturesAreRejectedToPreventReplay() {
        Instant signedAt = Instant.parse("2026-10-01T12:00:00Z");
        String header = WebhookSigner.sign(SECRET, signedAt.getEpochSecond(), BODY);
        assertThat(WebhookSigner.verify(SECRET, header, BODY, signedAt.plus(Duration.ofMinutes(6)), TOLERANCE)).isFalse();
    }

    @Test
    void simulatedBehaviorsFollowTheirAttemptRules() {
        assertThat(SimulatedBehavior.FAIL_FIRST_ATTEMPT.succeedsOnAttempt(1)).isFalse();
        assertThat(SimulatedBehavior.FAIL_FIRST_ATTEMPT.succeedsOnAttempt(2)).isTrue();
        assertThat(SimulatedBehavior.FAIL_FIRST_THREE.succeedsOnAttempt(3)).isFalse();
        assertThat(SimulatedBehavior.FAIL_FIRST_THREE.succeedsOnAttempt(4)).isTrue();
        assertThat(SimulatedBehavior.ALWAYS_FAIL.succeedsOnAttempt(100)).isFalse();
    }
}
