# AI-Ready Codebase Rubric (100점, 7 카테고리)

`scripts/score.py`가 이 루브릭을 휴리스틱으로 자동 채점한다. 자동 점수는 **1차 추정치**이며,
에이전트가 증거를 직접 확인한 뒤 `overrides.json`으로 보정하는 것을 전제로 한다.
각 항목의 "자동 채점 방식"은 스크립트가 실제로 보는 신호이고, "판단 보정 포인트"는 휴리스틱이
놓치기 쉬워 사람이/에이전트가 직접 확인해야 하는 부분이다.

## 목차
- [요약표](#요약표)
- [A. AI Navigation & Coverage (15)](#a-ai-navigation--coverage-15점)
- [B. Context Document Quality (20)](#b-context-document-quality-20점)
- [C. Tribal Knowledge Externalization (20)](#c-tribal-knowledge-externalization-20점)
- [D. Cross-Module Dependency & Data Flow Mapping (15)](#d-cross-module-dependency--data-flow-mapping-15점)
- [E. Verification & Quality Gates (15)](#e-verification--quality-gates-15점)
- [F. Freshness & Self-Maintenance (10)](#f-freshness--self-maintenance-10점)
- [G. Agent Performance Outcomes (5)](#g-agent-performance-outcomes-5점)
- [Final Grade](#final-grade)
- [핵심 개념 정의](#핵심-개념-정의)

## 요약표

| Category | Points | What It Measures |
|---|---|---|
| A. AI Navigation & Coverage | 15 | AI가 전체 codebase/module/workflow를 빠르게 찾을 수 있는가 |
| B. Context Document Quality | 20 | context files가 "compass, not encyclopedia" 원칙을 따르는가 |
| C. Tribal Knowledge Externalization | 20 | 숨은 규칙, 실패 패턴, human-only knowledge가 구조화되었는가 |
| D. Cross-Module Dependency & Data Flow Mapping | 15 | 변경 영향 범위를 AI가 추적할 수 있는가 |
| E. Verification & Quality Gates | 15 | AI-generated context와 code changes를 검증하는 체계가 있는가 |
| F. Freshness & Self-Maintenance | 10 | context가 stale해지지 않도록 자동 유지되는가 |
| G. Agent Performance Outcomes | 5 | 실제 AI task 성공률/효율 개선이 측정되는가 |

채점 단위(item id): `A`, `B1`–`B5`, `C`, `D`, `E1`–`E4`, `F`, `G` — overrides.json의 키로 그대로 쓴다.

---

## A. AI Navigation & Coverage (15점)

| Score | Criteria |
|---|---|
| 0 | AI가 repo를 직접 grep/search하며 구조를 추측해야 함 |
| 5 | 주요 module 일부에 README/context 존재 |
| 10 | 대부분의 핵심 module에 역할, entry point, related files 정리 |
| 15 | 모든 핵심 module/workflow에 AI navigation guide 존재. "어디를 봐야 하는가"를 1-2 hops 안에 찾을 수 있음 |

**측정식**: `Navigation Coverage = AI-context로 안내 가능한 핵심 module 수 / 전체 핵심 module 수`.
파일 개수보다 module/workflow coverage가 중요하다.

**자동 채점 방식**
- 핵심 module 탐지: 하위 디렉터리의 build manifest(package.json, build.gradle, pom.xml, pyproject.toml, go.mod, Cargo.toml …)가 2개 이상이면 그 디렉터리들이 module. 아니면 코드 파일들의 공통 소스 루트 바로 아래 디렉터리(테스트 제외, 코드 파일 2개 이상)가 module.
- module별 가중치: module 내부에 AI context 파일/README 있음 = 1.0, AI context 파일(CLAUDE.md, AGENTS.md, .claude/rules …)에서 이름·경로로 언급됨 = 0.6, 일반 README에서만 언급 = 0.3, 없음 = 0.
- 점수 = `round(15 × 평균 가중치)`.

**판단 보정 포인트**: module 탐지가 틀렸으면(예: 실제 핵심 workflow가 디렉터리 구조와 다름) 올바른 module 목록 기준으로 재계산한다. 단순 언급이 아니라 "역할 + entry point + 관련 파일"까지 안내하는지 확인한다.

---

## B. Context Document Quality (20점)

| 항목 | Points | Full Score Criteria |
|---|---|---|
| B1. Conciseness | 4 | 25-35 lines 또는 약 1,000 tokens 내외 |
| B2. Quick Commands | 4 | copy-paste 가능한 명령어와 사용 시점 포함 |
| B3. Key Files | 4 | 실제 수정에 필요한 3-5개 핵심 파일 제시 |
| B4. Non-Obvious Patterns | 4 | 실패를 유발하는 hidden rule과 예외 명시 |
| B5. See Also / Cross References | 4 | 관련 module, context file, dependency map으로 연결 |

중요한 것은 "문서가 많다"가 아니라 **task-relevant context만** 담는 것이다.

**자동 채점 방식** — 대상은 primary context 파일(CLAUDE.md, AGENTS.md, GEMINI.md, copilot-instructions.md, .cursorrules, CONTEXT.md 등). 루트 파일은 가중치 2. primary 파일이 없으면 루트 README를 평가하되 점수를 50%로 캡.
- B1: 추정 토큰(ASCII 문자/4 + 비ASCII 문자×0.8) ≤1,300 & 비어있지 않은 줄 ≤50 → 4, ≤2,500 → 3, ≤4,000 → 2, ≤7,000 → 1, 초과 → 0. 8줄 미만은 내용 부족으로 2.
- B2: 코드 블록 안 명령어 줄 수. 0 → 0, 1-2 → 2, 3+ → 3, 3+이고 절반 이상에 주석/설명이 붙음 → 4.
- B3: 실제 존재하는 **파일** 참조 수(디렉터리·.md 제외, 확장자 없는 클래스 경로 `common/exception/ErrorCode` 허용). 3-6 → 4, 7-12 → 3, 1-2 또는 13+ → 2, 0 → 0.
- B4: never/must not/avoid/gotcha/주의/반드시/절대/금지/예외 등 경고성 규칙 줄 수. 4+ → 4, 2-3 → 3, 1 → 2, 0 → 0.
- B5: 다른 .md/context 파일로의 링크·참조, "See also/참고/관련" 수. 3+ → 4, 2 → 3, 1 → 2, 0 → 0.

**판단 보정 포인트**: 키워드만 맞고 내용이 일반론인 경우(B4), 경로는 있지만 "왜 이 파일인지" 설명이 없는 경우(B3) 감점. 너무 짧아 사실상 빈 파일이면 B1 만점을 주지 않는다.

---

## C. Tribal Knowledge Externalization (20점)

| Score | Criteria |
|---|---|
| 0 | senior engineer, Slack, 과거 PR에만 지식 존재 |
| 5 | 일부 gotcha가 README나 comment에 흩어져 있음 |
| 10 | 반복 작업의 암묵지 일부가 문서화됨 |
| 15 | compatibility rule, naming convention, generated code rule, deprecated-but-required rule 등이 정리됨 |
| 20 | 식별된 tribal knowledge 대부분이 context file/checklist/playbook에 반영되고, AI가 질의로 회수 가능 |

**Five-Question Framework** — module마다 아래 질문에 답할 수 있으면 각 4점(총 20점):
1. What does this module configure/own?
2. What are common modification patterns?
3. What non-obvious patterns cause failures?
4. What are the cross-module dependencies?
5. What tribal knowledge is hidden in comments/history/human memory?

**자동 채점 방식**
- module 전용 텍스트 = module 내부 context/README + 전역 문서에서 module 이름·경로가 언급된 줄 ±3줄.
- 전역 텍스트 = 모든 AI context 파일, CONTRIBUTING, ADR, playbook/checklist/runbook 문서.
- 질문별 키워드가 module 전용 텍스트에 있으면 1.0, 전역 텍스트에만 있으면 0.5.
- 점수 = `round(20 × 전체 module·질문 평균)`.
- 참고 증거: 코드 내 TODO/FIXME/HACK/XXX 주석 수(주석에만 흩어진 암묵지의 신호, 점수엔 미반영).

**판단 보정 포인트**: 키워드 매칭은 "답이 있다"를 과대평가하기 쉽다. module 1-2개를 골라 5개 질문에 실제로 답할 수 있는지 문서만 보고 확인한다.

---

## D. Cross-Module Dependency & Data Flow Mapping (15점)

| Score | Criteria |
|---|---|
| 0 | 변경 영향 범위를 사람이 수동으로 추적 |
| 5 | 일부 architecture diagram 또는 dependency note 존재 |
| 10 | 주요 module 간 dependency와 ownership이 문서화됨 |
| 15 | "What depends on X?"에 대해 graph/index/map으로 답 가능. 변경이 repo/service/test/data flow에 어떻게 전파되는지 추적 가능 |

Meta 사례의 핵심 문제는 "한 field change가 six subsystems에 ripple effect를 만든다"는 점이었다.

**자동 채점 방식** (누적, 최대 15)
- +5: architecture 문서(ARCHITECTURE*, docs/**/architecture*), 다이어그램(mermaid 블록, .mmd/.puml/.dot/.drawio), 또는 본문의 dependency note(예: "Dependency direction is `api → domain`", "command와 query는 서로 참조하지 않음").
- +3: 문서에 dependency/의존/data flow/데이터 흐름/영향 범위/impact **전용 섹션**(heading).
- +2: ownership (CODEOWNERS 또는 문서 내 owner/담당 표기).
- +5: machine-readable graph/강제 수단 (dependency-map.json/yaml, .dependency-cruiser, .importlinter, ArchUnit 테스트, nx/turbo graph, madge 등).

---

## E. Verification & Quality Gates (15점)

| 항목 | Points | Full Score Criteria |
|---|---|---|
| E1. Reference Accuracy | 5 | file path, API, command hallucination 0건 |
| E2. Independent Critic Review | 4 | 최소 2-3 round의 독립 review 또는 checklist |
| E3. Task Validation | 4 | build/test/lint/typecheck/e2e 등 변경 유형별 검증 명령 제공 |
| E4. Prompt/Workflow Tests | 2 | 대표 AI task query를 실제로 테스트 |

AI-ready는 "AI가 읽기 좋은 문서"가 아니라 **검증된 문맥 인프라**다 ("zero hallucinated paths").

**자동 채점 방식**
- E1: 모든 AI context 파일의 경로 참조(backtick, 마크다운 링크)와 `./script`, `npm run X`, `make X` 명령을 실제 repo와 대조. 깨진 참조 0건 & 참조 3건 이상 → 5, 0건이나 참조 3건 미만 → 3, 깨진 비율 ≤5% → 3, ≤15% → 2, ≤30% → 1, 초과 → 0. context 파일 없음 → 0.
- E2: PR 템플릿 +1, CODEOWNERS +1, 리뷰 체크리스트/리뷰 에이전트/자동 리뷰 설정(.claude/agents/*review*, .coderabbit.yaml, dangerfile, review 워크플로) +2. 최대 4.
- E3: context 문서에 등장하는 검증 명령 종류(build/test/lint·format/typecheck/e2e) 1 → 1, 2 → 2, 3+ → 3, CI가 테스트를 실행하면 +1. 최대 4.
- E4: evals 디렉터리/evals.json/promptfoo 설정/golden task 파일 → 2, 문서에 대표 AI task 예시만 있음 → 1.

**판단 보정 포인트**: E1에서 깨진 참조로 잡힌 항목 중 예시 경로·생성 산출물·의도적 미래 경로 같은 false positive를 제거하고 재계산한다.

---

## F. Freshness & Self-Maintenance (10점)

| Score | Criteria |
|---|---|
| 0 | context가 수동 관리되며 stale 여부 불명 |
| 3 | 문서 owner가 있고 가끔 업데이트 |
| 6 | CI나 script로 broken path/reference 일부 검출 |
| 10 | 주기적으로 file path validation, coverage gap detection, critic review, stale reference repair가 자동 실행됨 |

stale context는 없는 context보다 위험할 수 있다.

**자동 채점 방식** (충족한 최고 단계)
- 2: owner 없이 context 파일이 최근 커밋 후 30일 이내에 갱신됨만 확인됨(부분 인정).
- 3: CODEOWNERS가 context 파일/문서를 커버함(루브릭의 "owner가 있고 가끔 업데이트").
- 6: CI/pre-commit/스크립트에서 링크·경로 검증(lychee, markdown-link-check, remark-validate-links, linkinator, context 검증 스크립트 등).
- 10: 위 검증이 스케줄(cron) 워크플로로 주기 실행됨.

---

## G. Agent Performance Outcomes (5점)

| Score | Criteria |
|---|---|
| 0 | AI 성능 개선 측정 없음 |
| 2 | 정성적으로 "도움 된다" 수준 |
| 3 | 대표 task success rate 또는 human intervention rate 측정 |
| 5 | tool calls, token usage, task completion time, correctness, prompt pass rate 등을 before/after로 측정 |

예시 metric: AI task pass rate, 평균 tool calls per task, 평균 tokens per task, human clarification count,
failed PR/rework rate, hallucinated file path count, time-to-first-correct-change.

**자동 채점 방식**: 문서/데이터 파일에서 metric 키워드 탐지. AI 도구 + 도움/생산성 언급 → 2, metric 1종 이상 + 숫자 → 3, metric 3종 이상 + before/after·baseline 비교 → 5.
이 스킬 자신의 파일과 출력 디렉터리는 스캔에서 제외된다.

**판단 보정 포인트**: 측정 결과가 repo 밖(대시보드, 위키)에 있다고 사용자가 알려주면 그 근거로 보정한다.

---

## Final Grade

| Score | Level | Meaning |
|---|---|---|
| 90-100 | AI-Native / Agentic-Ready | Agent가 대부분의 반복 작업을 자율 수행하고, context layer도 self-maintaining |
| 75-89 | AI-Ready | 대부분의 개발 작업에서 AI가 안정적으로 navigation, edit, verify 가능 |
| 60-74 | AI-Assisted | AI가 유용하지만 complex/domain-specific task에는 human context 필요 |
| 40-59 | AI-Fragile | 간단한 task는 가능하나 hidden rule과 dependency 때문에 오류 위험 높음 |
| <40 | AI-Hostile | tribal knowledge 의존도가 높고 AI가 추측 기반으로 작업함 |

## 핵심 개념 정의

- **AI context 파일**: AI 에이전트가 자동/명시적으로 읽도록 만든 파일. CLAUDE.md, AGENTS.md, GEMINI.md, `.github/copilot-instructions.md`, `.cursorrules`, `.cursor/rules/*`, `.windsurfrules`, `.claude/rules/*`, `.claude/skills/*/SKILL.md`, CONTEXT.md, `docs/ai/*`.
- **Primary context 파일**: 위 중 navigation 역할을 하는 파일(CLAUDE.md/AGENTS.md/GEMINI.md/copilot-instructions/.cursorrules/.windsurfrules/CONTEXT.md). B 카테고리 채점 대상.
- **예상 상승**: 그 액션 하나만 완료했을 때 오르는 점수. 항목의 남은 점수 전체가 아니라 현재 빠진 구성요소 기준으로 계산한다(예: D에서 문서만 쓰면 +3, 아키텍처 테스트까지 해야 +5 추가). 한 파일이 여러 항목을 채우면(CODEOWNERS → E2·D·F) 합산.
- **ROI**: `ROI = 예상 상승 ÷ 노력(S=1, M=2, L=3)`. 액션 리스트는 ROI 내림차순.
