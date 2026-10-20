package com.github.sup3rmar10.orders.domain;

import com.github.sup3rmar10.orders.error.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders", indexes = @Index(name = "ix_orders_status_created", columnList = "status,created_at"))
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status = OrderStatus.NEW;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64)
    private String customerRef;

    @Column(nullable = false)
    private long subtotalMinor;

    @Column(nullable = false)
    private int discountPercent;

    @Column(nullable = false)
    private long discountMinor;

    @Column(nullable = false)
    private long totalMinor;

    @Column(nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    /** SHA-256 of the normalised request; lets us tell a replay from key misuse. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Version
    private long version;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id ASC")
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
    }

    public Order(String customerRef, Instant createdAt, long subtotalMinor, int discountPercent,
                 long discountMinor, long totalMinor, String idempotencyKey, String requestHash,
                 List<OrderLine> lines) {
        this.customerRef = customerRef;
        this.createdAt = createdAt;
        this.subtotalMinor = subtotalMinor;
        this.discountPercent = discountPercent;
        this.discountMinor = discountMinor;
        this.totalMinor = totalMinor;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.lines = new ArrayList<>(lines);
    }

    public void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw ApiException.illegalTransition(status, target);
        }
        status = target;
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public long getSubtotalMinor() {
        return subtotalMinor;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }

    public long getDiscountMinor() {
        return discountMinor;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public List<OrderLine> getLines() {
        return List.copyOf(lines);
    }
}
