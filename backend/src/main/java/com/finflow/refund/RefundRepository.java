package com.finflow.refund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    List<Refund> findByMerchantIdAndPaymentIdOrderByCreatedAtAsc(UUID merchantId, UUID paymentId);

    @Query("select coalesce(sum(r.amountMinor), 0) from Refund r "
            + "where r.paymentId = :paymentId and r.status = com.finflow.refund.RefundStatus.PENDING")
    long sumPendingAmount(UUID paymentId);
}
