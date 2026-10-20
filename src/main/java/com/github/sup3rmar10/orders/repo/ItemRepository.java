package com.github.sup3rmar10.orders.repo;

import com.github.sup3rmar10.orders.domain.Item;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findBySku(String sku);

    List<Item> findBySkuIn(Collection<String> skus);

    List<Item> findAllByOrderBySkuAsc();
}
