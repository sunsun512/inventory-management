# AI-Ready 개선 액션 — inventroy-management

총점 **79/100** · 등급 **AI-Ready** — 대부분의 개발 작업에서 AI가 안정적으로 navigation, edit, verify 가능  
이전 develop (014e9cc) 76점 → 79점 (+3)

예상 상승 = 이 액션만 완료했을 때 오르는 점수 · ROI = 예상 상승 ÷ 노력(작음 1, 보통 2, 큼 3)

| # | 항목 | 액션 | 대상 | 예상 상승 | 노력 | ROI |
|---|---|---|---|---|---|---|
| 1 | F | **context 경로·링크 검증을 CI + 주간 스케줄로 실행** — context 문서의 파일 경로/명령 실재 여부를 PR마다 검사하고, 같은 검사를 cron으로 주 1회 실행 |  | +7 | 보통 | 3.5 |
| 2 | D | **의존 규칙을 아키텍처 테스트/map으로 고정** — ArchUnit·dependency-cruiser·import-linter 등으로 허용 의존 방향을 테스트로 강제 |  | +5 | 보통 | 2.5 |
| 3 | G | **AI 작업 성과 지표 측정** — eval 세트 기준 pass rate·tool calls·tokens·소요 시간을 context 개선 전후로 기록 |  | +5 | 보통 | 2.5 |
| 4 | E4 | **대표 AI task eval 세트 만들기** — 자주 하는 작업 5-10개를 프롬프트 + 기대 결과(수정 파일, 통과 테스트)로 저장 |  | +2 | 보통 | 1.0 |
| 5 | E3 | **변경 유형별 검증 명령 명시** — context 문서에 빠진 종류의 검증 명령 추가 | `lint/format`, `typecheck`, `e2e` | +1 | 작음 | 1.0 |
| 6 | E3 | **CI에서 테스트 실행** — PR마다 테스트를 돌리는 CI 워크플로 추가 |  | +1 | 보통 | 0.5 |

## 카테고리 점수

| 카테고리 | 점수 | 이전 대비 | 단계 |
|---|---|---|---|
| A. AI 탐색성 & 커버리지 | 15/15 | +0 | 모든 핵심 module/workflow에 navigation guide, 1-2 hops 안에 찾음 |
| B. 컨텍스트 문서 품질 | 20/20 | +0 | 하위 항목 합산 |
| C. 암묵지 외재화 | 20/20 | +0 | tribal knowledge 대부분이 context/checklist/playbook에 반영, 질의로 회수 가능 |
| D. 모듈 간 의존성 & 데이터 흐름 | 10/15 | +0 | 주요 module 간 dependency와 ownership 문서화 |
| E. 검증 & 품질 게이트 | 11/15 | +3 | 하위 항목 합산 |
| F. 최신성 & 자동 유지 | 3/10 | +0 | 문서 owner가 있고 가끔 업데이트 |
| G. 에이전트 성과 측정 | 0/5 | +0 | AI 성능 개선 측정 없음 |
