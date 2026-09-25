# common 패키지

## 소유 범위
모든 기능이 공유하는 설정·예외·응답·횡단 관심사를 소유합니다. 사실상 `src/main/resources/application.yml`(공통)과 `application-local.yml`(local 프로필)의 동작도 여기서 다룹니다.
- `config/` — `JacksonConfig`(엄격한 JSON), `QuerydslConfig`(`JPAQueryFactory`), `RateLimitConfig`(인터셉터 등록), `OpenApiConfig`(Swagger 공통 설명·에러 코드 표)
- `exception/` — `ErrorCode`(코드 → HTTP 상태), `InventoryException`과 하위 예외들, `GlobalExceptionHandler`, `SqlStates`(SQLState 추출)
- `logging/` — `ApiLoggingFilter`(`[REQ]`/`[RES]` 로그), `TraceIdResponseFilter`(`X-Trace-Id` 헤더)
- `ratelimit/` — `PostRateLimiter`(IP별 토큰 버킷), `RateLimitInterceptor`, `RateLimitProperties`(`rate-limit.post.*`)
- `response/` — `ErrorResponse`(`code`, `message`, `timestamp`), `PageResponse`(`content`, `page`, `size`, `hasNext`)

## 핵심 파일
- `src/main/java/com/example/inventory/management/common/exception/ErrorCode.java` — 에러 코드와 HTTP 상태
- `src/main/java/com/example/inventory/management/common/exception/GlobalExceptionHandler.java` — 예외 → 응답·로그 매핑의 유일한 지점
- `src/main/java/com/example/inventory/management/common/ratelimit/PostRateLimiter.java` — 버킷 생성·만료
- `src/main/java/com/example/inventory/management/common/config/OpenApiConfig.java` — 공통 규칙·에러 코드 표(문서)
- `src/main/resources/application.yml` — DB 타임아웃, graceful shutdown, Jackson, Flyway 잠금, rate limit, Actuator

## 수정 패턴
**새 에러 코드 추가**
1. `ErrorCode`에 상수와 `HttpStatus` 추가.
2. `common/exception/`에 `InventoryException`을 상속한 예외 추가(메시지에 진단용 식별자 포함, 예: `productId=`).
3. 서비스에서 로그 없이 던짐. `handleInventoryException`이 상태·본문·로그(4xx WARN, 5xx ERROR)를 처리하므로 핸들러 수정은 보통 불필요.
4. 문서: `OpenApiConfig` 에러 표와 해당 `StockApi`/`ProductApi`의 `@ApiResponse` 갱신(문서는 테스트 불필요).
5. 테스트: 해당 기능의 컨트롤러 통합 테스트에서 상태·`code` 검증.

**요청 속도 제한 설정 변경**
1. 값은 `RATE_LIMIT_CAPACITY` / `RATE_LIMIT_REFILL_PER_SECOND` 환경 변수 또는 `application.yml`의 `rate-limit.post`. 0 이하이면 기동 시 실패합니다(`PostRateLimiterTest`).
2. 버킷 만료 시간은 `capacity / refill-per-second`에서 자동 계산되므로 따로 설정하지 않습니다.
3. 동작을 바꾸면 `PostRateLimiterTest`(`FakeTimeMeter`, 가짜 ticker), `RateLimitInterceptorTest`, `RateLimitIntegrationTest`를 수정.

**DB 타임아웃/종료 대기 변경**
- `DB_LOCK_TIMEOUT`(3s)·`DB_STATEMENT_TIMEOUT`(5s)을 늘리면 `spring.lifecycle.timeout-per-shutdown-phase`(20s)가 요청 한 건의 최악 소요 시간보다 길게 유지되는지 확인. 검증: `DatabaseTimeoutConfigTest`, `GracefulShutdownConfigTest`.

