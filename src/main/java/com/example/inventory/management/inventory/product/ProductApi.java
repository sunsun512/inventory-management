package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.common.response.ErrorResponse;
import com.example.inventory.management.inventory.product.dto.StockQuantityResponse;
import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * OpenAPI documentation for {@link ProductController}, kept apart so the controller stays readable.
 */
@Tag(name = "재고 조회", description = "상품 현재 재고와 재고 변경 이력 조회")
interface ProductApi {

    @Operation(summary = "현재 재고 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — productId가 정수가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "`PRODUCT_NOT_FOUND`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    StockQuantityResponse getStock(@Parameter(description = "상품 ID", example = "1") Long productId);

    @Operation(
            summary = "재고 변경 이력 조회",
            description = """
                    페이징 조회입니다. 정렬은 서버에서 `id` 역순(실제 반영 역순)으로 고정되며 기본 `size`는 20입니다.

                    - 정렬 조건은 받지 않습니다. `sort` 파라미터를 보내도 무시됩니다.
                    - `size`는 최대 100이며, 더 크게 요청하면 오류 없이 100으로 제한됩니다.
                    - `createdAt`은 애플리케이션 서버 시각이라 서버 간 시계 차이로 `id` 순서와 다를 수 있습니다.
                    """)
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "페이지 번호 (0부터 시작)",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0")),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "페이지 크기 (최대 100)",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
    })
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — productId 타입 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "`PRODUCT_NOT_FOUND`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    Page<StockHistoryResponse> getStockHistories(
            @Parameter(description = "상품 ID", example = "1") Long productId,
            @Parameter(hidden = true) Pageable pageable);
}
