package com.github.sup3rmar10.orders.error;

import org.springframework.http.HttpStatus;

/** A business-rule failure that maps to an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", what + " not found");
    }

    public static ApiException skuExists(String sku) {
        return new ApiException(HttpStatus.CONFLICT, "sku-exists", "SKU already exists: " + sku);
    }

    public static ApiException unknownSku(String sku) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-sku", "Unknown SKU: " + sku);
    }

    public static ApiException insufficientStock(String sku, int requested, int available) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock",
                "SKU " + sku + ": requested " + requested + ", available " + available);
    }

    public static ApiException illegalTransition(Object from, Object to) {
        return new ApiException(HttpStatus.CONFLICT, "illegal-transition",
                "Cannot move order from " + from + " to " + to);
    }

    public static ApiException idempotencyKeyReuse() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reuse",
                "Idempotency-Key was already used with a different request body");
    }
}
