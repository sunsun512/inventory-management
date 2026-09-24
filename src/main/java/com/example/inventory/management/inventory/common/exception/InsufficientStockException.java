package com.example.inventory.management.inventory.common.exception;

public class InsufficientStockException extends InventoryException {

    public InsufficientStockException(Long productId, Long requestedQuantity) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                "재고가 부족합니다. productId=" + productId + ", requestedQuantity=" + requestedQuantity);
    }
}
