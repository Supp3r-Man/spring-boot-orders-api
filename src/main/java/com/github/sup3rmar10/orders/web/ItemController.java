package com.github.sup3rmar10.orders.web;

import com.github.sup3rmar10.orders.service.CatalogService;
import com.github.sup3rmar10.orders.web.Dtos.CreateItemRequest;
import com.github.sup3rmar10.orders.web.Dtos.ItemResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    private final CatalogService catalog;

    public ItemController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody CreateItemRequest request) {
        ItemResponse body = ItemResponse.from(
                catalog.create(request.sku(), request.name(), request.priceMinor(), request.stock()));
        return ResponseEntity.created(URI.create("/items/" + body.sku())).body(body);
    }

    @GetMapping
    public List<ItemResponse> list() {
        return catalog.list().stream().map(ItemResponse::from).toList();
    }

    @GetMapping("/{sku}")
    public ItemResponse get(@PathVariable String sku) {
        return ItemResponse.from(catalog.get(sku));
    }
}
