package com.example.inventory.management.product.dto;

import com.example.inventory.management.product.Product;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record ProductDetailResponse(
        @Schema(description = "상품 ID", example = "1") Long productId,
        @Schema(description = "상품 코드", example = "SKU1") String productCode,
        @Schema(description = "상품명", example = "상품 A") String productName,
        @Schema(description = "현재 재고 수량", example = "42") Long quantity,
        @Schema(description = "등록 시각 (UTC)", example = "2026-09-23T14:37:35.511802Z") Instant createdAt,
        @Schema(description = "마지막 재고 변경 시각 (UTC)", example = "2026-09-23T14:37:35.511802Z") Instant updatedAt
) {

    public static ProductDetailResponse from(Product product) {
        return new ProductDetailResponse(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getQuantity(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
