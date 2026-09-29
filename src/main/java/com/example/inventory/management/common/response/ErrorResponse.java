package com.example.inventory.management.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "에러 응답")
public record ErrorResponse(
        @Schema(description = "에러 코드", example = "INSUFFICIENT_STOCK") String code,
        @Schema(description = "에러 메시지", example = "재고가 부족합니다. productId=1, requestedQuantity=5") String message,
        @Schema(description = "발생 시각 (UTC)", example = "2026-09-23T14:37:42.931304Z") Instant timestamp
) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
