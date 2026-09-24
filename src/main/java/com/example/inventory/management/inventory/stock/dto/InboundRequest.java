package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.validation.QuantityLimit;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record InboundRequest(
        Long productId,

        @Size(max = 64)
        @Pattern(regexp = "^[A-Z0-9]+$", message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
        String productCode,

        @Size(max = 255) String productName,

        @NotNull @Positive @QuantityLimit Long quantity,

        @NotBlank @Size(max = 64) String requestId
) {

    @AssertTrue(message = "productId 또는 (productCode, productName)이 필요합니다.")
    public boolean isProductIdentifierValid() {
        boolean hasProductId = productId != null;
        boolean hasProductCodeAndName = productCode != null && !productCode.isBlank()
                && productName != null && !productName.isBlank();
        return hasProductId || hasProductCodeAndName;
    }
}
