package com.finflow.payment;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** SELECT ... FOR UPDATE: serializes state changes to one payment (capture vs refund vs void). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.merchantId = :merchantId")
    Optional<Payment> findForUpdate(UUID id, UUID merchantId);

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);
}
