# __workspace — 패키지별 작업 가이드

기능 패키지(`com.example.inventory.management` 아래)를 수정하기 전에 읽는 짧은 안내서입니다.
각 문서는 소유 범위, 핵심 파일, 수정 패턴, 실패 함정, 의존성, 배경·이유를 다룹니다.
요구사항·실행 방법·API 전반은 루트 [README.md](../README.md)를 봅니다.

| 문서 | 읽을 때 |
|---|---|
| [common.md](common.md) | 예외·에러 코드, 전역 예외 처리, 요청 속도 제한, 로그/traceId, 페이지 응답, Jackson·QueryDSL·OpenAPI 설정, `application*.yml`을 바꿀 때 |
| [product.md](product.md) | 상품 조회 API(목록·상세·현재 재고), `Product` 엔티티, `ProductRepository`의 원자적 SQL을 바꿀 때 |
| [stock.md](stock.md) | 입고·출고, `requestId` 중복 방지, 재고 이력 저장·조회, `stock_history` 스키마를 바꿀 때 |

- 여러 패키지에 걸친 변경이면 해당 문서를 모두 읽습니다. 예: 입고 요청 필드 추가 → `stock.md` + `common.md`.
- 문서와 코드가 다르면 코드를 믿고 문서를 고칩니다([.claude/rules/package-docs.md](../.claude/rules/package-docs.md)).
