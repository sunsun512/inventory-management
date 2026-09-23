package com.example.inventory.management.inventory.product.dto;

public record StockQuantityResponse(
        Long productId,
        String productCode,
        String productName,
        Long quantity
) {
}
