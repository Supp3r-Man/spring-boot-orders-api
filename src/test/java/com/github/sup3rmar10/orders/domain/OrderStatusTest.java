package com.github.sup3rmar10.orders.domain;

import static com.github.sup3rmar10.orders.domain.OrderStatus.CANCELLED;
import static com.github.sup3rmar10.orders.domain.OrderStatus.NEW;
import static com.github.sup3rmar10.orders.domain.OrderStatus.PAID;
import static com.github.sup3rmar10.orders.domain.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sup3rmar10.orders.TestData;
import com.github.sup3rmar10.orders.error.ApiException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class OrderStatusTest {

    @Test
    void fullTransitionMatrixMatchesSpecification() {
        Map<OrderStatus, Set<OrderStatus>> legal = Map.of(
                NEW, Set.of(PAID, CANCELLED),
                PAID, Set.of(SHIPPED, CANCELLED),
                SHIPPED, Set.of(),
                CANCELLED, Set.of());
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to)
                        .isEqualTo(legal.get(from).contains(to));
            }
        }
    }

    @Test
    void happyPathNewPaidShipped() {
        Order order = TestData.order("k1", TestData.T0);
        assertThat(order.getStatus()).isEqualTo(NEW);
        order.transitionTo(PAID);
        order.transitionTo(SHIPPED);
        assertThat(order.getStatus()).isEqualTo(SHIPPED);
    }

    @Test
    void cannotSkipPayment() {
        Order order = TestData.order("k1", TestData.T0);
        assertThatThrownBy(() -> order.transitionTo(SHIPPED))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("illegal-transition");
                });
        assertThat(order.getStatus()).isEqualTo(NEW);
    }

    @Test
    void selfTransitionIsRejected() {
        Order order = TestData.order("k1", TestData.T0);
        assertThatThrownBy(() -> order.transitionTo(NEW)).isInstanceOf(ApiException.class);
    }

    @Test
    void paidOrderCanStillBeCancelled() {
        Order order = TestData.order("k1", TestData.T0);
        order.transitionTo(PAID);
        order.transitionTo(CANCELLED);
        assertThat(order.getStatus()).isEqualTo(CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SHIPPED", "CANCELLED"})
    void terminalStatesAcceptNothing(OrderStatus terminal) {
        Order order = TestData.order("k1", TestData.T0);
        order.transitionTo(terminal == SHIPPED ? PAID : CANCELLED);
        if (terminal == SHIPPED) {
            order.transitionTo(SHIPPED);
        }
        for (OrderStatus target : OrderStatus.values()) {
            assertThatThrownBy(() -> order.transitionTo(target)).isInstanceOf(ApiException.class);
        }
        assertThat(order.getStatus()).isEqualTo(terminal);
    }
}
