package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.StockType;

import java.time.Instant;

public record StockChangeResponse(
        Long productId,
        String productCode,
        StockType type,
        Long quantity,
        Long beforeQuantity,
        Long afterQuantity,
        String requestId,
        Instant createdAt
) {
}
