package com.github.sup3rmar10.orders.domain;

import java.util.Set;

/**
 * Order lifecycle. NEW -> PAID -> SHIPPED, and CANCELLED from NEW or PAID.
 * SHIPPED and CANCELLED are terminal.
 */
public enum OrderStatus {
    NEW, PAID, SHIPPED, CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case NEW -> Set.of(PAID, CANCELLED);
            case PAID -> Set.of(SHIPPED, CANCELLED);
            case SHIPPED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedNext().contains(target);
    }
}
