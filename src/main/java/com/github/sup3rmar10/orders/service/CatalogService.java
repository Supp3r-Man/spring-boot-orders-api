package com.github.sup3rmar10.orders.service;

import com.github.sup3rmar10.orders.domain.Item;
import com.github.sup3rmar10.orders.error.ApiException;
import com.github.sup3rmar10.orders.repo.ItemRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ItemRepository items;

    public CatalogService(ItemRepository items) {
        this.items = items;
    }

    @Transactional
    public Item create(String sku, String name, long priceMinor, int stock) {
        if (items.findBySku(sku).isPresent()) {
            throw ApiException.skuExists(sku);
        }
        return items.save(new Item(sku, name, priceMinor, stock));
    }

    @Transactional(readOnly = true)
    public Item get(String sku) {
        return items.findBySku(sku).orElseThrow(() -> ApiException.notFound("Item " + sku));
    }

    @Transactional(readOnly = true)
    public List<Item> list() {
        return items.findAllByOrderBySkuAsc();
    }
}
