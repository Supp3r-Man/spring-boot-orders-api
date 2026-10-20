package com.github.sup3rmar10.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Pure pricing rules. All money is a long count of minor units (cents); percentages are applied with
 * BigDecimal and rounded HALF_UP to a whole minor unit.
 */
@Service
public class PricingService {

    public record Tier(long thresholdMinor, int percent) {
    }

    public record LineInput(long unitPriceMinor, int quantity) {
    }

    public record Quote(long subtotalMinor, int discountPercent, long discountMinor, long totalMinor) {
    }

    /** Highest threshold first; the first tier whose threshold the subtotal reaches wins. */
    static final List<Tier> TIERS = List.of(
            new Tier(100_000, 15),
            new Tier(50_000, 10),
            new Tier(20_000, 5));

    public long lineTotal(long unitPriceMinor, int quantity) {
        if (unitPriceMinor < 0) {
            throw new IllegalArgumentException("unit price must not be negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return Math.multiplyExact(unitPriceMinor, (long) quantity);
    }

    public Quote quote(List<LineInput> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        long subtotal = 0;
        for (LineInput line : lines) {
            subtotal = Math.addExact(subtotal, lineTotal(line.unitPriceMinor(), line.quantity()));
        }
        int percent = percentFor(subtotal);
        long discount = BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Quote(subtotal, percent, discount, subtotal - discount);
    }

    int percentFor(long subtotalMinor) {
        for (Tier tier : TIERS) {
            if (subtotalMinor >= tier.thresholdMinor()) {
                return tier.percent();
            }
        }
        return 0;
    }
}
