package com.finflow.webhook;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);

    Optional<WebhookEndpoint> findByIdAndMerchantId(UUID id, UUID merchantId);

    long countByMerchantId(UUID merchantId);
}
