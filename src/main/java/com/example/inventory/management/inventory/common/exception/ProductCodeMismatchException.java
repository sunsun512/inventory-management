package com.example.inventory.management.inventory.common.exception;

public class ProductCodeMismatchException extends InventoryException {

    public ProductCodeMismatchException(Long productId, String productCode) {
        super(ErrorCode.PRODUCT_CODE_MISMATCH,
                "productCode가 상품과 일치하지 않습니다. productId=" + productId + ", productCode=" + productCode);
    }
}
