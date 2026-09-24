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
DB 접속 설정(`spring.datasource.*`)은 `local` 프로필 전용 파일인 `application-local.yml`에 있습니다. 접속 URL은 `jdbc:postgresql://localhost:5432/inventory`로 고정되어 있어 `DB_URL` 같은 환경변수로는 바꿀 수 없고, 사용자/비밀번호는 `DB_USERNAME` / `DB_PASSWORD`(기본 `inventory-user` / `inventory-password`), 커넥션 풀 크기는 `DB_POOL_MAX_SIZE`(기본 `10`) / `DB_POOL_MIN_IDLE`(기본 `4`)로 덮어쓸 수 있습니다. DB 세션 타임아웃은 공통 설정 `application.yml`에 있으며 `DB_LOCK_TIMEOUT`(기본 `3s`) / `DB_STATEMENT_TIMEOUT`(기본 `5s`)으로 덮어쓸 수 있습니다 (기본값은 위 로컬 Postgres 설정과 일치). 애플리케이션 기동 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 자동 적용합니다.

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
| GET | `/api/v1/products/{productId}/stock-histories` | 재고 변경 이력 조회 (페이징, 기본 정렬 `id DESC` = 실제 반영 역순, `sort`는 `id`/`createdAt`만 허용, `size` 최대 100) |

### POST /api/v1/stocks/inbound
기존 상품에 입고하거나, 등록되지 않은 상품을 신규 등록하며 입고합니다. `productId` 유무로 구분합니다.

| 필드 | 타입 | 기존 상품 입고 | 신규 상품 등록 입고 | 규칙 |
|---|---|---|---|---|
| `productId` | 정수 | **필수** | 보내지 않음 | DB가 발급한 상품 ID |
| `productCode` | 문자열 | **필수** | **필수** | `^[A-Z0-9]+$`, 최대 64자 |
| `productName` | 문자열 | 선택 (무시됨) | **필수** | 1~255자 |
| `quantity` | 정수 | **필수** | **필수** | 1 이상 10,000 이하 |
| `requestId` | 문자열 | **필수** | **필수** | UUID (아래 공통 규칙) |

```json
{ "productId": 1, "productCode": "SKU1", "quantity": 10, "requestId": "3f1c2a9e-8b7d-4e21-9c3a-6d5e4f3b2a10" }
```
```json
{ "productCode": "SKU2", "productName": "상품 B", "quantity": 10, "requestId": "0b8e7d6c-5a4f-4e3d-8c2b-1a0f9e8d7c6b" }
```
- **기존 상품 입고**: `productId`의 상품이 없으면 404 `PRODUCT_NOT_FOUND`, `productCode`가 그 상품의 코드와 **정확히** 일치하지 않으면 400 `PRODUCT_CODE_MISMATCH`이며 재고는 변경되지 않습니다. `productName`은 보내도 무시되며 상품명을 바꾸지 않습니다.
- **신규 상품 등록 입고**: `productCode`가 없는 코드면 해당 코드·이름으로 상품을 등록하고 수량을 입고합니다(`beforeQuantity` = 0). 이미 등록된 코드면 기존 상품에 재고를 더하지 않고 409 `PRODUCT_CODE_ALREADY_EXISTS`로 거부합니다. 같은 신규 코드로 동시에 등록하면 정확히 한 건만 성공하고 나머지는 409 `PRODUCT_CODE_ALREADY_EXISTS`입니다.

### POST /api/v1/stocks/outbound

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `productId` | 정수 | **필수** | DB가 발급한 상품 ID |
| `productCode` | 문자열 | **필수** | `^[A-Z0-9]+$`, 최대 64자, 해당 상품의 코드와 정확히 일치 |
| `quantity` | 정수 | **필수** | 1 이상 10,000 이하, 현재 재고 이하 |
| `requestId` | 문자열 | **필수** | UUID (아래 공통 규칙) |

```json
{ "productId": 1, "productCode": "SKU1", "quantity": 5, "requestId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d" }
```
- `productName` 등 위 표에 없는 필드는 400 `VALIDATION_FAILED`입니다. `productCode`가 상품과 일치하지 않으면 400 `PRODUCT_CODE_MISMATCH`, 상품이 없으면 404 `PRODUCT_NOT_FOUND`, 재고가 부족하면 409 `INSUFFICIENT_STOCK`입니다.

