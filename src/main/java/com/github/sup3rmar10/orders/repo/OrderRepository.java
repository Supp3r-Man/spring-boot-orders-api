package com.github.sup3rmar10.orders.repo;

import com.github.sup3rmar10.orders.domain.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
