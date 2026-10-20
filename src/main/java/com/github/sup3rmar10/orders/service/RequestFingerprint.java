package com.github.sup3rmar10.orders.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Order-independent hash of a place-order request, used to compare replays of an Idempotency-Key. */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String customerRef, Map<String, Integer> quantitiesBySku) {
        StringBuilder canonical = new StringBuilder("customer=").append(customerRef.length()).append(':')
                .append(customerRef);
        new TreeMap<>(quantitiesBySku).forEach((sku, qty) ->
                canonical.append("|sku=").append(sku.length()).append(':').append(sku).append("|qty=").append(qty));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
