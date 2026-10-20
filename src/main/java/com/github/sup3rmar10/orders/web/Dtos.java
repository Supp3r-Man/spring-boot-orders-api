package com.github.sup3rmar10.orders.web;

import com.github.sup3rmar10.orders.domain.Item;
import com.github.sup3rmar10.orders.domain.Order;
import com.github.sup3rmar10.orders.domain.OrderLine;
import com.github.sup3rmar10.orders.domain.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

/** Request and response shapes. Money is always an integer count of minor units (USD cents). */
public final class Dtos {

    public static final String CURRENCY = "USD";
    private static final String SKU_PATTERN = "[A-Za-z0-9_-]+";

    private Dtos() {
    }

    public record CreateItemRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = SKU_PATTERN) String sku,
            @NotBlank @Size(max = 200) String name,
            @Positive long priceMinor,
            @PositiveOrZero int stock) {
    }

    public record ItemResponse(String sku, String name, long priceMinor, String currency, int stock) {
        static ItemResponse from(Item item) {
            return new ItemResponse(item.getSku(), item.getName(), item.getPriceMinor(), CURRENCY, item.getStock());
        }
    }

    public record LineRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = SKU_PATTERN) String sku,
            @Min(1) @Max(1000) int quantity) {
    }

    public record PlaceOrderRequest(
            @NotBlank @Size(max = 64) String customerRef,
            @NotEmpty @Size(max = 50) @Valid List<LineRequest> lines) {
    }

    public record StatusChangeRequest(@NotNull OrderStatus status) {
    }

    public record LineResponse(String sku, int quantity, long unitPriceMinor, long lineTotalMinor) {
        static LineResponse from(OrderLine line) {
            return new LineResponse(line.getSku(), line.getQuantity(), line.getUnitPriceMinor(),
                    line.getLineTotalMinor());
        }
    }

    public record OrderResponse(Long id, OrderStatus status, String customerRef, Instant createdAt,
                                String currency, long subtotalMinor, int discountPercent, long discountMinor,
                                long totalMinor, List<LineResponse> lines) {
        static OrderResponse from(Order order) {
            return new OrderResponse(order.getId(), order.getStatus(), order.getCustomerRef(),
                    order.getCreatedAt(), CURRENCY, order.getSubtotalMinor(), order.getDiscountPercent(),
                    order.getDiscountMinor(), order.getTotalMinor(),
                    order.getLines().stream().map(LineResponse::from).toList());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <S, T> PageResponse<T> from(Page<S> page, java.util.function.Function<S, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
