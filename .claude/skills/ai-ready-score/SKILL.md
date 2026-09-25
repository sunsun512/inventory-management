---
name: ai-ready-score
description: 임의의 git 리포지토리가 AI 에이전트(Claude Code, Copilot, Cursor 등)와 일하기에 얼마나 준비되었는지 AI-Ready 루브릭(100점, 7 카테고리 — Navigation, Context 품질, Tribal Knowledge, Dependency Map, Verification, Freshness, Agent Outcomes)으로 감사하고 JSON 점수표 + 한국어 HTML 대시보드 + ROI 우선순위 액션 리스트를 만든다. 사용자가 "AI-ready 점수", "AI 친화도", "CLAUDE.md/AGENTS.md가 충분한지", "컨텍스트 문서 감사", "에이전트가 이 코드베이스에서 잘 일할 수 있을까", "AI 도입 준비도 진단", "context engineering 평가"를 물을 때, 또는 repo의 AI 컨텍스트 인프라를 점검·비교·개선 우선순위화하려 할 때 이 스킬을 사용한다. 명시적으로 "스킬"이나 "루브릭"을 말하지 않아도 repo의 AI 준비도를 평가하려는 의도면 사용한다.
---

# AI-Ready Codebase Score

리포지토리를 AI-Ready 루브릭으로 채점한다. 결과물은 세 가지다.

- `ai_ready_score.json` — 항목별 자동 점수·최종 점수·근거·부족한 점
- `ai_ready_dashboard.html` — 한국어 대시보드 (총점/등급, 카테고리 바, ROI 액션, 근거, module 커버리지)
- `ai_ready_actions.md` — ROI 순 액션 리스트 (PR/이슈에 바로 붙여 넣기 좋음)

루브릭 전문과 각 항목의 자동 채점 방식은 `references/rubric.md`에 있다. 채점을 보정하기 전에 반드시 읽는다.

## 왜 2단계(자동 → 판단 보정)인가

스크립트는 파일 존재, 키워드, 경로 대조 같은 **관찰 가능한 신호**만 본다. 그래서 빠르고 재현 가능하지만
"문서가 실제로 맞는 말을 하는가", "키워드는 있지만 내용이 일반론인가" 같은 것은 판단하지 못한다.
AI-ready의 핵심은 *검증된* 문맥이므로, 에이전트가 증거를 직접 확인하고 근거 있는 보정만 더하는 것이 이 스킬의 가치다.
보정은 반드시 이유와 함께 기록되어 대시보드에 "자동 → 최종"으로 드러나므로 점수가 설명 가능하게 유지된다.

## 워크플로

### 1. 대상과 출력 위치 정하기

- 대상 repo: 사용자가 지정한 경로, 없으면 현재 작업 디렉터리.
- 출력 디렉터리: 사용자가 지정하지 않으면 대상 repo **밖**(예: scratchpad나 `/tmp/ai-ready-<repo>`)을 쓴다.
  감사 대상 repo에 파일을 만들면 git status를 더럽히기 때문이다. repo 안에 남기길 원하면 그대로 따른다
  (출력 디렉터리는 스캔에서 자동 제외된다).

### 2. 자동 채점 (pass 1)

```bash
python3 <skill-dir>/scripts/score.py <repo> --out <out-dir>
```

표준 라이브러리만 쓰므로 설치할 것이 없다. 콘솔에 카테고리별 점수와 상위 액션이 출력된다.

### 3. 증거 검토와 판단 보정

`<out-dir>/ai_ready_score.json`을 읽고 아래를 확인한다. 전부 다 읽을 필요는 없다 — 점수에 영향이 큰 곳부터 본다.

1. **module 목록이 맞는가** (`modules`): 핵심 module이 잘못 잡혔으면(테스트 픽스처, 생성 코드, 래퍼 디렉터리 등) 올바른 목록 기준으로 A와 C를 다시 판단한다.
2. **primary context 파일 직접 읽기** (CLAUDE.md, AGENTS.md 등): B1–B5를 루브릭 기준으로 확인한다. 특히
   - B3: 참조된 파일이 "실제 수정에 필요한 핵심 파일"인지
   - B4: 경고 문구가 이 repo 고유의 hidden rule인지, 아무 repo에나 쓸 수 있는 일반론인지
