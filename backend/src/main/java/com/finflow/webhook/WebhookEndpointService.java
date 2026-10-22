package com.finflow.webhook;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointService {

    public static final String RECEIVER_PATH = "/api/v1/simulator/webhook-receiver/";

    private final WebhookEndpointRepository endpoints;
    private final WebhookProperties properties;
    private final Environment environment;

    public WebhookEndpointService(WebhookEndpointRepository endpoints, WebhookProperties properties,
                                  Environment environment) {
        this.endpoints = endpoints;
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * Without a URL the endpoint targets FinFlow's built-in simulated receiver. External URLs must
     * match an operator-configured prefix allowlist (empty by default): a public demo must not be
     * usable to make the server send requests to arbitrary hosts (SSRF).
     */
    @Transactional
    public WebhookEndpoint create(UUID merchantId, String url, SimulatedBehavior behavior, String description) {
        if (endpoints.countByMerchantId(merchantId) >= properties.maxEndpointsPerMerchant()) {
            throw new ApiException(ErrorCode.WEBHOOK_ENDPOINT_LIMIT,
                    "At most " + properties.maxEndpointsPerMerchant() + " webhook endpoints are allowed");
        }
        UUID id = UUID.randomUUID();
        String target;
        SimulatedBehavior simulated = null;
        if (url == null || url.isBlank()) {
            simulated = behavior == null ? SimulatedBehavior.FAIL_FIRST_ATTEMPT : behavior;
            target = receiverBaseUrl() + RECEIVER_PATH + id;
        } else {
            target = url.strip();
            boolean allowed = properties.allowedUrlPrefixes() != null && properties.allowedUrlPrefixes().stream()
                    .anyMatch(prefix -> !prefix.isBlank() && target.startsWith(prefix.strip()));
            if (!allowed) {
                throw new ApiException(ErrorCode.WEBHOOK_URL_NOT_ALLOWED, "External webhook URLs are disabled on this "
                        + "deployment. Omit the URL to use the built-in simulated receiver.");
            }
        }
        return endpoints.save(new WebhookEndpoint(id, merchantId, target, WebhookSigner.newSecret(),
                description == null || description.isBlank() ? null : description.strip(), simulated));
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(UUID merchantId) {
        return endpoints.findByMerchantIdOrderByCreatedAtAsc(merchantId);
    }

    @Transactional
    public WebhookEndpoint setEnabled(UUID merchantId, UUID endpointId, boolean enabled) {
        WebhookEndpoint endpoint = endpoints.findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Webhook endpoint", endpointId));
        endpoint.setEnabled(enabled);
        return endpoint;
    }

    private String receiverBaseUrl() {
        String configured = properties.internalReceiverBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.replaceAll("/+$", "");
        }
        // local.server.port is the actual port (also when tests use a random one).
        return "http://localhost:" + environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
    }
}
