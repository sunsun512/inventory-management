package com.example.inventory.management.stock.api;

import com.example.inventory.management.common.response.ErrorResponse;
import com.example.inventory.management.stock.command.dto.InboundRequest;
import com.example.inventory.management.stock.command.dto.OutboundRequest;
import com.example.inventory.management.stock.command.dto.StockChangeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI documentation for {@link StockController}, kept apart so the controller stays readable.
 */
@Tag(name = "재고 입출고", description = "상품 재고 입고/출고")
interface StockApi {

    @Operation(
            summary = "입고",
            description = """
                    기존 상품에 입고하거나, 등록되지 않은 상품을 신규 등록하며 입고합니다. `productId` 유무로 구분합니다.

                    | 필드 | 기존 상품 입고 | 신규 상품 등록 입고 |
                    |---|---|---|
                    | `productId` | **필수** | 보내지 않음 |
                    | `productCode` | **필수** | **필수** |
                    | `productName` | 선택 (무시됨) | **필수** (1~255자) |
                    | `quantity` | **필수** | **필수** |
                    | `requestId` | **필수** | **필수** |

                    - **기존 상품 입고**: `productId`의 상품이 없으면 404 `PRODUCT_NOT_FOUND`, `productCode`가 그 상품의 코드와 \
                    **정확히** 일치하지 않으면 400 `PRODUCT_CODE_MISMATCH`이며 재고는 변경되지 않습니다. \
                    `productName`은 보내도 무시되며 상품명을 바꾸지 않습니다.
                    - **신규 상품 등록 입고**: 없는 `productCode`면 해당 코드·이름으로 상품을 등록하고 입고합니다(`beforeQuantity` = 0). \
                    이미 등록된 코드면 기존 상품에 재고를 더하지 않고 409 `PRODUCT_CODE_ALREADY_EXISTS`로 거부합니다. \
                    같은 신규 코드로 동시에 등록하면 정확히 한 건만 성공합니다.
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "기존 상품 입고",
                    value = "{\"productId\": 1, \"productCode\": \"SKU1\", \"quantity\": 10, \"requestId\": \"3f1c2a9e-8b7d-4e21-9c3a-6d5e4f3b2a10\"}"),
            @ExampleObject(name = "신규 상품 등록 입고",
                    value = "{\"productCode\": \"SKU2\", \"productName\": \"상품 B\", \"quantity\": 10, \"requestId\": \"0b8e7d6c-5a4f-4e3d-8c2b-1a0f9e8d7c6b\"}")
    }))
    @ApiResponse(responseCode = "200", description = "입고 완료")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` / `PRODUCT_CODE_MISMATCH`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "`PRODUCT_NOT_FOUND`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "`DUPLICATE_REQUEST` / `PRODUCT_CODE_ALREADY_EXISTS` / `QUANTITY_LIMIT_EXCEEDED` / `STOCK_QUANTITY_OVERFLOW`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "`TOO_MANY_REQUESTS` — 클라이언트 IP별 요청 한도 초과. 처리되지 않았으므로 Retry-After 후 재시도",
            headers = @Header(name = "Retry-After", description = "재시도까지 대기할 초", schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "`STOCK_LOCK_TIMEOUT` — 반영되지 않았으므로 같은 requestId로 재시도",
            headers = @Header(name = "Retry-After", description = "재시도까지 대기할 초", schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    StockChangeResponse inbound(InboundRequest request);

    @Operation(
            summary = "출고",
            description = """
                    상품의 현재 재고 수량을 감소시킵니다. 재고는 음수가 될 수 없습니다.

                    - `productName` 등 정의되지 않은 필드는 400 `VALIDATION_FAILED`입니다.
                    - `productCode`가 상품과 일치하지 않으면 400 `PRODUCT_CODE_MISMATCH`, 상품이 없으면 404 `PRODUCT_NOT_FOUND`, \
                    재고가 부족하면 409 `INSUFFICIENT_STOCK`입니다.
                    """)
    @ApiResponse(responseCode = "200", description = "출고 완료")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` / `PRODUCT_CODE_MISMATCH`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "`PRODUCT_NOT_FOUND`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "`DUPLICATE_REQUEST` / `INSUFFICIENT_STOCK` / `QUANTITY_LIMIT_EXCEEDED`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "`TOO_MANY_REQUESTS` — 클라이언트 IP별 요청 한도 초과. 처리되지 않았으므로 Retry-After 후 재시도",
            headers = @Header(name = "Retry-After", description = "재시도까지 대기할 초", schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "`STOCK_LOCK_TIMEOUT` — 반영되지 않았으므로 같은 requestId로 재시도",
            headers = @Header(name = "Retry-After", description = "재시도까지 대기할 초", schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    StockChangeResponse outbound(OutboundRequest request);
}
