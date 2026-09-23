package com.example.inventory.management.inventory.common.exception;

public class ProductNotFoundException extends InventoryException {

    public ProductNotFoundException(Long productId) {
        super(ErrorCode.PRODUCT_NOT_FOUND, "상품을 찾을 수 없습니다. productId=" + productId);
    }
}
