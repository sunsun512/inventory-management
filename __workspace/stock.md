# stock 패키지

## 소유 범위
입고·출고(재고 변경)와 재고 변경 이력(`stock_history`)의 저장·조회를 소유합니다. 재고 수량 자체는 `product.quantity`에 있지만, 그 값을 바꾸는 흐름은 이 패키지가 담당합니다.
- `api/` — `StockController`(`POST /api/v1/stocks/inbound`, `/outbound`), OpenAPI 문서는 `StockApi` 인터페이스
- `command/` — `StockCommandService`(중복 사전 검사, unique 위반 변환), `StockMutationExecutor`(실제 트랜잭션), `dto/`(요청·응답), `validation/`(`@QuantityLimit`, `@RequestIdFormat`)
- `query/` — `StockHistoryQueryService`, `StockHistoryQueryRepository`(QueryDSL), `dto/StockHistoryResponse`. 이력 조회 엔드포인트 자체는 `product.api.ProductController`에 있습니다.
- `domain/` — `StockHistory` 엔티티, `StockHistoryRepository`(advisory lock 포함), `StockType`

## 핵심 파일
- `src/main/java/com/example/inventory/management/stock/command/StockMutationExecutor.java` — 입고·출고 트랜잭션 본체
- `src/main/java/com/example/inventory/management/stock/command/StockCommandService.java` — 트랜잭션 밖에서 중복 검사·unique 위반 → 409 변환
- `src/main/java/com/example/inventory/management/stock/command/dto/InboundRequest.java` / `OutboundRequest.java` — 요청 검증, `requestId` 소문자 정규화
- `src/main/java/com/example/inventory/management/stock/domain/StockHistoryRepository.java` — `acquireRequestIdLock`(`pg_advisory_xact_lock`)
- `src/main/java/com/example/inventory/management/stock/query/StockHistoryQueryRepository.java` — 이력 목록 projection·정렬
- `src/main/resources/db/migration/V2__create_stock_history_table.sql`, `V3__replace_stock_history_indexes.sql` — 스키마·인덱스

## 수정 패턴
**입고/출고 요청에 필드 추가**
1. `InboundRequest`/`OutboundRequest`에 필드 + Bean Validation 추가(수량 한도처럼 409가 필요하면 `common.md`의 에러 코드 패턴 참고).
2. `StockMutationExecutor.applyInbound`/`applyOutbound`에서 사용하고, 이력에 남길 값이면 `StockHistory` 생성자·필드에 추가.
3. 새 마이그레이션 `V<n>__...sql`로 `stock_history` 컬럼 추가(기존 파일 수정 금지).
4. 응답에 노출하면 `StockChangeResponse`, 이력 조회에 노출하면 `StockHistoryResponse`와 `StockHistoryQueryRepository`의 `Projections.constructor` 인자 순서를 함께 수정.
5. `stock_history`에 직접 INSERT하는 곳을 확인: `src/main/resources/data.sql`, `ProductControllerIntegrationTest`, `StockHistoryQueryRepositoryTest`, `StockHistoryQueryServiceTest`. NOT NULL 컬럼이면 DEFAULT를 주거나 이들을 수정.
6. 테스트: `StockControllerIntegrationTest`(검증·응답), `StockCommandServiceTest`, `StockHistorySchemaMigrationTest`(스키마).

**이력 조회 필터/정렬 변경**
1. `StockHistoryQueryRepository.findByProductId`의 `where`/`orderBy` 수정. 정렬 변경 시 `id` 동점 정렬 키를 유지.
2. 인덱스 `(product_id, created_at DESC, id DESC)`와 맞지 않으면 새 마이그레이션으로 인덱스 교체(V3처럼 `CONCURRENTLY`). `StockHistorySchemaMigrationTest`가 인덱스와 실행 계획을 검증합니다.

