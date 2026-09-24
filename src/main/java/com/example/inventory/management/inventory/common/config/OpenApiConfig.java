package com.example.inventory.management.inventory.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI at
 * {@code /swagger-ui/index.html}. Rules shared by every API (request format, idempotency,
 * error body and codes) live in the document description; per-API details are on
 * {@code StockApi} / {@code ProductApi}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String DESCRIPTION = """
            상품 **입고·출고**와 **상품 목록·상세, 현재 재고·재고 변경 이력 조회** API입니다.

            | API | 설명 |
            |:---|:---|
            | `POST /api/v1/stocks/inbound` | 입고 (미등록 상품은 신규 등록 후 입고) |
            | `POST /api/v1/stocks/outbound` | 출고 |
            | `GET /api/v1/products` | 상품 목록 (`productCode` 정확 일치 필터로 코드 → `productId` 조회 가능) |
            | `GET /api/v1/products/{productId}` | 상품 상세 |
            | `GET /api/v1/products/{productId}/stock` | 현재 재고 |
            | `GET /api/v1/products/{productId}/stock-histories` | 재고 변경 이력 |

            ---

            ### 1. 요청 필드 규칙 (입고/출고)

            | 필드 | 형식 | 비고 |
            |:---|:---|:---|
            | `productCode` | 영문 대문자·숫자 `^[A-Z0-9]+$`, 최대 64자 | 소문자·공백·기호를 변환하지 않고 거부 |
            | `requestId` | UUID `8-4-4-4-12` (36자, 버전 무관) | 대소문자 무관 — 소문자로 정규화해 같은 키로 취급 |
            | `quantity` | 정수 `1` ~ `10,000` | `1.9`, `2.0` 같은 소수는 잘라내지 않고 거부 |
            | `productId` | 정수 | 서버가 발급한 상품 ID |

            - 상품 목록 조회의 `productCode` 쿼리 파라미터도 같은 형식 규칙을 따릅니다.
            - 정수 필드에 문자열(`"5"`)을 보내거나 정의되지 않은 필드가 있으면 거부합니다.
            - 위반 시 `400 VALIDATION_FAILED`입니다. 단, `quantity`가 10,000을 넘기만 한 경우는 `409 QUANTITY_LIMIT_EXCEEDED`입니다 (다른 오류와 겹치면 400 우선).

            ### 2. 중복 요청 (`requestId`)

            | 상황 | 결과 |
            |:---|:---|
            | 처음으로 성공한 요청 | 정상 처리 |
            | 이미 성공한 `requestId`로 재요청 (동시 요청 포함, 요청 내용 무관) | `409 DUPLICATE_REQUEST` — 최초 처리 결과는 포함하지 않음 |
            | 실패했던(400/404/409/503) `requestId`로 재요청 | 정상 처리 — 실패한 요청은 저장되지 않음 |

            ### 3. 검사 순서

            **① 요청 검증** `400` → **② 중복 `requestId`** `409` → **③ 비즈니스 검사** `404` / `409` / `400`

            > 이미 성공한 출고를 다시 보내면, 그 사이 재고가 부족해졌더라도 `INSUFFICIENT_STOCK`이 아닌 `DUPLICATE_REQUEST`입니다.

            ### 4. 에러 응답

            모든 에러는 같은 형식입니다.

            ```json
            {
              "code": "INSUFFICIENT_STOCK",
              "message": "재고가 부족합니다. productId=1, requestedQuantity=5",
              "timestamp": "2026-09-23T14:37:42.931304Z"
            }
            ```

            | HTTP | code | 상황 |
            |:---:|:---|:---|
            | 400 | `VALIDATION_FAILED` | 요청 값 오류 — 필수값 누락, 형식 오류, JSON 오류, 본문 누락, 경로 변수 타입 오류, 범위를 벗어난 `page`·`size` 등 |
            | 400 | `PRODUCT_CODE_MISMATCH` | `productCode`가 `productId` 상품의 코드와 다름 |
            | 404 | `PRODUCT_NOT_FOUND` | 상품 없음 |
            | 404 | `NOT_FOUND` | 존재하지 않는 API 경로 |
            | 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 (`Allow` 헤더 포함) |
            | 406 | — | 응답할 수 없는 `Accept` (본문 없음) |
            | 409 | `INSUFFICIENT_STOCK` | 재고 부족 |
            | 409 | `DUPLICATE_REQUEST` | 이미 성공 처리된 `requestId` |
            | 409 | `PRODUCT_CODE_ALREADY_EXISTS` | 신규 상품 등록 입고인데 `productCode`가 이미 등록됨 |
            | 409 | `QUANTITY_LIMIT_EXCEEDED` | 1회 요청 `quantity`가 10,000 초과 |
            | 409 | `STOCK_QUANTITY_OVERFLOW` | 입고 결과 재고가 저장 가능한 최대값(BIGINT) 초과 |
            | 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 `Content-Type` (예: `text/plain`, 누락) |
            | 500 | `INTERNAL_ERROR` | 서버 오류 |
            | 503 | `STOCK_LOCK_TIMEOUT` | 락 대기·쿼리 실행·트랜잭션 시간 초과 (`Retry-After: 1`) |

            - **503**은 롤백되어 반영되지 않은 상태이므로 같은 `requestId`로 안전하게 재시도할 수 있습니다.
            - Spring MVC 표준 예외는 원래 상태 코드를 유지하며, `code`는 HTTP 상태 이름(`NOT_FOUND`, `METHOD_NOT_ALLOWED` 등)입니다.
            """;

    @Bean
    OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Inventory Management API")
                .version("v1")
                .description(DESCRIPTION));
    }
}
