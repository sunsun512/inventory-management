package com.example.inventory.management.stock.command.dto;

import com.example.inventory.management.stock.command.validation.QuantityLimit;
import com.example.inventory.management.stock.command.validation.RequestIdFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record OutboundRequest(
        @Schema(description = "상품 ID", example = "1")
        @NotNull Long productId,

        @Schema(description = "상품 코드. 해당 상품의 코드와 정확히 일치해야 한다", example = "SKU1")
        @NotNull
        @Size(max = 64)
        @Pattern(regexp = "^[A-Z0-9]+$", message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
        String productCode,

        @Schema(description = "출고 수량. 현재 재고 이하여야 하며, 10,000 초과는 409 QUANTITY_LIMIT_EXCEEDED", minimum = "1", maximum = "10000", example = "10")
        @NotNull @Positive @QuantityLimit Long quantity,

        @Schema(description = "클라이언트가 생성한 UUID. 대소문자 무관(소문자로 정규화)하며 처음 성공한 요청 한 건만 처리", example = "3f1c2a9e-8b7d-4e21-9c3a-6d5e4f3b2a10")
        @NotNull @RequestIdFormat String requestId
) {

    public OutboundRequest {
        requestId = requestId == null ? null : requestId.toLowerCase(Locale.ROOT);
    }
}