### 입고/출고 공통 규칙
- **`productCode`**: 영문 대문자와 숫자로만 구성됩니다 (`^[A-Z0-9]+$`, 최대 64자). 소문자·하이픈·공백·기타 기호는 변환(정규화)하지 않고 400 `VALIDATION_FAILED`로 거부합니다.
- **`requestId`**: 클라이언트가 생성하는 표준 UUID 문자열입니다 (8-4-4-4-12 형식의 16진수, 하이픈 포함 36자, 버전 무관). 대소문자 모두 허용하며 조회·저장 전에 소문자로 정규화하므로 대소문자만 다른 값은 같은 키입니다. 그 외 형식(숫자 `123` 포함)은 400 `VALIDATION_FAILED`입니다.
- **`quantity`**: 1 이상 10,000 이하의 **정수**입니다. `1.9`, `2.0` 같은 소수는 잘라내지 않고 400 `VALIDATION_FAILED`로 거부하며, 10,000 초과는 409 `QUANTITY_LIMIT_EXCEEDED`입니다.
- **엄격한 JSON**: 정수 필드(`quantity`, `productId`)에 문자열(`"5"`, `"1"`)을 보내면 변환하지 않고 400 `VALIDATION_FAILED`, 정의되지 않은 필드가 있으면 400 `VALIDATION_FAILED`입니다.
- **중복 요청**: 하나의 `requestId`는 트랜잭션이 처음으로 성공 커밋된 요청 한 건만 처리합니다. 이후의 요청이나 동시에 들어온 요청은 요청 내용과 관계없이 409 `DUPLICATE_REQUEST`를 받으며, 본문에 최초 처리 결과는 포함되지 않습니다. 성공한 요청만 저장되므로 실패한 요청(400/404/409/503)과 같은 `requestId`로 다시 요청하면 정상 처리됩니다.
- **검사 순서**: 요청 검증(400) → 중복 `requestId`(409 `DUPLICATE_REQUEST`) → 비즈니스 검사(404 `PRODUCT_NOT_FOUND` / 409 `INSUFFICIENT_STOCK` / 409 `PRODUCT_CODE_ALREADY_EXISTS` / 400 `PRODUCT_CODE_MISMATCH`). 예를 들어 이미 성공한 출고를 다시 보내면 재고가 부족해졌더라도 `INSUFFICIENT_STOCK`이 아닌 `DUPLICATE_REQUEST`입니다.

