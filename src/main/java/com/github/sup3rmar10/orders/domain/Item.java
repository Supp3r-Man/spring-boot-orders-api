package com.github.sup3rmar10.orders.domain;

import com.github.sup3rmar10.orders.error.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "items")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    /** Unit price in minor units (cents). Never a floating point type. */
    @Column(nullable = false)
    private long priceMinor;

    @Column(nullable = false)
    private int stock;

    /** Optimistic lock: concurrent stock changes cannot silently overwrite each other. */
    @Version
    private long version;

    protected Item() {
    }

    public Item(String sku, String name, long priceMinor, int stock) {
        this.sku = sku;
        this.name = name;
        this.priceMinor = priceMinor;
        this.stock = stock;
    }

    public void reserve(int quantity) {
        if (quantity > stock) {
            throw ApiException.insufficientStock(sku, quantity, stock);
        }
        stock -= quantity;
    }

    public void restock(int quantity) {
        stock += quantity;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public int getStock() {
        return stock;
    }

    public long getVersion() {
        return version;
    }
}
