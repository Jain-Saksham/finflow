package com.finflow.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    List<PaymentEvent> findByPaymentIdOrderByIdAsc(UUID paymentId);
}
