package com.github.sup3rmar10.orders.web;

import static com.github.sup3rmar10.orders.TestData.T0;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.sup3rmar10.orders.TestData;
import com.github.sup3rmar10.orders.domain.Item;
import com.github.sup3rmar10.orders.domain.Order;
import com.github.sup3rmar10.orders.repo.ItemRepository;
import com.github.sup3rmar10.orders.repo.OrderRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

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

    // ---- helpers ----------------------------------------------------------------------------------

    private void item(String sku, long priceMinor, int stock) {
        items.save(new Item(sku, "Item " + sku, priceMinor, stock));
    }

    /** lines are "SKU:qty" strings. */
    private static String orderBody(String customer, String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String[] parts = line.split(":");
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append("{\"sku\":\"").append(parts[0]).append("\",\"quantity\":").append(parts[1]).append('}');
        }
        return "{\"customerRef\":\"" + customer + "\",\"lines\":[" + sb + "]}";
    }

    private ResultActions place(String key, String body) throws Exception {
        return mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content(body));
    }

    private long placeOk(String key, String body) throws Exception {
        String json = place(key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private ResultActions changeStatus(long id, String target) throws Exception {
        return mvc.perform(post("/orders/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + target + "\"}"));
    }

    private void assertStock(String sku, int expected) throws Exception {
        mvc.perform(get("/items/" + sku)).andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(expected));
    }

    // ---- catalogue ---------------------------------------------------------------------------------

    @Test
    void createItemReturns201WithLocation() throws Exception {
        mvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"WID-1\",\"name\":\"Widget\",\"priceMinor\":1999,\"stock\":10}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/items/WID-1"))
                .andExpect(jsonPath("$.priceMinor").value(1999))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void duplicateSkuIs409Problem() throws Exception {
        item("WID-1", 100, 1);
        mvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"WID-1\",\"name\":\"Again\",\"priceMinor\":5,\"stock\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("sku-exists"));
    }

    @Test
    void invalidItemListsEveryBadField() throws Exception {
        mvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"bad sku!\",\"name\":\" \",\"priceMinor\":0,\"stock\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("urn:problem:orders:validation"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("sku", "name", "priceMinor", "stock")));
    }

    @Test
    void malformedJsonIs400Problem() throws Exception {
        mvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void unknownItemIs404() throws Exception {
        mvc.perform(get("/items/NOPE")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    // ---- placing orders ----------------------------------------------------------------------------

    @Test
    void placeOrderPricesLinesAndReservesStock() throws Exception {
        item("A", 1999, 10);
        item("B", 500, 10);
        place("key-1", orderBody("cust-1", "A:2", "B:1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/orders/")))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.subtotalMinor").value(4498))
                .andExpect(jsonPath("$.discountMinor").value(0))
                .andExpect(jsonPath("$.totalMinor").value(4498))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].sku").value("A"))
                .andExpect(jsonPath("$.lines[0].lineTotalMinor").value(3998));
        assertStock("A", 8);
        assertStock("B", 9);
    }

    @Test
    void placeOrderAppliesDiscountTier() throws Exception {
        item("PRO", 25_000, 5);
        place("key-1", orderBody("cust-1", "PRO:1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountPercent").value(5))
                .andExpect(jsonPath("$.discountMinor").value(1250))
                .andExpect(jsonPath("$.totalMinor").value(23_750));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        item("A", 100, 1);
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(orderBody("c", "A:1")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        assertStock("A", 1);
    }

    @Test
    void blankIdempotencyKeyIs400() throws Exception {
        item("A", 100, 1);
        place(" ", orderBody("c", "A:1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("idempotencyKey")));
    }

    @Test
    void idempotencyKeyLengthLimitIs128() throws Exception {
        item("A", 100, 5);
        placeOk("k".repeat(128), orderBody("c", "A:1"));
        place("k".repeat(129), orderBody("c", "A:1")).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.errors[*].field", hasItem("idempotencyKey")));
        assertStock("A", 4);
    }

    @Test
    void createdAtInThePlacementResponseMatchesWhatIsStored() throws Exception {
        item("A", 100, 5);
        String json = place("key-1", orderBody("c", "A:1")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String created = JsonPath.read(json, "$.createdAt");
        long id = ((Number) JsonPath.read(json, "$.id")).longValue();
        mvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.createdAt").value(created));
        place("key-1", orderBody("c", "A:1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(created));
    }

    @Test
    void sameKeySameBodyReplaysTheOriginalOrder() throws Exception {
        item("A", 100, 5);
        long first = placeOk("key-1", orderBody("cust-1", "A:2"));
        place("key-1", orderBody("cust-1", "A:2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first));
        assertStock("A", 3); // reserved once, not twice
        assertEquals(1, orders.count());
    }

    @Test
    void replayIsRecognisedEvenIfLinesAreReorderedOrSplit() throws Exception {
        item("A", 100, 10);
        item("B", 100, 10);
        long first = placeOk("key-1", orderBody("cust-1", "A:2", "B:1"));
        place("key-1", orderBody("cust-1", "B:1", "A:1", "A:1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));
        assertStock("A", 8);
    }

    @Test
    void sameKeyDifferentQuantityIs422AndChangesNothing() throws Exception {
        item("A", 100, 10);
        placeOk("key-1", orderBody("cust-1", "A:2"));
        place("key-1", orderBody("cust-1", "A:3"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("idempotency-key-reuse"));
        assertStock("A", 8);
        assertEquals(1, orders.count());
    }

    @Test
    void sameKeyDifferentCustomerIs422() throws Exception {
        item("A", 100, 10);
        placeOk("key-1", orderBody("cust-1", "A:1"));
        place("key-1", orderBody("cust-2", "A:1")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void differentKeysCreateDifferentOrders() throws Exception {
        item("A", 100, 10);
        long one = placeOk("key-1", orderBody("cust-1", "A:1"));
        long two = placeOk("key-2", orderBody("cust-1", "A:1"));
        assertEquals(1, two - one);
        assertStock("A", 8);
    }

    @Test
    void unknownSkuIs422() throws Exception {
        place("key-1", orderBody("cust-1", "GHOST:1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unknown-sku"));
    }

    @Test
    void insufficientStockIs422AndNothingIsPersisted() throws Exception {
        item("A", 100, 1);
        place("key-1", orderBody("cust-1", "A:2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient-stock"));
        assertStock("A", 1);
        assertEquals(0, orders.count());
    }

    @Test
    void failureOnSecondLineRollsBackTheFirst() throws Exception {
        item("A", 100, 5);
        item("B", 100, 0);
        place("key-1", orderBody("cust-1", "A:1", "B:1")).andExpect(status().isUnprocessableEntity());
        assertStock("A", 5);
        assertEquals(0, orders.count());
    }

    @Test
    void emptyLinesAndBlankCustomerAre400WithFieldList() throws Exception {
        place("key-1", "{\"customerRef\":\"\",\"lines\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("customerRef", "lines")));
    }

    @Test
    void quantityOutOfRangeNamesTheNestedField() throws Exception {
        item("A", 100, 5);
        place("key-1", orderBody("cust-1", "A:0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("lines[0].quantity")));
        place("key-2", orderBody("cust-1", "A:1001"))
                .andExpect(status().isBadRequest());
    }

    // ---- reading and listing -----------------------------------------------------------------------

    @Test
    void getOrderReturnsLines() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("cust-1", "A:2"));
        mvc.perform(get("/orders/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerRef").value("cust-1"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));
    }

    @Test
    void unknownOrderIs404Problem() throws Exception {
        mvc.perform(get("/orders/999999")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void unknownPathIs404Problem() throws Exception {
        mvc.perform(get("/nope")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        item("A", 100, 10);
        placeOk("key-1", orderBody("c", "A:1"));
        long paid = placeOk("key-2", orderBody("c", "A:1"));
        changeStatus(paid, "PAID").andExpect(status().isOk());
        mvc.perform(get("/orders").param("status", "PAID")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(paid));
    }

    @Test
    void listCreatedAfterIsExclusive() throws Exception {
        orders.save(TestData.order("k0", T0));
        Order one = orders.save(TestData.order("k1", T0.plus(Duration.ofHours(1))));
        Order two = orders.save(TestData.order("k2", T0.plus(Duration.ofHours(2))));
        mvc.perform(get("/orders").param("createdAfter", "2026-01-01T01:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(two.getId().intValue()));
        mvc.perform(get("/orders").param("createdAfter", "2026-01-01T00:59:59Z"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[1].id").value(one.getId().intValue()));
    }

    @Test
    void listIsNewestFirst() throws Exception {
        Order middle = orders.save(TestData.order("k1", T0.plus(Duration.ofHours(1))));
        Order newest = orders.save(TestData.order("k2", T0.plus(Duration.ofHours(2))));
        Order oldest = orders.save(TestData.order("k0", T0));
        mvc.perform(get("/orders"))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().intValue()))
                .andExpect(jsonPath("$.content[1].id").value(middle.getId().intValue()))
                .andExpect(jsonPath("$.content[2].id").value(oldest.getId().intValue()));
    }

    @Test
    void identicalTimestampsPageStablyByIdDescending() throws Exception {
        Order a = orders.save(TestData.order("k1", T0));
        Order b = orders.save(TestData.order("k2", T0));
        Order c = orders.save(TestData.order("k3", T0));
        mvc.perform(get("/orders").param("size", "2"))
                .andExpect(jsonPath("$.content[0].id").value(c.getId().intValue()))
                .andExpect(jsonPath("$.content[1].id").value(b.getId().intValue()))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/orders").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a.getId().intValue()));
    }

    @Test
    void paginationReportsTotals() throws Exception {
        for (int i = 0; i < 5; i++) {
            orders.save(TestData.order("k" + i, T0.plus(Duration.ofMinutes(i))));
        }
        mvc.perform(get("/orders").param("size", "2").param("page", "2"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "page=-1", "status=BOGUS", "createdAfter=yesterday"})
    void badListParametersAre400Problems(String query) throws Exception {
        mvc.perform(get("/orders?" + query)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    // ---- state machine over HTTP -------------------------------------------------------------------

    @Test
    void orderWalksNewPaidShipped() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("c", "A:1"));
        changeStatus(id, "PAID").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        changeStatus(id, "SHIPPED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void skippingPaymentIs409AndStatusIsUnchanged() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("c", "A:1"));
        changeStatus(id, "SHIPPED").andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("illegal-transition"));
        mvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void shippedOrderCannotBeCancelledAndStockStaysSold() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("c", "A:2"));
        changeStatus(id, "PAID").andExpect(status().isOk());
        changeStatus(id, "SHIPPED").andExpect(status().isOk());
        changeStatus(id, "CANCELLED").andExpect(status().isConflict());
        assertStock("A", 3);
    }

    @Test
    void cancellingRestocksTheItems() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("c", "A:2"));
        assertStock("A", 3);
        changeStatus(id, "CANCELLED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertStock("A", 5);
    }

    @Test
    void cancellingTwiceIs409AndDoesNotRestockTwice() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("c", "A:2"));
        changeStatus(id, "CANCELLED").andExpect(status().isOk());
        changeStatus(id, "CANCELLED").andExpect(status().isConflict());
        assertStock("A", 5);
    }

    @Test
    void statusChangeOnUnknownOrderIs404() throws Exception {
        changeStatus(424242, "PAID").andExpect(status().isNotFound());
    }

    @Test
    void statusChangeWithMissingOrUnknownStatusIs400() throws Exception {
        item("A", 100, 5);
        long id = placeOk("key-1", orderBody("c", "A:1"));
        mvc.perform(post("/orders/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("status")));
        changeStatus(id, "TELEPORTED").andExpect(status().isBadRequest());
    }

    // ---- operations --------------------------------------------------------------------------------

    @Test
    void actuatorHealthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
