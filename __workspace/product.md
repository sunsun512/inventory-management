# product 패키지

## 소유 범위
상품(`product` 테이블)과 상품 조회 API를 소유합니다. 재고 수량 컬럼(`product.quantity`)과 그 값을 바꾸는 원자적 SQL도 여기(`domain/`)에 있지만, 호출하는 쪽은 `stock.command`입니다. 이 패키지에는 `command/`가 없습니다.
- `api/` — `ProductController`(`GET /api/v1/products`, `/{productId}`, `/{productId}/stock`, `/{productId}/stock-histories`), OpenAPI 문서·파라미터 제약은 `ProductApi` 인터페이스
- `query/` — `ProductQueryService`(목록·상세·현재 재고), `ProductQueryRepository`(QueryDSL 목록), `dto/`(`ProductSummaryResponse`, `ProductDetailResponse`, `StockQuantityResponse`)
- `domain/` — `Product` 엔티티(읽기 전용 getter만), `ProductRepository`(네이티브 쓰기 SQL 포함)

## 핵심 파일
- `src/main/java/com/example/inventory/management/product/domain/ProductRepository.java` — `decreaseQuantityIfSufficient`, `increaseQuantity`, `insertProductIfAbsent`
- `src/main/java/com/example/inventory/management/product/api/ProductApi.java` — Swagger 설명 + `@Min`/`@ProductCodeFormat` 등 요청 파라미터 제약
- `src/main/java/com/example/inventory/management/product/query/ProductQueryRepository.java` — 목록 조회(필터·정렬·페이징)
- `src/main/java/com/example/inventory/management/product/query/ProductQueryService.java` — `size` 상한 적용, 404 처리
- `src/main/resources/db/migration/V1__create_product_table.sql` — `uk_product_product_code`, `ck_product_quantity_non_negative`

## 수정 패턴
**상품 목록에 조회 필터 추가**
1. 파라미터를 `ProductApi.getProducts`(제약 포함)와 `ProductController.getProducts`(`@RequestParam`)에 추가.
2. `ProductQueryService.getProducts` → `ProductQueryRepository.findSummaries`로 전달하고, `productCodeEq`처럼 `null`이면 `null`을 돌려주는 `BooleanExpression` 메서드를 추가해 `where`에 넣음.
3. 필터 컬럼에 인덱스가 필요하면 새 마이그레이션으로 추가(`product_code`는 unique 인덱스가 이미 있음).
4. 테스트: `ProductQueryRepositoryTest`, `ProductControllerIntegrationTest`(400 검증 포함).

**상품 응답에 필드 추가**
1. 컬럼이면 새 마이그레이션 → `Product` 필드·getter.
2. `ProductDetailResponse.from`, 또는 목록이면 `ProductSummaryResponse`와 `ProductQueryRepository`의 `Projections.constructor` 인자 순서를 함께 수정.
3. `product`에 NOT NULL 컬럼을 DEFAULT 없이 추가하면 `data.sql`, `ProductFixtures.insertProduct`, 테스트의 직접 INSERT, `ProductRepository.insertProductIfAbsent`가 모두 깨집니다.

## 실패 함정
- **`ProductRepository`의 쓰기 쿼리에 `@Modifying`을 붙이지 않음**: `@Modifying`은 void/int/long만 반환(`executeUpdate()`)해서 `RETURNING` 값을 받을 수 없습니다. 붙이지 않으면 `getResultList()`로 읽고, Postgres가 `RETURNING`으로 결과 집합을 돌려줍니다(메서드 주석).
- **재고 증감은 반드시 원자적 SQL로**: 읽고-계산하고-저장하지 않습니다. 감소는 `WHERE quantity >= :quantity` 조건부 `UPDATE ... RETURNING`이며, 빈 결과가 "재고 부족"입니다. `Product`에는 setter가 없습니다.
- **`CHECK (quantity >= 0)`는 최후 방어선**: 위반(23514)은 `DataIntegrityViolationException` → `GlobalExceptionHandler`에서 500이 됩니다. 409를 원하면 `WHERE` 조건으로 먼저 막아야 합니다.
- **시각은 호출자가 넘김**: 쓰기 쿼리는 `:now` 파라미터를 받습니다(DB `now()` 금지). 상품과 이력에 같은 시각을 찍기 위해서입니다.
- **`insertProductIfAbsent`는 기존 상품에 재고를 더하지 않음**: `ON CONFLICT (product_code) DO NOTHING` → 빈 결과. 호출자가 409로 바꿉니다.
- **파라미터 제약은 `ProductApi`에만**: Bean Validation은 인터페이스 메서드에 선언된 제약을 구현 메서드에서 다시 선언하는 것을 허용하지 않습니다(`ProductApi` 클래스 주석).
- **페이지 크기**: `size`가 `PageResponse.MAX_SIZE`(100)를 넘으면 조용히 100으로 줄입니다. 전체 건수(count) 쿼리는 없습니다.
- **`productCode` 필터는 정확 일치**: 대소문자 무시·부분 일치 없음. 형식(`^[A-Z0-9]+$`, 최대 64자)은 입고·출고와 같은 `common.validation.ProductCodeFormat`으로 검증하며, 어기면 400.
- 테스트에서 상품은 `support/ProductFixtures`로 SQL 직접 삽입합니다(운영 등록 경로는 기존 코드를 거부하기 때문).

## 의존성
- product → common: `common.response`(`PageResponse`, `ErrorResponse`), `common.exception.ProductNotFoundException`, `common.validation.ProductCodeFormat`
- product → stock: `product.api.ProductController` → `stock.query.StockHistoryQueryService`, `ProductController`/`ProductApi` → `stock.query.dto.StockHistoryResponse` (재고 이력 조회 엔드포인트)
- 내부: `product.api` → `product.query` → `product.domain`
- product에 의존하는 쪽: `stock.command.StockMutationExecutor` → `product.domain.Product`/`ProductRepository`, `stock.query.StockHistoryQueryService` → `product.domain.ProductRepository`
- QueryDSL Q타입(`QProduct`)은 빌드 시 annotation processor가 생성하며 git에 포함하지 않습니다(`build.gradle`).

## 배경·이유
- 상품 식별 정책: 기존 상품은 `productId` + `productCode`를 모두 받고 불일치 시 400, 신규 등록은 업서트 대신 중복 코드를 409로 거부(커밋 7337a79).
- 목록은 `productId` 내림차순(최근 등록 순)이며, 상품 코드만 아는 클라이언트가 `productCode` 필터로 `productId`를 찾도록 합니다(README, `ProductApi`).
- 목록 조회는 엔티티를 로드하지 않고 DTO로 바로 projection하며, `size + 1`건을 읽어 `hasNext`만 판단합니다(커밋 d0c70ee).
