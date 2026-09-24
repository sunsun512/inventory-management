package com.example.inventory.management.product.api;

import com.example.inventory.management.common.response.ErrorResponse;
import com.example.inventory.management.common.response.PageResponse;
import com.example.inventory.management.product.query.dto.ProductDetailResponse;
import com.example.inventory.management.product.query.dto.ProductSummaryResponse;
import com.example.inventory.management.product.query.dto.StockQuantityResponse;
import com.example.inventory.management.stock.query.dto.StockHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * OpenAPI documentation for {@link ProductController}, kept apart so the controller stays readable.
 * Parameter constraints ({@code @Min}) live here too: Bean Validation only allows them on the
 * interface method, not re-declared on the implementing controller method.
 */
@Tag(name = "상품·재고 조회", description = "상품 목록·상세, 현재 재고와 재고 변경 이력 조회")
interface ProductApi {

    @Operation(
            summary = "상품 목록 조회",
            description = """
                    등록된 상품을 최근 등록 순(`productId` 내림차순)으로 페이지 단위로 조회합니다.

                    - `productCode`를 주면 코드가 **정확히 일치**하는 상품만 조회합니다(부분 일치·대소문자 무시 없음). \
                    상품 코드는 유일하므로 결과는 0건 또는 1건이며, 상품 코드만 아는 클라이언트가 `productId`를 찾을 때 사용합니다. \
                    일치하는 상품이 없으면 404가 아닌 빈 `content`를 반환합니다.
                    - `productCode`는 입고/출고와 같은 형식(`^[A-Z0-9]+$`, 최대 64자)이어야 하며, 빈 문자열도 거부합니다.
                    - 정렬은 서버에서 고정하며 `sort` 파라미터는 지원하지 않습니다(보내도 무시).
                    - `page`는 0부터 시작하며 기본값은 0, `size` 기본값은 10입니다.
                    - `size`는 최대 100이며, 더 크게 요청하면 오류 없이 100으로 제한됩니다(응답의 `size`에 실제 적용 값).
                    - 전체 건수는 제공하지 않습니다. 다음 페이지가 있는지는 `hasNext`로 확인하세요.
                    """)
    @ApiResponse(responseCode = "200", description = "조회 성공 (일치하는 상품이 없으면 빈 `content`)")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — productCode 형식 오류, page < 0, size < 1, 정수가 아닌 page/size",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    PageResponse<ProductSummaryResponse> getProducts(
            @Parameter(description = "상품 코드 정확 일치 필터 (선택, `^[A-Z0-9]+$`, 최대 64자)", example = "SKU1")
            @Size(max = 64) @Pattern(regexp = "^[A-Z0-9]+$", message = "productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")
            String productCode,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
            @Parameter(description = "페이지 크기 (1 이상, 100 초과 시 100으로 제한)", example = "10") @Min(value = 1, message = "1 이상이어야 합니다.") int size);

    @Operation(summary = "상품 상세 조회", description = "상품 정보와 현재 재고, 등록 시각(`createdAt`)·마지막 재고 변경 시각(`updatedAt`)을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — productId가 정수가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "`PRODUCT_NOT_FOUND`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ProductDetailResponse getProduct(@Parameter(description = "상품 ID", example = "1") Long productId);

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
                    최신 이력부터 페이지 단위로 조회합니다.

                    - 정렬은 서버에서 `createdAt` 내림차순, 같은 시각이면 `id` 내림차순으로 고정합니다. \
                    `sort` 파라미터는 지원하지 않으며 보내도 무시됩니다.
                    - `page`는 0부터 시작하며 기본값은 0, `size` 기본값은 10입니다.
                    - `size`는 최대 100이며, 더 크게 요청하면 오류 없이 100으로 제한됩니다(응답의 `size`에 실제 적용 값).
                    - 전체 건수는 제공하지 않습니다. 다음 페이지가 있는지는 `hasNext`로 확인하세요. \
                    마지막 페이지를 넘으면 빈 `content`와 `hasNext: false`를 반환합니다.
                    - `createdAt`은 애플리케이션 서버 시각이라 서버 간 시계 차이로 실제 반영 순서와 약간 다를 수 있습니다.
                    """)
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — productId 타입 오류, page < 0, size < 1, 정수가 아닌 page/size",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "`PRODUCT_NOT_FOUND`",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    PageResponse<StockHistoryResponse> getStockHistories(
            @Parameter(description = "상품 ID", example = "1") Long productId,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
            @Parameter(description = "페이지 크기 (1 이상, 100 초과 시 100으로 제한)", example = "10") @Min(value = 1, message = "1 이상이어야 합니다.") int size);
}
