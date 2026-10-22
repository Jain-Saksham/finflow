package com.finflow.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope written to the outbox, published to Kafka and POSTed to webhook endpoints.
 * {@link #id()} is unique per event and is the deduplication key for every consumer.
 */
public record PaymentEventMessage(UUID id, String type, String apiVersion, Instant createdAt, UUID merchantId,
                                  String correlationId, Data data) {

    public static final String API_VERSION = "2026-10-01";

    public record Data(UUID paymentId, UUID orderId, String status, String previousStatus, long amountMinor,
                       String currency, long capturedAmountMinor, long refundedAmountMinor, Long eventAmountMinor,
                       String captureMode, String providerReference, String failureCode, String failureMessage,
                       String detail) {
    }
}
