package com.example.inventory.management.inventory.common.exception;

public class StockQuantityOverflowException extends InventoryException {

    /**
     * @param target identifies the product, e.g. "productId=1"
     */
    public StockQuantityOverflowException(String target) {
        super(ErrorCode.STOCK_QUANTITY_OVERFLOW, "재고 수량이 저장 가능한 최대값을 초과합니다. " + target);
    }
}