## 실패 함정
- **`requestId`는 "처음 성공한 요청 하나"만 처리**: 이후 같은 키는 요청 내용과 무관하게 409 `DUPLICATE_REQUEST`이고 원래 결과를 돌려주지 않습니다. 실패한 요청은 저장되지 않아 같은 키로 재시도 가능합니다(`StockCommandService` 클래스 주석).
- **중복 검사는 두 번**: 트랜잭션 전 `existsByRequestId`는 최적화일 뿐이고, 권위 있는 검사는 `claimRequestId`(advisory lock 획득 후 재확인)입니다. 락 순서는 항상 advisory lock → 상품 행이므로, 새 로직에서 상품 행을 먼저 잠그지 마세요.
- **`StockMutationExecutor`를 별도 빈으로 유지**: `StockCommandService`가 트랜잭션 프록시 밖에 있어야 롤백 후 `DataIntegrityViolationException`을 409로 바꿀 수 있습니다. `@Transactional`을 `StockCommandService`로 옮기지 마세요.
- **unique 위반 복구는 제약 이름으로 판별**: `uk_stock_history_request_id` → `DUPLICATE_REQUEST`, `uk_product_product_code` → `PRODUCT_CODE_ALREADY_EXISTS`, 그 외는 그대로 던져 500. 제약 이름을 바꾸는 마이그레이션은 `StockCommandService`의 상수도 바꿔야 합니다.
- **출고는 `WHERE quantity >= :quantity`로 막음**: 빈 결과 → 409 `INSUFFICIENT_STOCK`. DB `CHECK (quantity >= 0)`는 최후 방어선이며, 그게 발동하면 409가 아니라 500입니다.
- **입고 BIGINT 초과(22003)** → 409 `STOCK_QUANTITY_OVERFLOW`(`translateOverflow`).
- **신규 상품 입고(`productId` 없음)는 기존 코드에 재고를 더하지 않음**: `ON CONFLICT DO NOTHING` → 409 `PRODUCT_CODE_ALREADY_EXISTS`. 기존 상품은 `productId` + 정확히 일치하는 `productCode` 필요(불일치 400).
- **시각은 애플리케이션 시계 한 번**: `now()`를 마이크로초로 잘라 상품 `updated_at`, 이력 `created_at`, 응답에 같은 값을 씁니다. DB `now()`를 쓰지 마세요.
- **락 대기 초과(55P03)·쿼리 초과(57014)** → 503 `STOCK_LOCK_TIMEOUT`, 롤백되었으므로 같은 `requestId`로 재시도 안전. advisory lock 대기에도 `lock_timeout`이 적용됩니다.
- **서비스에서 실패를 로그로 남기지 않음**: 예외만 던지고, 로그는 `GlobalExceptionHandler`가 한 번 남깁니다.
- **마이그레이션**: 커밋된 파일 수정 금지, `DROP`/데이터 삭제는 사용자 확인 필수(`.claude/rules/db-migration.md`). `CONCURRENTLY` 파일에는 `CONCURRENTLY` 구문만 두고, 실패 시 INVALID 인덱스를 지우고 `flyway repair` 후 재실행(V3 주석).

## 의존성
- stock → common: `common.exception`(예외들, `SqlStates`), `common.response`(`ErrorResponse`, `PageResponse`), `common.validation`(`@ProductCodeFormat`)
- stock → product: `stock.command.StockMutationExecutor` → `product.domain.Product`/`ProductRepository`, `stock.query.StockHistoryQueryService` → `product.domain.ProductRepository`
- 내부: `stock.api` → `stock.command`만 사용. `command`/`query`는 서로 참조하지 않습니다.
- stock에 의존하는 쪽: `product.api.ProductController`/`ProductApi` → `stock.query.StockHistoryQueryService`, `stock.query.dto.StockHistoryResponse`; `common.exception.GlobalExceptionHandler` → `stock.command.validation.QuantityLimit`

## 배경·이유
- 중복 방지는 `pg_advisory_xact_lock` + 트랜잭션 내 재확인으로 구현하고, 성공한 요청만 저장합니다(커밋 7337a79). 같은 커밋에서 신규 상품 입고의 업서트를 없앴습니다.
- 수량 한도를 `@Max` 대신 합성 제약 `@QuantityLimit`로 둔 이유: 제약 코드로 409 `QUANTITY_LIMIT_EXCEEDED`를 골라내되 다른 `@Max`는 400으로 남기기 위해서입니다.
- 이력 조회를 `StockHistoryQueryService`로 분리해 stock ↔ product 서비스 간 순환 의존을 없앴습니다(커밋 a5cbe90).
- V3는 조회 정렬(`created_at DESC, id DESC`)과 같은 순서의 인덱스로 Incremental Sort를 없애고, 쓰이지 않던 인덱스를 지웠습니다(커밋 6be8f2e).
