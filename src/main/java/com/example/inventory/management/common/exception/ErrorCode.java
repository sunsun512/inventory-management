package com.example.inventory.management.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    PRODUCT_CODE_MISMATCH(HttpStatus.BAD_REQUEST),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT),
    PRODUCT_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT),
    QUANTITY_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    STOCK_QUANTITY_OVERFLOW(HttpStatus.CONFLICT),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    STOCK_LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
