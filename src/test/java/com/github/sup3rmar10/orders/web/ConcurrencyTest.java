package com.github.sup3rmar10.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.sup3rmar10.orders.domain.Item;
import com.github.sup3rmar10.orders.repo.ItemRepository;
import com.github.sup3rmar10.orders.repo.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real threads, real transactions, real H2 row locks. Not @Transactional on purpose. */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ItemRepository items;

    @Autowired
    OrderRepository orders;

    @BeforeEach
    void clean() {
        orders.deleteAll();
        items.deleteAll();
    }

    private static MockHttpServletRequestBuilder buy(String key, String sku, int qty) {
        return post("/orders").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content("{\"customerRef\":\"cust\",\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":" + qty + "}]}");
    }

    /** Releases n requests at the same instant and returns their HTTP status codes. */
    private List<Integer> race(int n, IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<Integer> task = () -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    return mvc.perform(request.apply(index)).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    @Test
    void twoBuyersRacingForTheLastUnitProduceExactlyOneOrder() throws Exception {
        int rounds = 25;
        for (int round = 0; round < rounds; round++) {
            String sku = "LAST-" + round;
            items.save(new Item(sku, "Last unit", 1000, 1));
            int r = round;
            List<Integer> statuses = race(2, i -> buy("k-" + r + "-" + i, sku, 1));
            assertThat(count(statuses, 201)).as("round %d statuses %s", round, statuses).isEqualTo(1);
            assertThat(statuses).as("loser must be 409 or 422, round %d", round)
                    .allMatch(s -> s == 201 || s == 409 || s == 422);
            assertThat(items.findBySku(sku).orElseThrow().getStock()).isZero();
        }
        assertThat(orders.count()).isEqualTo(rounds);
    }

    @Test
    void eightBuyersRacingForOneUnitStillProduceExactlyOneOrder() throws Exception {
        items.save(new Item("LAST", "Last unit", 1000, 1));
        List<Integer> statuses = race(8, i -> buy("k-" + i, "LAST", 1));
        assertThat(count(statuses, 201)).as("statuses %s", statuses).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409 || s == 422);
        assertThat(items.findBySku("LAST").orElseThrow().getStock()).isZero();
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void contentionNeverOversellsAndNeverLosesAnUpdate() throws Exception {
        items.save(new Item("POOL", "Pool of five", 1000, 5));
        List<Integer> statuses = race(12, i -> buy("k-" + i, "POOL", 1));
        long sold = count(statuses, 201);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409 || s == 422);
        assertThat(sold).as("statuses %s", statuses).isBetween(1L, 5L);
        // Every accepted order is reflected in stock: no lost update, no phantom decrement.
        assertThat(items.findBySku("POOL").orElseThrow().getStock()).isEqualTo(5 - (int) sold);
        assertThat(orders.count()).isEqualTo(sold);
    }

    @Test
    void sameIdempotencyKeyInFlightTwiceCreatesOneOrder() throws Exception {
        items.save(new Item("A", "Widget", 1000, 10));
        List<Integer> statuses = race(2, i -> buy("same-key", "A", 1));
        assertThat(count(statuses, 201)).as("statuses %s", statuses).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(items.findBySku("A").orElseThrow().getStock()).isEqualTo(9);
    }
}
