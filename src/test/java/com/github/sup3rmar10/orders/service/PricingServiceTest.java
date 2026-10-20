package com.github.sup3rmar10.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sup3rmar10.orders.service.PricingService.LineInput;
import com.github.sup3rmar10.orders.service.PricingService.Quote;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingServiceTest {

    private final PricingService pricing = new PricingService();

    private Quote single(long subtotalMinor) {
        return pricing.quote(List.of(new LineInput(subtotalMinor, 1)));
    }

    @ParameterizedTest(name = "subtotal {0} -> {1}% off {2}, total {3}")
    @CsvSource({
            "1,0,0,1",
            "19999,0,0,19999",
            "20000,5,1000,19000",
            "49999,5,2500,47499",
            "50000,10,5000,45000",
            "99999,10,10000,89999",
            "100000,15,15000,85000",
            "250000,15,37500,212500"})
    void tierBoundaries(long subtotal, int percent, long discount, long total) {
        Quote quote = single(subtotal);
        assertThat(quote.subtotalMinor()).isEqualTo(subtotal);
        assertThat(quote.discountPercent()).isEqualTo(percent);
        assertThat(quote.discountMinor()).isEqualTo(discount);
        assertThat(quote.totalMinor()).isEqualTo(total);
    }

    @Test
    void roundsHalfUpNotBankers() {
        // 20010 * 5% = 1000.5 -> 1001 (HALF_EVEN would give 1000)
        assertThat(single(20_010).discountMinor()).isEqualTo(1001);
        // 20050 * 5% = 1002.5 -> 1003 (HALF_EVEN would give 1002)
        assertThat(single(20_050).discountMinor()).isEqualTo(1003);
        // 50005 * 10% = 5000.5 -> 5001
        assertThat(single(50_005).discountMinor()).isEqualTo(5001);
    }

    @Test
    void roundsDownBelowHalf() {
        // 20009 * 5% = 1000.45 -> 1000
        assertThat(single(20_009).discountMinor()).isEqualTo(1000);
    }

    @Test
    void sumsLinesBeforeChoosingTier() {
        Quote quote = pricing.quote(List.of(new LineInput(10_000, 1), new LineInput(5_000, 2)));
        assertThat(quote.subtotalMinor()).isEqualTo(20_000);
        assertThat(quote.discountPercent()).isEqualTo(5);
    }

    @Test
    void multipliesQuantityIntoLineTotal() {
        assertThat(pricing.lineTotal(1999, 3)).isEqualTo(5997);
        Quote quote = pricing.quote(List.of(new LineInput(1999, 3), new LineInput(500, 2)));
        assertThat(quote.subtotalMinor()).isEqualTo(6997);
        assertThat(quote.discountPercent()).isZero();
    }

    @Test
    void totalPlusDiscountAlwaysEqualsSubtotalAndDiscountNeverExceedsTopTier() {
        for (long subtotal = 0; subtotal <= 150_000; subtotal += 7) {
            Quote quote = single(subtotal);
            assertThat(quote.totalMinor() + quote.discountMinor()).isEqualTo(subtotal);
            assertThat(quote.discountMinor()).isBetween(0L, Math.round(subtotal * 0.15) + 1);
        }
    }

    @Test
    void discountPercentNeverDecreasesAsSubtotalGrows() {
        int previous = 0;
        for (long subtotal = 0; subtotal <= 150_000; subtotal += 13) {
            int percent = single(subtotal).discountPercent();
            assertThat(percent).isGreaterThanOrEqualTo(previous);
            previous = percent;
        }
    }

    @Test
    void rejectsEmptyOrder() {
        assertThatThrownBy(() -> pricing.quote(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> pricing.lineTotal(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.lineTotal(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> pricing.lineTotal(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowFailsLoudlyInsteadOfWrappingAround() {
        assertThatThrownBy(() -> pricing.quote(List.of(new LineInput(Long.MAX_VALUE / 2, 3))))
                .isInstanceOf(ArithmeticException.class);
    }
}
