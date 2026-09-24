package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.StockType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record StockChangeResponse(
        @Schema(description = "상품 ID", example = "1") Long productId,
        @Schema(description = "상품 코드", example = "SKU1") String productCode,
        @Schema(description = "변경 유형", example = "INBOUND") StockType type,
        @Schema(description = "요청 수량", example = "10") Long quantity,
        @Schema(description = "변경 전 재고", example = "0") Long beforeQuantity,
        @Schema(description = "변경 후 재고", example = "10") Long afterQuantity,
        @Schema(description = "요청 ID (소문자 UUID)", example = "3f1c2a9e-8b7d-4e21-9c3a-6d5e4f3b2a10") String requestId,
        @Schema(description = "처리 시각 (UTC)", example = "2026-09-23T14:37:35.511802Z") Instant createdAt
) {
}
