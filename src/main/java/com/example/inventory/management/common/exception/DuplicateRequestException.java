package com.example.inventory.management.common.exception;

/**
 * The requestId was already used by a request that completed successfully. The original result is
 * deliberately not returned: a requestId belongs to exactly one successful request.
 */
public class DuplicateRequestException extends InventoryException {

    public DuplicateRequestException(String requestId) {
        super(ErrorCode.DUPLICATE_REQUEST, "이미 처리된 requestId입니다. requestId=" + requestId);
    }
}
