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
docker compose -f local/docker-compose.yml up -d
```
`local/docker-compose.yml`은 `postgres:17.5` 컨테이너(`postgresql`)를 `5432` 포트로 띄웁니다. DB `inventory`, 사용자 `inventory-user`, 비밀번호 `inventory-password`이고, 데이터는 `postgres` 볼륨에 유지됩니다. 중지는 `docker compose -f local/docker-compose.yml down`입니다(`-v`를 붙이면 볼륨의 데이터까지 삭제됩니다).

## 애플리케이션 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```
DB 접속 설정(`spring.datasource.*`)은 `local` 프로필 전용 파일인 `application-local.yml`에 있습니다. 접속 URL은 `jdbc:postgresql://localhost:5432/inventory`로 고정되어 있어 `DB_URL` 같은 환경변수로는 바꿀 수 없고, 사용자/비밀번호는 `DB_USERNAME` / `DB_PASSWORD`(기본 `inventory-user` / `inventory-password`), 커넥션 풀 크기는 `DB_POOL_MAX_SIZE`(기본 `10`) / `DB_POOL_MIN_IDLE`(기본 `4`)로 덮어쓸 수 있습니다. DB 세션 타임아웃은 공통 설정 `application.yml`에 있으며 `DB_LOCK_TIMEOUT`(기본 `3s`) / `DB_STATEMENT_TIMEOUT`(기본 `5s`)으로 덮어쓸 수 있습니다 (기본값은 위 로컬 Postgres 설정과 일치). 애플리케이션 기동 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 자동 적용합니다.

## 로컬 시드 데이터
```bash
./gradlew bootRun --args='--spring.profiles.active=local,seed'
```
- `seed` 프로필을 함께 켜면 애플리케이션이 기동하면서 상품 10,000개와 상품별 재고 요청 이력 8~12건(총 약 10만 건)을 생성합니다. 약 20초 걸리고, 이후에는 평소처럼 API 서버로 동작합니다.
- **`product`와 `stock_history`가 모두 비어 있을 때만 생성합니다.** 데이터가 하나라도 있으면 아무것도 지우거나 추가하지 않고 건너뜁니다. 따라서 `seed` 프로필을 켠 채로 다시 기동해도 안전합니다. 데이터를 새로 만들고 싶으면 테이블을 직접 비운 뒤 기동합니다.
- 시드 SQL은 Flyway 마이그레이션이 아닙니다. `src/main/resources/db/seed/local-seed-data.sql`에 있고, Flyway는 `db/migration`만 읽기 때문에 테스트나 다른 환경에는 적용되지 않습니다. `seed` 프로필에서만 등록되는 `LocalSeedDataRunner`(`ApplicationRunner`)가 Flyway 마이그레이션이 끝난 뒤 이 파일을 하나의 트랜잭션으로 실행합니다.
- 공통 설정의 `statement_timeout`(기본 5s)보다 오래 걸리므로, 시드 트랜잭션 안에서만 `SET LOCAL statement_timeout = 0`으로 해제합니다. `lock_timeout`은 그대로 적용됩니다.
- 생성 방식
  1. 상품: `generate_series`로 10,000행을 만들고, 카테고리 8종의 접두어와 6자리 일련번호로 `productCode`를 만듭니다(예: `FOOD000072`, `^[A-Z0-9]+$`). 상품명은 카테고리·품목·옵션을 조합합니다(예: `[식품] 그래놀라 미니 10호`).
  2. 이력: 재귀 CTE로 모든 상품의 n번째 요청을 한 단계씩 동시에 생성합니다. 이렇게 하면 `beforeQuantity`/`afterQuantity`가 끊기지 않고 이어집니다.
     - 첫 요청은 신규 상품 등록 입고(`beforeQuantity` = 0)입니다.
     - 이후 요청은 입고 55% / 출고 45%이며, 재고가 0이면 반드시 입고입니다.
     - 수량은 1회 1~10,000 범위입니다. 대량 입고와 전량 출고(품절)도 일부 섞여 있어 재고 0인 상품이 생깁니다.
     - 요청 간격은 10분~3일이고, 전체 기간은 약 180일 전부터 현재까지입니다.
  3. INSERT: 상품은 등록 시각 순으로 넣습니다. 이력은 전체를 `created_at` 순으로 넣어 상품별 이력 `id` 순서가 실제 반영 순서와 같게 합니다([재고 이력 정렬](#재고-이력-정렬) 전제). `requestId`는 `gen_random_uuid()`로 만든 소문자 UUID입니다.
  4. 검증: 커밋 전에 다음을 확인하고, 하나라도 어긋나면 전체를 롤백합니다.
     - 이력 사이의 수량이 이어지는지
     - `product.quantity`가 마지막 이력의 `afterQuantity`와 같은지
     - 상품별 `id` 순서와 `created_at` 순서가 같은지
     - 첫 이력이 신규 등록 입고인지
- `setseed`로 난수 시드를 고정해 상품 구성과 수량은 매번 같게 생성됩니다. `requestId`만 실행할 때마다 달라집니다. 다른 데이터가 필요하면 `local-seed-data.sql`의 `setseed` 값을 바꿉니다.

## 테스트 실행
```bash
./gradlew test
```
모든 테스트는 Testcontainers로 띄운 실제 Postgres에 대해 실행되며(H2 등 인메모리 DB 미사용), 별도의 로컬 Postgres 없이도 동작합니다.

# API 명세
API 명세는 코드(springdoc-openapi)에서 생성되는 OpenAPI 문서로 제공합니다. 애플리케이션 실행 후 아래 주소에서 확인합니다.

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

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