## 실패 함정
- **실패 로그는 `GlobalExceptionHandler`에서만 한 번**: 서비스·필터에서 실패를 로그로 남기면 중복됩니다.
- **`DataIntegrityViolationException`은 기본 500**: unique 위반·BIGINT 초과처럼 비즈니스 충돌이면 호출하는 쪽에서 `SqlStates`로 판별해 `InventoryException`으로 바꿔야 합니다(`stock.command` 참고).
- **락·쿼리·트랜잭션 시간 초과 → 503 `STOCK_LOCK_TIMEOUT`** + `Retry-After: 1`: `PessimisticLockingFailureException`(55P03), `QueryTimeoutException`(57014), `TransactionTimedOutException`. 롤백되었으므로 같은 `requestId`로 재시도 가능하다는 전제입니다.
- **수량 한도 409는 제약 코드로 판별**: `QuantityLimit` 위반만 있을 때만 409 `QUANTITY_LIMIT_EXCEEDED`, 다른 검증 오류가 섞이면 400 `VALIDATION_FAILED`가 우선. 판별 키는 `QuantityLimit.class.getSimpleName()`입니다.
- **Jackson은 엄격**: 문자열 숫자(`"5"`), 소수(`1.9`), 정의되지 않은 필드는 모두 400. `JacksonConfig`와 `application.yml`의 `accept-float-as-int: false`, `fail-on-unknown-properties: true`가 함께 담당합니다.
- **Rate limit은 필터가 아니라 인터셉터**: 예외를 던져 `ErrorResponse` 본문을 쓰기 위해서입니다. `/api/**`의 POST만 제한하고, 본문을 읽기 전에 검사하므로 잘못된 요청도 토큰을 씁니다.
- **클라이언트 식별은 `getRemoteAddr()`만**: `X-Forwarded-For`는 위조 가능해 신뢰하지 않습니다. LB 뒤에서는 `server.forward-headers-strategy` 설정이 필요합니다.
- **버킷 만료를 재충전 시간보다 짧게 두면 안 됨**: 일찍 만료되면 가득 찬 새 버킷을 받아 제한을 우회합니다(커밋 0717d80).
- **통합 테스트는 한도를 올려 둠**: `AbstractIntegrationTest`가 `rate-limit.post.capacity=1000000`을 설정합니다. 작은 한도 검증은 `RateLimitIntegrationTest`처럼 별도 `@SpringBootTest`로.
- **`spring.flyway.postgresql.transactional-lock: false` 유지**: 트랜잭션 잠금이면 `CREATE/DROP INDEX CONCURRENTLY`가 그 트랜잭션 종료를 기다리며 막힙니다(`FlywayLockConfigTest`).
- **`ddl-auto: validate`는 `application-local.yml`에만** 있습니다. 공통 `application.yml`에는 없습니다.

## 의존성
- common → stock: `common.exception.GlobalExceptionHandler` → `stock.command.validation.QuantityLimit`
- 내부: `config` → `ratelimit`, `ratelimit` → `exception`(`TooManyRequestsException`), `exception` → `response`
- common에 의존하는 쪽: `product.api`/`product.query`, `stock.api`/`stock.command`/`stock.query`(`common.exception`, `common.response`)

## 배경·이유
- DB 세션 타임아웃은 상품 행 락을 오래 기다리거나 쿼리가 길어질 때 커넥션을 무한정 붙잡지 않기 위해서입니다. Hikari `connection-timeout: 3000`도 종료 대기(20s) 안에 끝나도록 짧게 둡니다(`application.yml` 주석, 커밋 9ec05ad).
- Rate limit 상태는 Redis 없이 인스턴스 메모리(Caffeine)에 둡니다. 인스턴스가 N대면 한도가 N배, 재기동 시 초기화 — 정확한 클러스터 쿼터가 아니라 한 클라이언트의 단기 폭주를 막는 것이 목적입니다(`PostRateLimiter` 주석).
- Actuator는 health만 노출하고 liveness에서 DB를 빼서, DB 장애로 프로세스가 재시작되지 않게 합니다(커밋 7f5f977).
- `PageResponse`는 count 쿼리 대신 `size + 1`건으로 `hasNext`만 판단합니다.
