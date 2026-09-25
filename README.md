# 프로젝트
- Inventory Management API

# 기능 요구사항
## 입고
- 상품의 현재 재고 수량을 증가시킵니다.
- 등록되지 않은 상품일 경우 신규 상품으로 등록 후 입고 처리합니다.

## 출고
- 상품의 현재 재고 수량을 감소시킵니다.
- 상품의 재고 수량은 음수가 될 수 없습니다.

## 재고
- 상품의 현재 재고 수량을 확인합니다.
- 재고 변경 이력을 조회합니다.
- 상품 목록(상품 코드 필터)과 상품 상세를 조회합니다.

# 작업목록
- IM1 - 규모 추정
- IM2 - 요구사항 작성
- IM3 - API 서버 구현
- IM4 - 데이터베이스 연동
- IM5 - 문서화
- IM6 - 고가용성을 위한 설계
- IM7 - 운영 안정성
- IM8 - 코드 리뷰 체계

# 기술 스택
| 항목 | 용도 |
|---|---|
| Java 17 · Spring Boot 4.1 · JPA · QueryDSL | API 서버 |
| PostgreSQL 17 · Flyway | DB, 스키마 변경 이력 관리 |
| Testcontainers | 실제 Postgres 기반 통합 테스트 |
| Actuator · Micrometer Tracing | 헬스체크, 요청별 traceId |
| Bucket4j · Caffeine | POST 요청 속도 제한 |
| springdoc-openapi | API 명세(Swagger) |
| Docker Compose | 로컬 DB 구동 |

# 실행 방법

## 요구사항
- Java 17
- Docker (로컬 Postgres 구동 및 테스트용 Testcontainers 실행에 필요)

## 로컬 Postgres 준비
```bash
docker compose -f local/docker-compose.yml up -d
```
`postgres:17.5`를 `5432` 포트로 띄웁니다(DB `inventory`, 사용자 `inventory-user` / `inventory-password`).

### Docker 없이 직접 준비할 경우
PostgreSQL(17 권장)에 superuser로 접속해 사용자와 DB를 만듭니다. 테이블은 앱 기동 시 Flyway가 생성합니다.
```sql
CREATE USER "inventory-user" WITH PASSWORD 'inventory-password';
CREATE DATABASE inventory OWNER "inventory-user" ENCODING 'UTF8' TEMPLATE template0;
```
- 호스트·포트가 `localhost:5432`가 아니면 `application-local.yml`의 `spring.datasource.url`을 바꿉니다.

## 애플리케이션 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```
- 기동 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 적용합니다.
- `local` 프로필은 테이블이 비어 있을 때만 시드 데이터(상품 10,000개, 재고 이력 약 10만 건)를 생성합니다(약 20초).
- 종료 신호(SIGTERM)를 받으면 처리 중인 요청을 최대 20초 기다린 뒤 종료합니다.

## 환경 변수
`local` 프로필 기준입니다. `DB_LOCK_TIMEOUT`·`DB_STATEMENT_TIMEOUT`·`RATE_LIMIT_*`만 모든 프로필에 적용됩니다.

| 이름 | 기본값 | 용도 |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | `inventory-user` / `inventory-password` | DB 접속 계정 |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `4` | 커넥션 풀 크기 |
| `DB_LOCK_TIMEOUT` / `DB_STATEMENT_TIMEOUT` | `3s` / `5s` | 락 대기·쿼리 타임아웃 |
| `DB_SEED_MODE` | `always` | 로컬 시드 데이터 생성 (`never`로 끔) |
| `RATE_LIMIT_CAPACITY` / `RATE_LIMIT_REFILL_PER_SECOND` | `20` / `10` | POST 요청 속도 제한 |

## 헬스체크
| 경로 | 용도 | 확인 항목 |
|---|---|---|
| `/actuator/health` | 전체 상태 | DB, 디스크 등 |
| `/actuator/health/liveness` (`/livez`) | 프로세스가 살아 있는가 (실패 시 재시작) | 애플리케이션 상태만 (DB 제외) |
| `/actuator/health/readiness` (`/readyz`) | 요청을 받을 준비가 되었는가 (실패 시 트래픽 제외) | 애플리케이션 상태 + DB |

- 기동 중(마이그레이션 포함)이거나 종료 중이면 readiness가 503을 반환합니다.

## API 로그
- 요청마다 `[REQ]`, `[RES]`(상태 코드, 소요 시간) 로그를 남깁니다. 헬스체크 경로는 제외합니다.
- 모든 로그에 요청별 traceId가 붙고, 응답 헤더 `X-Trace-Id`로도 내려줍니다.

## 테스트 실행
```bash
./gradlew test
```
- Testcontainers로 띄운 실제 Postgres에서 실행되므로 Docker가 실행 중이어야 합니다(로컬 Postgres는 필요 없음).
- 테스트가 끝나면 JaCoCo 커버리지 리포트가 `build/reports/jacoco/test/html/index.html`에 생성됩니다.

# API 명세
`local` 프로필로 실행했을 때만 Swagger에서 확인할 수 있습니다(다른 프로필에서는 비활성화).

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
- 1회 요청 수량은 1 ~ 10,000입니다.
- `productCode`는 영문 대문자와 숫자만 허용합니다.
- 필드별 규칙과 오류 코드는 Swagger에서 확인합니다.

## 중복 요청 / 동시성
- `requestId`(UUID)로 중복 요청을 막습니다. 처음 성공한 요청만 처리하고 이후 같은 `requestId`는 409 `DUPLICATE_REQUEST`입니다.
- 재고 증감은 원자적 `UPDATE`로 처리하고, DB 제약으로 재고가 음수가 되지 않게 합니다.
- 락 대기·쿼리 시간이 초과되면 503 `STOCK_LOCK_TIMEOUT`으로 응답하며, 같은 `requestId`로 재시도할 수 있습니다.

## 요청 속도 제한
- 모든 POST API는 클라이언트 IP별로 요청 수를 제한합니다(기본 한 번에 20건, 초당 10건).
- 한도를 넘으면 429 `TOO_MANY_REQUESTS`와 `Retry-After`(초)로 응답합니다. 통과한 요청에는 남은 요청 수를 `X-RateLimit-Remaining` 헤더로 알려줍니다.
- 제한 상태는 인스턴스 메모리에 두므로 인스턴스가 N대면 실제 한도는 N배이고, 재기동하면 초기화됩니다.
- IP는 접속 주소만 사용합니다(`X-Forwarded-For` 미신뢰). 로드밸런서 뒤에 배포할 때는 `server.forward-headers-strategy` 설정이 필요합니다.

## 조회 / 페이징
- 목록 조회(상품 목록, 재고 이력)는 `page`(기본 0), `size`(기본 10, 최대 100)를 받고 `content`, `page`, `size`, `hasNext`로 응답합니다. 전체 건수는 제공하지 않습니다.
- 상품 목록의 `productCode` 필터로 상품 코드에서 `productId`를 찾을 수 있습니다.

## 로그 / 시각
- 실패한 요청은 예외 핸들러에서 한 번만 로그로 남깁니다(4xx WARN, 5xx ERROR).
- 모든 시각은 UTC를 사용합니다.
