package com.example.inventory.management.product.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One row of the product list: only the columns the list shows, read directly by a projection query. */
public record ProductSummaryResponse(
        @Schema(description = "상품 ID", example = "1") Long productId,
        @Schema(description = "상품 코드", example = "SKU1") String productCode,
        @Schema(description = "상품명", example = "상품 A") String productName,
        @Schema(description = "현재 재고 수량", example = "42") Long quantity
) {
}
