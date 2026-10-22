package com.finflow.webhook;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A pretend merchant server that receives FinFlow webhooks, so retries and receiver-side
 * deduplication can be demonstrated without sending traffic to the internet. It does what a real
 * receiver should: verify the signature and timestamp, then process each event id at most once
 * and acknowledge duplicates with 200 so the sender stops retrying.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/simulator/webhook-receiver")
public class SimulatedReceiverController {

    private final WebhookEndpointRepository endpoints;
    private final WebhookProperties properties;
    private final JdbcTemplate jdbc;

    public SimulatedReceiverController(WebhookEndpointRepository endpoints, WebhookProperties properties,
                                       JdbcTemplate jdbc) {
        this.endpoints = endpoints;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @PostMapping("/{endpointId}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable UUID endpointId,
                                                       @RequestHeader(value = WebhookSigner.HEADER, required = false) String signature,
                                                       @RequestHeader(value = "FinFlow-Event-Id", required = false) UUID eventId,
                                                       @RequestBody String body) {
        WebhookEndpoint endpoint = endpoints.findById(endpointId).orElse(null);
        if (endpoint == null || endpoint.getSimulatedBehavior() == null) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown receiver"));
        }
        if (eventId == null || !WebhookSigner.verify(endpoint.getSecret(), signature, body, Instant.now(),
                properties.signatureTolerance())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }
        Map<String, Object> receipt = jdbc.queryForMap("""
                insert into webhook_receipts (endpoint_id, event_id, attempts, first_received_at)
                values (?, ?, 1, now())
                on conflict (endpoint_id, event_id) do update set attempts = webhook_receipts.attempts + 1
                returning attempts, processed_at""", endpointId, eventId);
        int attempts = ((Number) receipt.get("attempts")).intValue();
        if (receipt.get("processed_at") != null) {
            jdbc.update("update webhook_receipts set duplicate_deliveries = duplicate_deliveries + 1 "
                    + "where endpoint_id = ? and event_id = ?", endpointId, eventId);
            return ResponseEntity.ok(Map.of("status", "duplicate_ignored", "eventId", eventId));
        }
        if (!endpoint.getSimulatedBehavior().succeedsOnAttempt(attempts)) {
            return ResponseEntity.status(503).body(Map.of("error", "simulated receiver failure",
                    "behavior", endpoint.getSimulatedBehavior(), "attempt", attempts));
        }
        jdbc.update("update webhook_receipts set processed_at = now() where endpoint_id = ? and event_id = ?",
                endpointId, eventId);
        return ResponseEntity.ok(Map.of("status", "processed", "eventId", eventId));
    }
}
