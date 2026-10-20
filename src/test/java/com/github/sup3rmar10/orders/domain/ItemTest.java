package com.github.sup3rmar10.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sup3rmar10.orders.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ItemTest {

    @Test
    void reserveDecrementsStock() {
        Item item = new Item("A", "Widget", 100, 5);
        item.reserve(2);
        assertThat(item.getStock()).isEqualTo(3);
    }

    @Test
    void canReserveExactlyTheRemainingStock() {
        Item item = new Item("A", "Widget", 100, 2);
        item.reserve(2);
        assertThat(item.getStock()).isZero();
    }

    @Test
    void reservingMoreThanAvailableIsRejectedAndLeavesStockAlone() {
        Item item = new Item("A", "Widget", 100, 2);
        assertThatThrownBy(() -> item.reserve(3))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("insufficient-stock");
                });
        assertThat(item.getStock()).isEqualTo(2);
    }

    @Test
    void restockAddsBack() {
        Item item = new Item("A", "Widget", 100, 2);
        item.restock(4);
        assertThat(item.getStock()).isEqualTo(6);
    }
}
