package com.github.sup3rmar10.orders.web;

import com.github.sup3rmar10.orders.domain.OrderStatus;
import com.github.sup3rmar10.orders.service.OrderService;
import com.github.sup3rmar10.orders.service.OrderService.LineCommand;
import com.github.sup3rmar10.orders.service.OrderService.Placement;
import com.github.sup3rmar10.orders.web.Dtos.OrderResponse;
import com.github.sup3rmar10.orders.web.Dtos.PageResponse;
import com.github.sup3rmar10.orders.web.Dtos.PlaceOrderRequest;
import com.github.sup3rmar10.orders.web.Dtos.StatusChangeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        Placement placement = orders.place(idempotencyKey, request.customerRef(),
                request.lines().stream().map(l -> new LineCommand(l.sku(), l.quantity())).toList());
        OrderResponse body = OrderResponse.from(placement.order());
        if (placement.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/orders/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return OrderResponse.from(orders.get(id));
    }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(orders.list(status, createdAfter, page, size), OrderResponse::from);
    }

    @PostMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable long id, @Valid @RequestBody StatusChangeRequest request) {
        return OrderResponse.from(orders.transition(id, request.status()));
    }
}
