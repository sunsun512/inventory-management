package com.example.inventory.management.inventory.stock.dto;

import com.example.inventory.management.inventory.stock.validation.QuantityLimit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OutboundRequest(
        @NotNull Long productId,

        @NotNull @Positive @QuantityLimit Long quantity,

        @NotBlank @Size(max = 64) String requestId
) {
}
