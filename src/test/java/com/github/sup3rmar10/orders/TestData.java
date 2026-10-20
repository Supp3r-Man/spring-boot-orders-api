package com.github.sup3rmar10.orders;

import com.github.sup3rmar10.orders.domain.Order;
import com.github.sup3rmar10.orders.domain.OrderLine;
import java.time.Instant;
import java.util.List;

public final class TestData {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private TestData() {
    }

    public static Order order(String idempotencyKey, Instant createdAt) {
        return new Order("cust-1", createdAt, 1000, 0, 0, 1000, idempotencyKey, "hash-" + idempotencyKey,
                List.of(new OrderLine("SKU-1", 500, 2, 1000)));
    }
}
