package com.github.sup3rmar10.orders.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sup3rmar10.orders.domain.Item;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DataJpaTest
class ItemRepositoryTest {

    @Autowired
    ItemRepository items;

    @Autowired
    TestEntityManager em;

    @Test
    void findsBySku() {
        em.persist(new Item("A-1", "Widget", 250, 4));
        em.flush();
        assertThat(items.findBySku("A-1")).get().extracting(Item::getPriceMinor).isEqualTo(250L);
        assertThat(items.findBySku("missing")).isEmpty();
    }

    @Test
    void skuIsUnique() {
        items.saveAndFlush(new Item("A-1", "Widget", 250, 4));
        assertThatThrownBy(() -> items.saveAndFlush(new Item("A-1", "Other", 100, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findBySkuInAndSortedListing() {
        items.saveAndFlush(new Item("B", "b", 1, 1));
        items.saveAndFlush(new Item("A", "a", 1, 1));
        items.saveAndFlush(new Item("C", "c", 1, 1));
        assertThat(items.findBySkuIn(List.of("A", "C"))).extracting(Item::getSku).containsExactlyInAnyOrder("A", "C");
        assertThat(items.findAllByOrderBySkuAsc()).extracting(Item::getSku).containsExactly("A", "B", "C");
    }

    @Test
    void versionIncrementsOnEveryStockChange() {
        Item item = items.saveAndFlush(new Item("A-1", "Widget", 250, 4));
        long v0 = item.getVersion();
        item.reserve(1);
        items.saveAndFlush(item);
        assertThat(item.getVersion()).isEqualTo(v0 + 1);
    }

    @Test
    void staleWriteIsRejectedByOptimisticLock() {
        Item loaded = items.saveAndFlush(new Item("A-1", "Widget", 250, 1));
        em.detach(loaded);
        // Another transaction sells the last unit behind our back.
        em.getEntityManager()
                .createNativeQuery("update items set stock = stock - 1, version = version + 1 where id = ?1")
                .setParameter(1, loaded.getId())
                .executeUpdate();
        loaded.reserve(1); // our copy still believes one unit is left
        assertThatThrownBy(() -> items.saveAndFlush(loaded))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