3. **stale 주장 찾기**: context 문서가 코드와 모순되는 말을 하는지 본다(예: "아직 DB 연동 없음"이라고 쓰여 있는데 실제로는 repository 코드가 있음). 이런 주장은 경로 검사(E1)로는 잡히지 않지만 AI를 가장 크게 오도한다. 발견하면 E1과 F를 감점하고 액션으로 올린다.
4. **E1 깨진 참조 false positive 제거**: 예시 경로, 빌드 산출물, 의도적인 미래 경로는 제외하고 다시 계산한다.
5. **C Five-Question 샘플 검증**: module 1–2개를 골라 문서만 보고 5개 질문에 실제로 답할 수 있는지 확인한다. 키워드 매칭은 과대평가 경향이 있다.
6. **D/F/G의 repo 밖 증거**: 사용자가 위키·대시보드 등 repo 밖 근거를 알려주면 반영한다. 모르는 것을 추측해 올리지는 않는다.

보정할 항목만 `overrides.json`으로 쓴다 (item id: `A`, `B1`–`B5`, `C`, `D`, `E1`–`E4`, `F`, `G`):

```json
{
  "summary": "한 문단 총평: 가장 큰 강점, 가장 큰 리스크, 첫 번째로 할 일",
  "items": {
    "B4": {"score": 2, "reason": "경고 문구 2줄이 모두 일반론(‘테스트를 작성하라’)이고 repo 고유 규칙이 아님"},
    "E1": {"score": 2, "reason": "CLAUDE.md의 'DB 연동 없음' 서술이 실제 JPA 코드와 모순 (stale claim)"}
  },
  "actions": [
    {"item": "E1", "title": "CLAUDE.md의 stale 서술 수정", "detail": "Architecture 섹션의 'no persistence layer' 문장을 현재 구조로 교체", "effort": "S"}
  ]
}
```

- `reason`은 한국어로, 확인한 파일/줄을 근거로 쓴다. 근거 없이 점수를 올리지 않는다.
- `actions`에 넣은 item은 그 item의 자동 액션을 대체한다. 자동 액션이 이미 충분히 구체적이면 넣지 않는다.
  `targets`(대상 파일/module 목록)와 `expected_gain`(생략 시 항목의 남은 점수, 그 이상은 잘림)을 줄 수 있다.
- `effort`: `S`(1시간 이내) / `M`(하루 이내) / `L`(그 이상). ROI = 예상 상승 ÷ 노력(1/2/3).
- `verification`: 3단계에서 context 문서의 서술을 코드와 대조했다면 그 기록을 남긴다. 대시보드의 "검증 로그"에
  표시되어 스크립트 점수와 리뷰어가 직접 확인한 사실이 구분된다.

```json
"verification": [
  {"claim": "command와 query는 서로 참조하지 않음", "result": "일치", "evidence": "*/command → .query. import 0건"},
  {"claim": "persistence layer 없음", "result": "불일치", "evidence": "product/domain/ProductRepository.java 존재"}
]
```
`result`가 "일치"로 시작하면 초록, 그 외는 빨강으로 표시된다.

### 4. 최종 산출 (pass 2)

```bash
python3 <skill-dir>/scripts/score.py <repo> --out <out-dir> --overrides <out-dir>/overrides.json \
  [--previous <이전 ai_ready_score.json> --previous-label "develop"]
```

`--previous`를 주면 총점과 카테고리별 변화(▲/▼)가 표시된다. 브랜치 효과를 보여줄 때는 기준 브랜치를
**같은 스크립트 버전으로** 다시 채점한 결과와 비교한다 — 예전 JSON과 비교하면 채점 로직 변경분이 섞인다
(대시보드는 스크립트 버전이 다르면 경고를 띄운다). 기준 브랜치는 임시 worktree로 채점하고 바로 지운다:

```bash
git worktree add --detach <tmp>/base <base-branch>
python3 <skill-dir>/scripts/score.py <tmp>/base --out <tmp>/base-report   # 필요하면 같은 기준으로 보정
git worktree remove <tmp>/base
```

### 5. 사용자에게 보고

짧게 보고한다:

- 총점/등급 (보정이 있었다면 자동 점수와 차이, 가장 큰 보정 이유 1–2개)
- 카테고리별 점수 한 줄 표
- ROI 상위 3–5개 액션
- 산출물 경로 3개

대시보드를 팀과 공유할 것 같으면 HTML 파일을 Artifact로 게시할지 한 줄로 제안한다.

## 참고

- 점수는 repo 간 **상대 비교와 개선 추적**에 가장 유용하다. 같은 repo를 개선 전후로 다시 채점해 `total`/카테고리 변화를 보여주면 G(Agent Outcomes)의 근거로도 쓸 수 있다.
- 이 스킬 디렉터리, 출력 디렉터리, 그리고 repo에 커밋된 이전 리포트(`ai_ready_score.json`이 있는 디렉터리)는 스캔에서 자동 제외된다(루브릭 키워드가 G 등을 오염시키지 않도록).
- `--top N`으로 액션 개수를 조절한다(기본 10).
