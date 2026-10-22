package com.finflow.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** All lookups take a merchantId: there is deliberately no unscoped finder exposed to controllers. */
public interface OrderRepository extends JpaRepository<MerchantOrder, UUID> {

    Optional<MerchantOrder> findByIdAndMerchantId(UUID id, UUID merchantId);

    Page<MerchantOrder> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<MerchantOrder> findByMerchantIdAndStatus(UUID merchantId, OrderStatus status, Pageable pageable);

    boolean existsByMerchantIdAndMerchantReference(UUID merchantId, String merchantReference);

    long countByMerchantId(UUID merchantId);
}
