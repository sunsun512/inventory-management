package com.example.inventory.management.common.exception;

public abstract class InventoryException extends RuntimeException {

    private final ErrorCode errorCode;

    protected InventoryException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
