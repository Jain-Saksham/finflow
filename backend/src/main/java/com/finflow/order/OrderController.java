package com.finflow.order;

import com.finflow.common.web.PageResponse;
import com.finflow.common.web.Paging;
import com.finflow.order.OrderDtos.CreateOrderRequest;
import com.finflow.order.OrderDtos.OrderResponse;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create an order to be paid")
    public ResponseEntity<OrderResponse> create(@AuthenticationPrincipal MerchantPrincipal merchant,
                                                @Valid @RequestBody CreateOrderRequest request) {
        MerchantOrder order = orderService.create(merchant.merchantId(), request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.getId())).body(OrderResponse.from(order));
    }

    @GetMapping
    @Operation(summary = "List orders, newest first")
    public PageResponse<OrderResponse> list(@AuthenticationPrincipal MerchantPrincipal merchant,
                                            @RequestParam(required = false) OrderStatus status,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = Paging.DEFAULT_SIZE) @Min(1) @Max(Paging.MAX_SIZE) int size) {
        return PageResponse.of(orderService.list(merchant.merchantId(), status, Paging.newestFirst(page, size)),
                OrderResponse::from);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order")
    public OrderResponse get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID orderId) {
        return OrderResponse.from(orderService.get(merchant.merchantId(), orderId));
    }
}
