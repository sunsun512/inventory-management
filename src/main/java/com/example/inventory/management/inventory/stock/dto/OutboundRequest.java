package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.validation.QuantityLimit;
import com.example.inventory.management.inventory.stock.validation.RequestIdFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record OutboundRequest(
        @NotNull Long productId,

        @NotNull
        @Size(max = 64)
        @Pattern(regexp = "^[A-Z0-9]+$", message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
        String productCode,

        @NotNull @Positive @QuantityLimit Long quantity,

        @NotNull @RequestIdFormat String requestId
) {

    public OutboundRequest {
        requestId = requestId == null ? null : requestId.toLowerCase(Locale.ROOT);
    }
}
