package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.validation.QuantityLimit;
import com.example.inventory.management.inventory.stock.validation.RequestIdFormat;
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
        Long productId,

        @NotNull
        @Size(max = 64)
        @Pattern(regexp = "^[A-Z0-9]+$", message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
        String productCode,

        String productName,

        @NotNull @Positive @QuantityLimit Long quantity,

        @NotNull @RequestIdFormat String requestId
) {

    public static final int MAX_PRODUCT_NAME_LENGTH = 255;

    public InboundRequest {
        requestId = requestId == null ? null : requestId.toLowerCase(Locale.ROOT);
    }

    /** Only a new-product registration (no productId) uses productName, so only then is it required. */
    @AssertTrue(message = "신규 상품 등록(productId 없음)에는 1~" + MAX_PRODUCT_NAME_LENGTH + "자의 productName이 필요합니다.")
    public boolean isProductNameValid() {
        if (productId != null) {
            return true;
        }
        return productName != null && !productName.isBlank() && productName.length() <= MAX_PRODUCT_NAME_LENGTH;
    }
}
