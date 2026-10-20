package com.github.sup3rmar10.orders.repo;

import com.github.sup3rmar10.orders.domain.Order;
import com.github.sup3rmar10.orders.domain.OrderStatus;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

public final class OrderSpecs {

    private OrderSpecs() {
    }

    /** Both filters are optional; createdAfter is exclusive. */
    public static Specification<Order> filter(OrderStatus status, Instant createdAfter) {
        Specification<Order> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (createdAfter != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThan(root.get("createdAt"), createdAfter));
        }
        return spec;
    }
}
