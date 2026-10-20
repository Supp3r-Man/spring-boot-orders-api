package com.github.sup3rmar10.orders.service;

import com.github.sup3rmar10.orders.domain.Item;
import com.github.sup3rmar10.orders.domain.Order;
import com.github.sup3rmar10.orders.domain.OrderLine;
import com.github.sup3rmar10.orders.domain.OrderStatus;
import com.github.sup3rmar10.orders.error.ApiException;
import com.github.sup3rmar10.orders.repo.ItemRepository;
import com.github.sup3rmar10.orders.repo.OrderRepository;
import com.github.sup3rmar10.orders.repo.OrderSpecs;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    public record LineCommand(String sku, int quantity) {
    }

    public record Placement(Order order, boolean replayed) {
    }

    private final ItemRepository items;
    private final OrderRepository orders;
    private final PricingService pricing;
    private final Clock clock;

    public OrderService(ItemRepository items, OrderRepository orders, PricingService pricing, Clock clock) {
        this.items = items;
        this.orders = orders;
        this.pricing = pricing;
        this.clock = clock;
    }

    /**
     * Places an order atomically: stock for every line is reserved or nothing is. Concurrent writers to the
     * same item are arbitrated by the item @Version (the loser gets an optimistic-locking failure).
     */
    @Transactional
    public Placement place(String idempotencyKey, String customerRef, List<LineCommand> commands) {
        Map<String, Integer> wanted = new TreeMap<>();
        for (LineCommand command : commands) {
            wanted.merge(command.sku(), command.quantity(), Integer::sum);
        }
        String hash = RequestFingerprint.of(customerRef, wanted);

        Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw ApiException.idempotencyKeyReuse();
            }
            return new Placement(existing.get(), true);
        }

        Map<String, Item> found = items.findBySkuIn(wanted.keySet()).stream()
                .collect(Collectors.toMap(Item::getSku, Function.identity()));
        List<OrderLine> lines = new ArrayList<>();
        List<PricingService.LineInput> inputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            Item item = found.get(entry.getKey());
            if (item == null) {
                throw ApiException.unknownSku(entry.getKey());
            }
            int quantity = entry.getValue();
            item.reserve(quantity);
            lines.add(new OrderLine(item.getSku(), item.getPriceMinor(), quantity,
                    pricing.lineTotal(item.getPriceMinor(), quantity)));
            inputs.add(new PricingService.LineInput(item.getPriceMinor(), quantity));
        }
        PricingService.Quote quote = pricing.quote(inputs);
        // The database keeps microseconds; truncate here so the 201 body and a later replay/GET agree exactly.
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Order order = new Order(customerRef, createdAt, quote.subtotalMinor(), quote.discountPercent(),
                quote.discountMinor(), quote.totalMinor(), idempotencyKey, hash, lines);
        return new Placement(orders.save(order), false);
    }

    @Transactional(readOnly = true)
    public Order get(long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order " + id));
    }

    /** Newest first; id is the tie-breaker so pages are stable when timestamps collide. */
    @Transactional(readOnly = true)
    public Page<Order> list(OrderStatus status, Instant createdAfter, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return orders.findAll(OrderSpecs.filter(status, createdAfter), PageRequest.of(page, size, sort));
    }

    @Transactional
    public Order transition(long id, OrderStatus target) {
        Order order = get(id);
        order.transitionTo(target);
        if (target == OrderStatus.CANCELLED) {
            for (OrderLine line : order.getLines()) {
                items.findBySku(line.getSku()).orElseThrow().restock(line.getQuantity());
            }
        }
        return order;
    }
}
