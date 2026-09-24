package com.example.inventory.management.inventory.common.exception;

/**
 * A new-product inbound (no productId) named a productCode that is already registered. Stock is
 * never added to the existing product on this path; the client must send productId + productCode.
 */
public class ProductCodeAlreadyExistsException extends InventoryException {

    public ProductCodeAlreadyExistsException(String productCode) {
        super(ErrorCode.PRODUCT_CODE_ALREADY_EXISTS,
                "이미 등록된 productCode입니다. 기존 상품에 입고하려면 productId와 productCode를 함께 보내세요. productCode="
                        + productCode);
    }
}
