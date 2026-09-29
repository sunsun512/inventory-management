# 리뷰 체크리스트

사람 리뷰어와 `code-reviewer` 에이전트가 같이 쓰는 단일 기준입니다. 패키지별 세부 함정은 `__workspace/<package>.md`의 "실패 함정"이 원본이고, 여기에는 **이 repo에서 실제로 사고를 낼 수 있는 항목만** 둡니다.

## 리뷰 라운드
1. **작성자 자가 점검**: PR을 열기 전 이 체크리스트를 직접 확인합니다(PR 템플릿).
2. **독립 리뷰 (`code-reviewer` 에이전트)**: 작성 맥락이 없는 별도 컨텍스트에서 diff를 검토합니다. 결과를 PR 본문에 첨부합니다.
3. **사람 리뷰**: CODEOWNERS 리뷰어가 1·2의 결과와 diff를 확인합니다.

## 공통
- [ ] 동작 변경은 테스트가 먼저 있고, Red → Green 결과를 PR 본문 "테스트"에 적었다(`.claude/rules/tdd.md`).
  - DB·SQL·트랜잭션·HTTP 매핑·설정 바인딩을 거치는 동작은 `AbstractIntegrationTest` 기반 통합 테스트.
  - 설정값을 입력으로 받는 순수 계산(예: `PostRateLimiter`의 만료 시간)은 단위 테스트로 충분하다.
- [ ] `./gradlew test` 전체가 통과한다(Docker 필요). 결과를 PR 본문에 적었다.
- [ ] 바뀐 패키지의 `__workspace/<package>.md`를 읽었고, 문서 내용이 바뀌었으면 같은 PR에서 문서도 고쳤다(`.claude/rules/package-docs.md`).
- [ ] CLAUDE.md·`__workspace`·rules에 새로 적은 경로·클래스·명령이 실제로 존재한다.

## 데이터·동시성 (stock, product)
- [ ] 재고 증감은 원자적 SQL(`UPDATE ... RETURNING`)이며, 읽고-계산하고-저장하지 않는다.
- [ ] `ProductRepository` 쓰기 쿼리에 `@Modifying`을 붙이지 않았다.
- [ ] 잠금 순서는 advisory lock → 상품 행을 유지한다. `@Transactional`을 `StockCommandService`로 옮기지 않았다.
- [ ] 시각은 애플리케이션에서 한 번 만든 값(`:now`)을 쓰고 DB `now()`를 쓰지 않는다.
- [ ] 제약 이름을 바꿨다면 `StockCommandService`의 제약 이름 상수도 바꿨다.

## 마이그레이션
- [ ] 커밋된 마이그레이션 파일을 수정하지 않고 새 `V<n>__*.sql`을 추가했다.
- [ ] `DROP`/`TRUNCATE`/데이터 삭제가 있으면 사용자 확인을 받았다(`.claude/rules/db-migration.md`).
- [ ] `CONCURRENTLY` 인덱스 작업은 그 구문만 있는 별도 파일이다.

## API·에러 처리 (common)
- [ ] 새 비즈니스 오류는 `ErrorCode` + `InventoryException` 하위 클래스로 표현하고, `DataIntegrityViolationException`이 그대로 500으로 새지 않는다.
- [ ] 실패 로그는 `GlobalExceptionHandler`에서만 남긴다(서비스·필터에서 중복 로그 없음).
- [ ] 파라미터 제약·OpenAPI 설명은 `*Api` 인터페이스에만 선언했다.
- [ ] 클라이언트 IP는 `getRemoteAddr()`만 사용하고 `X-Forwarded-For`를 직접 신뢰하지 않는다.
- [ ] rate limit 버킷의 캐시 만료가 가득 채워지는 시간(capacity ÷ refill-per-second)보다 짧지 않다. 짧으면 만료 후 새 버킷을 받아 제한을 우회한다.

## 보안·운영
- [ ] 로그·에러 응답에 비밀값, 스택 트레이스, SQL이 노출되지 않는다.
- [ ] 비밀값·접속 정보는 환경 변수로 받고 `application.yml`에 하드코딩하지 않았다(`application-local.yml`의 로컬 기본값은 예외).