### 응답 (입고/출고 공통)
```json
{
  "productId": 1,
  "productCode": "SKU1",
  "type": "INBOUND",
  "quantity": 10,
  "beforeQuantity": 0,
  "afterQuantity": 10,
  "requestId": "3f1c2a9e-8b7d-4e21-9c3a-6d5e4f3b2a10",
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
| 요청 값이 유효하지 않음 (필수값 누락, `quantity` ≤ 0, 소수 `quantity`, 문자열로 보낸 숫자(`"5"`), 정의되지 않은 필드, `productCode` 형식 오류, `requestId` UUID 형식 오류, JSON 형식 오류, 본문 누락, 경로 변수 타입 오류(`/products/abc/stock`), 허용되지 않은 `sort` 속성 등) | 400 | `VALIDATION_FAILED` |
| `productCode`가 `productId` 상품의 코드와 다름 (입고/출고) | 400 | `PRODUCT_CODE_MISMATCH` |
| 상품 없음 | 404 | `PRODUCT_NOT_FOUND` |
| 존재하지 않는 API 경로 | 404 | `NOT_FOUND` |
| 지원하지 않는 HTTP 메서드 | 405 (`Allow` 헤더 포함) | `METHOD_NOT_ALLOWED` |
| 응답할 수 없는 `Accept` (예: `application/xml`) | 406 | - (본문 없음) |
| 지원하지 않는 `Content-Type` (예: `text/plain`, 누락) | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 재고 부족 | 409 | `INSUFFICIENT_STOCK` |
| 이미 성공 처리된 `requestId`로 재요청 (동시 요청 포함, 요청 내용 무관) | 409 (최초 처리 결과 미포함) | `DUPLICATE_REQUEST` |
| 신규 상품 등록 입고(`productId` 없음)인데 `productCode`가 이미 등록됨 | 409 | `PRODUCT_CODE_ALREADY_EXISTS` |
| 1회 요청 `quantity`가 10,000 초과 | 409 | `QUANTITY_LIMIT_EXCEEDED` |
| 입고 결과 재고가 저장 가능한 최대값(BIGINT) 초과 | 409 | `STOCK_QUANTITY_OVERFLOW` |
| 상품 행 락·`requestId` 락 대기(`lock_timeout`) / 쿼리 실행(`statement_timeout`) / 트랜잭션 시간 초과 | 503 (`Retry-After: 1`) | `STOCK_LOCK_TIMEOUT` |
| 서버 오류 | 500 | `INTERNAL_ERROR` |

- 한도 초과(`QUANTITY_LIMIT_EXCEEDED`)와 다른 검증 오류가 함께 있으면 400 `VALIDATION_FAILED`가 우선합니다.
- 503은 요청이 반영되지 않고 롤백된 상태이므로 같은 `requestId`로 안전하게 재시도할 수 있습니다.
- Spring MVC 표준 예외는 원래 상태 코드를 유지하고 본문만 위 형식으로 바꿉니다. `code`는 400이면 `VALIDATION_FAILED`, 500이면 `INTERNAL_ERROR`, 그 밖에는 HTTP 상태 이름(`NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` 등)입니다.

# 설계

## 요청 검증 / 수량 한도
- `quantity`는 정수만 허용합니다. Jackson의 기본 동작(`ACCEPT_FLOAT_AS_INT`)은 `1.9`를 `1`로 조용히 잘라 처리하므로 `spring.jackson.deserialization.accept-float-as-int: false`로 끄고, 본문 해석 실패(`HttpMessageNotReadableException`)는 400으로 응답합니다.
- 1회 요청 수량 한도(10,000)는 합성 제약 `@QuantityLimit`(`@Max(10000)`)으로 검증합니다. 형식 오류가 아닌 비즈니스 규칙 위반이므로 409 `QUANTITY_LIMIT_EXCEEDED`로 구분합니다.
- 입고로 누적 재고가 `BIGINT` 범위를 넘으면 Postgres가 SQLState `22003`으로 실패합니다. 이를 409 `STOCK_QUANTITY_OVERFLOW`로 변환하며, 유니크 제약 위반(`23505`)과는 SQLState로 구분해 `DUPLICATE_REQUEST`/`PRODUCT_CODE_ALREADY_EXISTS`로 잘못 응답하지 않도록 합니다.

## 상품 코드
- `productCode`는 클라이언트가 지정하며 서버는 정규화(trim/대문자 변환)하지 않고 그대로 저장·비교합니다. 대신 `^[A-Z0-9]+$` 형식이 아니면 거부해, 대소문자·공백 차이로 같은 상품이 다른 코드로 등록되는 것을 입구에서 막습니다. (기존 데이터에는 적용하지 않으며 DB `CHECK` 제약은 두지 않았습니다.)
- 기존 상품 입고와 출고는 `productId`와 `productCode`를 모두 필수로 받고, 두 값이 같은 상품을 가리키는지 검증해 잘못된 상품의 재고가 변경되는 것을 막습니다.

## 재고 이력 정렬
- 상품별 재고 변경은 상품 행 락(원자적 `UPDATE`) 아래에서 순서대로 반영되고 이력은 같은 트랜잭션에서 저장되므로, 상품별 이력 `id`는 실제 반영 순서와 일치합니다. 반면 `created_at`은 애플리케이션 서버 시각이라 서버 간 시계 차이로 순서가 뒤바뀔 수 있어, 기본 정렬을 `id DESC`로 합니다.
- `V3` 마이그레이션으로 `(product_id, id DESC)` 인덱스를 추가해 정렬 없이 인덱스 순서대로 페이지를 읽습니다. 기존 `(product_id, created_at DESC)` 인덱스는 파괴적 변경을 피하기 위해 유지합니다.

## 락 / 쿼리 타임아웃
- Hikari `data-source-properties.options`로 커넥션마다 Postgres `lock_timeout`(기본 3s)과 `statement_timeout`(기본 5s)을 설정합니다. 특정 상품에 요청이 몰리거나 락을 오래 잡는 트랜잭션이 있어도 요청 스레드와 커넥션을 무한정 붙잡지 않습니다.
- `lock_timeout`(55P03)은 `CannotAcquireLockException`, `statement_timeout`(57014)은 `QueryTimeoutException`으로 변환되며, 트랜잭션 타임아웃(`TransactionTimedOutException`)과 함께 503 `STOCK_LOCK_TIMEOUT`으로 응답합니다.
- `StockLockTimeoutIntegrationTest`는 별도 커넥션에서 `SELECT ... FOR UPDATE`로 상품 행 락을 잡은 상태에서 출고 요청이 503을 받고 재고·이력이 변하지 않는지 검증합니다.
