package com.github.sup3rmar10.orders.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameContentSameHash() {
        assertThat(RequestFingerprint.of("c1", Map.of("A", 1, "B", 2)))
                .isEqualTo(RequestFingerprint.of("c1", Map.of("A", 1, "B", 2)))
                .hasSize(64);
    }

    @Test
    void lineOrderDoesNotMatter() {
        Map<String, Integer> ab = new LinkedHashMap<>();
        ab.put("A", 1);
        ab.put("B", 2);
        Map<String, Integer> ba = new LinkedHashMap<>();
        ba.put("B", 2);
        ba.put("A", 1);
        assertThat(RequestFingerprint.of("c1", ab)).isEqualTo(RequestFingerprint.of("c1", ba));
    }

    @Test
    void quantityChangesHash() {
        assertThat(RequestFingerprint.of("c1", Map.of("A", 1))).isNotEqualTo(RequestFingerprint.of("c1", Map.of("A", 2)));
    }

    @Test
    void customerChangesHash() {
        assertThat(RequestFingerprint.of("c1", Map.of("A", 1))).isNotEqualTo(RequestFingerprint.of("c2", Map.of("A", 1)));
    }

    @Test
    void skuChangesHash() {
        assertThat(RequestFingerprint.of("c1", Map.of("A", 1))).isNotEqualTo(RequestFingerprint.of("c1", Map.of("B", 1)));
    }

    @Test
    void boundariesAreUnambiguous() {
        // One SKU whose text imitates the separators of a two-SKU request. Without length prefixes the
        // canonical strings would be identical ("|sku=A|qty=1|sku=B|qty=2") and the hashes would collide.
        assertThat(RequestFingerprint.of("c1", Map.of("A|qty=1|sku=B", 2)))
                .isNotEqualTo(RequestFingerprint.of("c1", new java.util.TreeMap<>(Map.of("A", 1, "B", 2))));
    }

    @Test
    void customerBoundaryIsUnambiguous() {
        // A customer ref that imitates a SKU entry must not hash like a real customer with that SKU.
        assertThat(RequestFingerprint.of("c1|sku=1:A|qty=1", Map.of()))
                .isNotEqualTo(RequestFingerprint.of("c1", Map.of("A", 1)));
    }
}
