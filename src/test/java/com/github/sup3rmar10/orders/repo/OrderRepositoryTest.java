package com.github.sup3rmar10.orders.repo;

import static com.github.sup3rmar10.orders.TestData.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sup3rmar10.orders.TestData;
import com.github.sup3rmar10.orders.domain.Order;
import com.github.sup3rmar10.orders.domain.OrderStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    OrderRepository orders;

    @Autowired
    TestEntityManager em;

    Order newOrder;
    Order paidOrder;
    Order shippedOrder;

    @BeforeEach
    void seed() {
        newOrder = orders.save(TestData.order("k-new", T0));
        paidOrder = TestData.order("k-paid", T0.plus(Duration.ofHours(1)));
        paidOrder.transitionTo(OrderStatus.PAID);
        paidOrder = orders.save(paidOrder);
        shippedOrder = TestData.order("k-shipped", T0.plus(Duration.ofHours(2)));
        shippedOrder.transitionTo(OrderStatus.PAID);
        shippedOrder.transitionTo(OrderStatus.SHIPPED);
        shippedOrder = orders.saveAndFlush(shippedOrder);
    }

    private List<Long> ids(OrderStatus status, java.time.Instant after) {
        return orders.findAll(OrderSpecs.filter(status, after)).stream().map(Order::getId).toList();
    }

    @Test
    void noFiltersReturnsEverything() {
        assertThat(ids(null, null)).containsExactlyInAnyOrder(newOrder.getId(), paidOrder.getId(), shippedOrder.getId());
    }

    @Test
    void filtersByStatus() {
        assertThat(ids(OrderStatus.PAID, null)).containsExactly(paidOrder.getId());
        assertThat(ids(OrderStatus.CANCELLED, null)).isEmpty();
    }

    @Test
    void createdAfterIsExclusive() {
        assertThat(ids(null, T0.plus(Duration.ofHours(1)))).containsExactly(shippedOrder.getId());
        assertThat(ids(null, T0.plus(Duration.ofHours(1)).minusNanos(1)))
                .containsExactlyInAnyOrder(paidOrder.getId(), shippedOrder.getId());
    }

    @Test
    void combinesFiltersWithAnd() {
        assertThat(ids(OrderStatus.NEW, T0.plus(Duration.ofMinutes(30)))).isEmpty();
        assertThat(ids(OrderStatus.SHIPPED, T0.plus(Duration.ofMinutes(30)))).containsExactly(shippedOrder.getId());
    }

    @Test
    void persistsLinesWithTheOrder() {
        Order reloaded = orders.findById(newOrder.getId()).orElseThrow();
        assertThat(reloaded.getLines()).hasSize(1);
        assertThat(reloaded.getLines().get(0).getLineTotalMinor()).isEqualTo(1000);
    }

    @Test
    void looksUpByIdempotencyKey() {
        assertThat(orders.findByIdempotencyKey("k-paid")).get().extracting(Order::getId).isEqualTo(paidOrder.getId());
        assertThat(orders.findByIdempotencyKey("nope")).isEmpty();
    }

    @Test
    void idempotencyKeyIsUniqueInTheDatabase() {
        assertThatThrownBy(() -> orders.saveAndFlush(TestData.order("k-new", T0.plusSeconds(5))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleStatusWriteIsRejectedByOptimisticLock() {
        em.detach(paidOrder);
        // Another transaction cancels the order behind our back.
        em.getEntityManager()
                .createNativeQuery("update orders set status = 'CANCELLED', version = version + 1 where id = ?1")
                .setParameter(1, paidOrder.getId())
                .executeUpdate();
        paidOrder.transitionTo(OrderStatus.SHIPPED); // our copy still believes the order is PAID
        assertThatThrownBy(() -> orders.saveAndFlush(paidOrder))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
