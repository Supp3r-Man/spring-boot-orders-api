package com.github.sup3rmar10.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String sku;

    /** Price snapshot at order time, so later catalogue edits do not rewrite history. */
    @Column(nullable = false)
    private long unitPriceMinor;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long lineTotalMinor;

    protected OrderLine() {
    }

    public OrderLine(String sku, long unitPriceMinor, int quantity, long lineTotalMinor) {
        this.sku = sku;
        this.unitPriceMinor = unitPriceMinor;
        this.quantity = quantity;
        this.lineTotalMinor = lineTotalMinor;
    }

    public String getSku() {
        return sku;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getLineTotalMinor() {
        return lineTotalMinor;
    }
}
