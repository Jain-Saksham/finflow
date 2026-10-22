package com.finflow.order;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.order.OrderDtos.CreateOrderRequest;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional
    public MerchantOrder create(UUID merchantId, CreateOrderRequest request) {
        String reference = request.merchantReference().strip();
        if (orders.existsByMerchantIdAndMerchantReference(merchantId, reference)) {
            throw duplicate(reference);
        }
        var order = new MerchantOrder(merchantId, reference, request.amountMinor(), request.currency(),
                blankToNull(request.description()));
        try {
            return orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request using the same reference; the unique constraint wins.
            throw duplicate(reference);
        }
    }

    @Transactional(readOnly = true)
    public MerchantOrder get(UUID merchantId, UUID orderId) {
        return orders.findByIdAndMerchantId(orderId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Order", orderId));
    }

    @Transactional(readOnly = true)
    public Page<MerchantOrder> list(UUID merchantId, OrderStatus status, Pageable pageable) {
        return status == null
                ? orders.findByMerchantId(merchantId, pageable)
                : orders.findByMerchantIdAndStatus(merchantId, status, pageable);
    }

    private static ApiException duplicate(String reference) {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                "An order with merchantReference '" + reference + "' already exists");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
