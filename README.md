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
  - 상품 목록(상품 코드 필터) / 상품 상세 조회

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
`postgres:17.5`를 `5432` 포트로 띄웁니다(DB `inventory`, 사용자 `inventory-user` / `inventory-password`).

> 마이그레이션 `V1`·`V2`가 수정되고 `V3`가 `V2`로 통합되었습니다. 이전 버전으로 만든 로컬 DB는 Flyway 검증에 실패하므로 초기화 후 다시 띄웁니다(데이터 삭제).
> ```bash
> docker compose -f local/docker-compose.yml down -v && docker compose -f local/docker-compose.yml up -d
> ```

## 애플리케이션 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```
- DB 사용자/비밀번호는 `DB_USERNAME` / `DB_PASSWORD`, 커넥션 풀은 `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE`, 타임아웃은 `DB_LOCK_TIMEOUT`(기본 3s) / `DB_STATEMENT_TIMEOUT`(기본 5s)으로 바꿀 수 있습니다.
- 기동 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 적용합니다.
- 종료 신호(SIGTERM)를 받으면 새 요청을 받지 않고, 처리 중인 요청을 최대 20초 기다린 뒤 종료합니다(graceful shutdown).

## 헬스체크
| 경로 | 용도 | 확인 항목 |
|---|---|---|
| `/actuator/health` | 전체 상태 | DB, 디스크 등 |
| `/actuator/health/liveness` (`/livez`) | 프로세스가 살아 있는가 (실패 시 재시작) | 애플리케이션 상태만 (DB 제외) |
| `/actuator/health/readiness` (`/readyz`) | 요청을 받을 준비가 되었는가 (실패 시 트래픽 제외) | 애플리케이션 상태 + DB |

- 기동 중(마이그레이션 포함)이거나 종료 중이면 readiness가 503을 반환합니다.

## 로컬 시드 데이터
`local` 프로필로 기동하면 테이블이 비어 있을 때만 상품 10,000개와 재고 이력 약 10만 건을 생성합니다(약 20초, `src/main/resources/data.sql`).
```bash
DB_SEED_MODE=never ./gradlew bootRun --args='--spring.profiles.active=local' # 시드 끄기
```

## 테스트 실행
```bash
./gradlew test
```
Testcontainers로 띄운 실제 Postgres에서 실행되며, 로컬 Postgres가 없어도 동작합니다.

## 테스트 커버리지
JaCoCo로 측정합니다. `./gradlew test`가 끝나면 커버리지 리포트가 자동으로 생성됩니다.
```bash
./gradlew test
open build/reports/jacoco/test/html/index.html   # macOS에서 HTML 리포트 열기
```
- 리포트는 `build/reports/jacoco/test/` 아래에 HTML(`html/index.html`, 사람이 보는 용)과 XML(`jacocoTestReport.xml`, CI·커버리지 도구 연동용)로 생성됩니다. 리포트만 다시 만들려면 `./gradlew jacocoTestReport`를 실행합니다.
- 테스트가 Testcontainers를 사용하므로 측정할 때 Docker가 실행 중이어야 합니다. Docker 없이 실행하면 통합 테스트가 실패해 커버리지가 실제보다 낮게 나옵니다.
- HTML 리포트에서 패키지·클래스별 라인/브랜치 커버리지를 확인할 수 있습니다. 소스 코드는 실행된 줄이 초록, 실행되지 않은 줄이 빨강, 조건의 일부 경우만 실행된 줄이 노랑으로 표시됩니다.
  - 라인 커버리지: 코드 줄이 한 번이라도 실행됐는지를 셉니다.
  - 브랜치 커버리지: `if`·`switch`·삼항 연산자 같은 조건에서 갈 수 있는 경우가 각각 실행됐는지를 셉니다. 재고 음수 방지, 중복 요청 거부, 락 타임아웃처럼 조건에 따라 동작이 달라지는 로직이 핵심이므로 브랜치 커버리지를 더 중요한 기준으로 봅니다.
- 실행 로직이 거의 없는 애플리케이션 메인 클래스, `dto` 패키지, `common/config` 패키지는 측정에서 제외합니다(`build.gradle`의 `coverageExcludes`).

# API 명세
애플리케이션 실행 후 Swagger에서 확인합니다.

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

# 설계

## 패키지 구조
기능별로 패키지를 나누고, 각 기능 안은 쓰기(`command`)와 읽기(`query`)로 나눕니다.
```
com.example.inventory.management
├── common/     공통 설정·예외·응답
├── product/    상품 조회
└── stock/      입고·출고, 재고 이력 조회
```
각 기능 패키지는 아래처럼 구성합니다.

| 패키지 | 역할 |
|---|---|
| `api/` | Controller |
| `command/` | 쓰기(입고·출고) |
| `query/` | 읽기(목록·상세·이력) |
| `domain/` | 엔티티, Repository |

- 의존 방향: `api → command / query → domain`. `command`와 `query`는 서로 참조하지 않습니다.
- 테스트도 같은 구조를 따르며, 공통 테스트 코드는 `support/`에 있습니다.

## 요청 검증
- `quantity`는 1 ~ 10,000 사이 정수만 허용합니다. 소수·문자열 숫자는 400, 10,000 초과는 409 `QUANTITY_LIMIT_EXCEEDED`입니다.
- `productCode`는 정규화하지 않고 `^[A-Z0-9]+$` 형식만 허용합니다.
- 기존 상품 입고와 출고는 `productId`와 `productCode`가 같은 상품을 가리켜야 합니다.

## 중복 요청 / 동시성
- `requestId`(UUID)로 중복 요청을 막습니다. 처음 성공한 요청만 처리하고 이후 같은 `requestId`는 409 `DUPLICATE_REQUEST`입니다.
- 재고 증감은 원자적 `UPDATE`로 처리하고, DB 제약으로 재고가 음수가 되지 않게 합니다.
- 락 대기·쿼리 시간이 초과되면 503 `STOCK_LOCK_TIMEOUT`으로 응답하며, 같은 `requestId`로 재시도할 수 있습니다.

## 조회 / 페이징
- 목록 조회(상품 목록, 재고 이력)는 `page`(기본 0), `size`(기본 10, 최대 100)를 받고 `content`, `page`, `size`, `hasNext`로 응답합니다. 전체 건수는 제공하지 않습니다.
- 재고 이력은 `createdAt DESC, id DESC`, 상품 목록은 `productId DESC`로 정렬을 고정합니다.
- 상품 목록의 `productCode` 필터로 상품 코드에서 `productId`를 찾을 수 있습니다.

## 로그 / 시각
- 실패한 요청은 예외 핸들러에서 한 번만 로그로 남깁니다(4xx WARN, 5xx ERROR).
- 모든 시각은 애플리케이션 시각(UTC)을 사용하며, 재고 변경 한 건의 상품·이력 시각은 같은 값입니다.
