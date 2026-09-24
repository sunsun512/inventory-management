# 프로젝트
- Inventory Management API

# 기능 요구사항
- ### 입고
  - 상품의 현재 재고 수량을 증가시킵니다.
  - 등록되지 않은 상품일 경우 신규 상품으로 등록 후 입고 처리합니다.

- ### 출고
  - 상품의 현재 재고 수량을 감소시킵니다.
  - 상품의 재고 수량은 음수가 될 수 없습니다.

- ### 재고
  - 상품의 현재 재고 수량을 확인합니다. 
  - 재고 변경 이력 조회

# 작업목록
Develop
- IM1 - 규모 추정
- IM2 - 요구사항 작성
- IM3 - API 서버 구현
- IM4 - 데이터베이스 연동
- IM5 - 문서화
- IM6 - 고가용성을 위한 설계

# 실행 방법

## 요구사항
- Java 17
- Docker (로컬 Postgres 구동 및 테스트용 Testcontainers 실행에 필요)

## 로컬 Postgres 준비
```bash
docker run -d --name inventory-postgres \
  -e POSTGRES_DB=inventory \
  -e POSTGRES_USER=inventory-user \
  -e POSTGRES_PASSWORD=inventory-password \
  -p 5432:5432 postgres:16-alpine
```

## 애플리케이션 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```
`application.yml`의 `spring.datasource.*`는 환경변수 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`로 덮어쓸 수 있습니다 (기본값은 위 로컬 Postgres 설정과 일치). 애플리케이션 기동 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 자동 적용합니다.

## 테스트 실행
```bash
./gradlew test
```
모든 테스트는 Testcontainers로 띄운 실제 Postgres에 대해 실행되며(H2 등 인메모리 DB 미사용), 별도의 로컬 Postgres 없이도 동작합니다.

# API 명세

| Method | Path | 설명|
|---|---|---|
| POST | `/api/v1/stocks/inbound` | 입고 |
| POST | `/api/v1/stocks/outbound` | 출고 |
| GET | `/api/v1/products/{productId}/stock` | 현재 재고 조회 |
| GET | `/api/v1/products/{productId}/stock-histories` | 재고 변경 이력 조회 (페이징, 최신순) |

### POST /api/v1/stocks/inbound
```json
{
  "productId": 1,
  "productCode": "SKU-1",
  "productName": "상품 A",
  "quantity": 10,
  "requestId": "01K..."
}
```
- 기존 상품에 입고할 경우 `productId`를, 신규 상품을 등록하며 입고할 경우 `productCode`와 `productName`을 전달합니다 (`productId`가 없으면 두 필드가 모두 필요).
- `requestId`는 클라이언트가 생성하는 멱등성 키입니다. 동일한 `requestId`로 재요청하면 재고를 다시 반영하지 않고 최초 처리 결과를 그대로 반환합니다.

### POST /api/v1/stocks/outbound
```json
{
  "productId": 1,
  "quantity": 5,
  "requestId": "01K..."
}
```

### 응답 (입고/출고 공통)
```json
{
  "productId": 1,
  "productCode": "SKU-1",
  "type": "INBOUND",
  "quantity": 10,
  "beforeQuantity": 0,
  "afterQuantity": 10,
  "requestId": "01K...",
  "createdAt": "2026-09-23T14:37:35.511802Z"
}
```

### 에러 응답
```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "재고가 부족합니다. productId=1",
  "timestamp": "2026-09-23T14:37:42.931304Z"
}
```

| 상황 | HTTP | code |
|---|---|---|
| `quantity` 등 요청 값이 유효하지 않음 | 400 | `VALIDATION_FAILED` |
| 상품 없음 | 404 | `PRODUCT_NOT_FOUND` |
| 재고 부족 | 409 | `INSUFFICIENT_STOCK` |
| 동일 `requestId` 재요청 | 200 (최초 처리 결과 그대로 반환) | - |
| 서버 오류 | 500 | `INTERNAL_ERROR` |

# 설계

## 아키텍처
Product / Stock / History를 별도 서비스로 나누지 않고 단일 Spring Boot 애플리케이션 + 단일 PostgreSQL로 구성했습니다. 재고 변경에는 트랜잭션 정합성이 핵심인데, 서비스를 분리하면 단순한 DB 트랜잭션으로 해결되던 문제가 분산 트랜잭션·최종적 일관성 문제로 확대되기 때문입니다. 향후 트래픽/도메인 규모가 커지면 분리를 재검토할 수 있습니다.

패키지는 계층형이 아닌 도메인 기준으로 구성했습니다 (`product`, `stock`, `common`).

## 데이터 모델
- `product`: 현재 재고 상태 (`quantity`), 상품 식별을 위한 `product_code`(UNIQUE, 비즈니스 키). 이름은 "아이폰 17" vs "iPhone 17"처럼 신뢰할 수 없어 식별자로 사용하지 않습니다.
- `stock_history`: 재고 변경 이력 (`before_quantity`, `after_quantity`, `type`, `request_id`). "현재 재고가 왜 이 값인가"를 설명하는 감사 추적(audit trail) 역할입니다.

## 재고 정합성 / 동시성 제어
`SELECT` 후 애플리케이션에서 계산하여 `UPDATE`하는 방식(Lost Update 위험)을 쓰지 않고, PostgreSQL의 원자적 조건부 `UPDATE ... RETURNING`을 사용합니다.
```sql
UPDATE product SET quantity = quantity - :quantity
WHERE id = :id AND quantity >= :quantity
RETURNING quantity
```
조건을 만족하지 못하면(재고 부족) 0건이 반영되어 애플리케이션은 이를 감지해 `InsufficientStockException`을 반환합니다. `RETURNING`으로 변경 후 수량을 원자적으로 함께 받아오므로, 재고 이력에 기록되는 `before/after` 값이 별도의 재조회 없이 항상 실제 커밋된 값과 일치합니다.

신규 상품 등록 + 입고는 `INSERT ... ON CONFLICT (product_code) DO UPDATE`로 하나의 원자적 문장으로 처리하여, 동일한 신규 상품에 대한 동시 등록 경쟁도 DB 레벨에서 해결합니다.

방어는 여러 단계로 둡니다: 요청 검증(`quantity > 0`) → 서비스 로직 → 원자적 SQL → DB `CHECK (quantity >= 0)` 제약(애플리케이션 버그가 있어도 음수 재고가 저장되지 않는 최후 방어선).

이 방식을 `SELECT ... FOR UPDATE` 비관적 락 대신 선택한 이유는, 한 번의 왕복으로 처리되고 락 대기 체인이 생기지 않기 때문입니다. 다중 서버 환경에서도 PostgreSQL 자체가 유일한 정합성 소스이므로 Redis 분산 락은 도입하지 않았습니다.

## 멱등성
`stock_history.request_id`에 UNIQUE 제약을 두어, 동일한 `requestId`로 재요청이 오면 재고를 다시 반영하지 않고 최초 처리 결과를 그대로 반환합니다(409가 아닌 200). 네트워크 재시도 등으로 인한 중복 요청은 비즈니스 충돌이 아니라 "같은 요청"이기 때문입니다.

## 트랜잭션 경계
재고 수량 변경과 이력 저장은 하나의 트랜잭션에서 처리됩니다. 이력 저장이 실패하면 수량 변경도 함께 롤백되어, "재고는 바뀌었는데 이력이 없는" 정합성 불일치를 방지합니다.

## 테스트
- Testcontainers로 실제 PostgreSQL에 대해 테스트를 실행합니다 (H2 등 인메모리 DB로는 원자적 `UPDATE`/락/`ON CONFLICT` 동작을 정확히 재현할 수 없기 때문).
- `StockServiceConcurrencyTest`: 재고 100개에서 10개씩 10스레드가 동시에 출고하면 전량 성공하고 최종 재고가 0이 되는지, 20스레드가 동시에 출고하면 정확히 10개만 성공(나머지는 재고 부족)하고 최종 재고가 0이 되는지 검증합니다.

## 가정 및 향후 확장 여지
- 재고 단위는 항상 정수("개")라고 가정하여 `quantity`를 `BIGINT`/`Long`으로 설계했습니다. kg, m 등 소수 단위 재고가 필요해지면 `NUMERIC`으로의 스키마 변경이 필요합니다.
- 상품 삭제 기능은 요구사항에 없어 설계하지 않았습니다.
