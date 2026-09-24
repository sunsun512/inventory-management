package com.example.inventory.management.stock.dto;

import com.example.inventory.management.stock.validation.QuantityLimit;
import com.example.inventory.management.stock.validation.RequestIdFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * Inbound to an existing product: productId + productCode (productName is ignored).
 * Inbound registering a new product: no productId, productCode + productName.
 */
public record InboundRequest(
        @Schema(description = "기존 상품 입고 시 상품 ID. 신규 상품 등록 입고에서는 보내지 않는다", example = "1")
        Long productId,

        @Schema(description = "상품 코드. 기존 상품 입고 시 해당 상품의 코드와 정확히 일치해야 한다", example = "SKU1")
        @NotNull
        @Size(max = 64)
        @Pattern(regexp = "^[A-Z0-9]+$", message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
        String productCode,

        @Schema(description = "상품명. 신규 상품 등록 입고에서만 필수(1~255자)이며 기존 상품 입고에서는 무시된다",
                maxLength = MAX_PRODUCT_NAME_LENGTH, example = "상품 A")
        String productName,

        @Schema(description = "입고 수량. 10,000 초과는 409 QUANTITY_LIMIT_EXCEEDED", minimum = "1", maximum = "10000", example = "10")
        @NotNull @Positive @QuantityLimit Long quantity,

        @Schema(description = "클라이언트가 생성한 UUID. 대소문자 무관(소문자로 정규화)하며 처음 성공한 요청 한 건만 처리", example = "3f1c2a9e-8b7d-4e21-9c3a-6d5e4f3b2a10")
        @NotNull @RequestIdFormat String requestId
) {

    public static final int MAX_PRODUCT_NAME_LENGTH = 255;

    public InboundRequest {
        requestId = requestId == null ? null : requestId.toLowerCase(Locale.ROOT);
    }

    /** Only a new-product registration (no productId) uses productName, so only then is it required. */
    @Schema(hidden = true)
    @AssertTrue(message = "신규 상품 등록(productId 없음)에는 1~" + MAX_PRODUCT_NAME_LENGTH + "자의 productName이 필요합니다.")
    public boolean isProductNameValid() {
        if (productId != null) {
            return true;
        }
        return productName != null && !productName.isBlank() && productName.length() <= MAX_PRODUCT_NAME_LENGTH;
    }
}
