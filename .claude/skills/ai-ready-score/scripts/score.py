#!/usr/bin/env python3
"""AI-Ready Codebase scorer.

Audits a git repository against the AI-Ready rubric (100 points, 7 categories;
see ../references/rubric.md) using static heuristics, then writes:

  <out>/ai_ready_score.json      machine-readable score sheet (auto + final scores, evidence)
  <out>/ai_ready_dashboard.html  Korean HTML dashboard
  <out>/ai_ready_actions.md      ROI-ordered action list (Korean)

Auto scores are a first-pass estimate. Pass --overrides <file.json> to apply
judgment-based corrections after reviewing the evidence:

  {
    "items":   {"B4": {"score": 2, "reason": "경고 문구가 일반론뿐"}},
    "actions": [{"item": "D", "title": "...", "detail": "...", "effort": "M"}],
    "summary": "한 문단 총평"
  }

Standard library only. Usage:
  python3 score.py <repo> [--out DIR] [--overrides FILE] [--top N]
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path, PurePosixPath

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SCRIPT_VERSION = "1.3.0"  # bump when scoring logic changes, so score deltas can be attributed

EXCLUDE_DIRS = {
    ".git", "node_modules", "build", "dist", "target", "out", "bin", "obj", ".gradle",
    "venv", ".venv", "env", "__pycache__", ".mypy_cache", ".pytest_cache", ".tox",
    "vendor", ".idea", ".vscode", ".next", ".nuxt", "coverage", ".terraform", ".cache",
    "Pods", "DerivedData", ".dart_tool",
}

CODE_EXT = {
    ".java", ".kt", ".kts", ".scala", ".groovy", ".py", ".js", ".jsx", ".ts", ".tsx", ".mjs",
    ".cjs", ".go", ".rs", ".rb", ".php", ".cs", ".fs", ".swift", ".m", ".mm", ".c", ".cc",
    ".cpp", ".h", ".hpp", ".ex", ".exs", ".erl", ".clj", ".dart", ".lua", ".vue", ".svelte",
}

TEST_DIR_NAMES = {"test", "tests", "__tests__", "spec", "specs", "testing", "e2e", "it", "androidTest"}

MANIFESTS = {
    "package.json", "build.gradle", "build.gradle.kts", "pom.xml", "pyproject.toml", "setup.py",
    "go.mod", "Cargo.toml", "composer.json", "Gemfile", "mix.exs", "pubspec.yaml", "Package.swift",
}

PRIMARY_CONTEXT_NAMES = {
    "CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", "GEMINI.md", "copilot-instructions.md",
    ".cursorrules", ".windsurfrules", "CONTEXT.md", "AI_CONTEXT.md",
}

DOC_EXT = {".md", ".mdx", ".mdc", ".rst", ".txt", ".adoc"}

CI_MARKERS = (".github/workflows/", ".gitlab-ci.yml", "Jenkinsfile", ".circleci/", "azure-pipelines.yml",
              "bitbucket-pipelines.yml", ".buildkite/", ".drone.yml", ".travis.yml")

CMD_PREFIX = re.compile(
    r"^\s*(\$\s+)?(\./|bash |sh |npm |npx |yarn |pnpm |bun |make |gradle |\./gradlew|mvn |\./mvnw|python3? |pip |"
    r"uv |poetry |pytest|go |cargo |docker |docker-compose |kubectl |bundle |rake |rails |dotnet |swift |"
    r"flutter |dart |mix |composer |php |node |deno |tox |nox |ruff |mypy |tsc |eslint |prettier |git |"
    r"terraform |helm |just |task |sbt |bazel |buck2? |nx |turbo |lerna )"
)

GOTCHA_RE = re.compile(
    r"(\bnever\b|\bdon'?t\b|\bdo not\b|\bmust not\b|\bmust\b|\bavoid\b|\bgotcha|\bcaveat|\bpitfall|"
    r"\bwarning\b|\bimportant\b|\bcareful\b|\bbeware\b|\bexcept(ion)?\b|\bunless\b|"
    r"주의|반드시|절대|금지|하지\s?마|하지\s?않|안\s?됨|안\s?된다|예외|함정|중요|주의사항|꼭)",
    re.IGNORECASE,
)

# Five-Question Framework keyword sets (Category C)
FIVE_Q = {
    "q1_owns": re.compile(r"(responsib|\bowns?\b|purpose|\brole\b|handles|configures|역할|책임|담당|관리|목적|기능)", re.I),
    "q2_modify": re.compile(r"(how to|to add|adding|when (adding|changing|modifying)|workflow|steps?\b|recipe|"
                            r"추가\s?(하|할|시)|변경\s?(시|하)|수정\s?(시|하)|절차|순서|방법|패턴)", re.I),
    "q3_failures": GOTCHA_RE,
    "q4_deps": re.compile(r"(depend|import|calls?\b|uses\b|consum|publish|downstream|upstream|integrat|"
                          r"의존|호출|참조|연동|사용한다|영향)", re.I),
    "q5_tribal": re.compile(r"(histor|legacy|deprecat|compat|backward|because|reason|\bwhy\b|background|context:|"
                            r"\badr\b|decision|이유|배경|호환|레거시|과거|왜|결정|때문)", re.I),
}
FIVE_Q_LABELS = {
    "q1_owns": "소유 범위",
    "q2_modify": "수정 패턴",
    "q3_failures": "실패 함정",
    "q4_deps": "의존성",
    "q5_tribal": "배경·이력",
}
FIVE_Q_QUESTIONS = {
    "q1_owns": "What does this module configure/own?",
    "q2_modify": "What are common modification patterns?",
    "q3_failures": "What non-obvious patterns cause failures?",
    "q4_deps": "What are the cross-module dependencies?",
    "q5_tribal": "What tribal knowledge is hidden in comments/history/human memory?",
}

METRIC_PATTERNS = {
    "pass_rate": re.compile(r"(pass\s?rate|success\s?rate|pass@\d|성공률|통과율)", re.I),
    "tool_calls": re.compile(r"(tool\s?calls?|도구\s?호출)", re.I),
    "tokens": re.compile(r"(tokens?\s?(per|/)\s?task|token usage|토큰\s?(사용|수))", re.I),
    "time": re.compile(r"(completion time|time[- ]to[- ]first|task time|소요\s?시간|완료\s?시간)", re.I),
    "intervention": re.compile(r"(human intervention|clarification|개입|재질문)", re.I),
    "rework": re.compile(r"(rework|failed pr|재작업)", re.I),
    "hallucination": re.compile(r"(hallucinat|환각)", re.I),
}

# Rubric level text per category (score threshold, meaning). B and E are sums of sub-items.
LEVELS = {
    "A": [(15, "모든 핵심 module/workflow에 navigation guide, 1-2 hops 안에 찾음"),
          (10, "대부분의 핵심 module에 역할·entry point·related files 정리"),
          (5, "주요 module 일부에 README/context 존재"), (0, "AI가 grep/search로 구조를 추측")],
    "C": [(20, "tribal knowledge 대부분이 context/checklist/playbook에 반영, 질의로 회수 가능"),
          (15, "compatibility·naming·generated code·deprecated rule 등이 정리됨"),
          (10, "반복 작업의 암묵지 일부가 문서화됨"), (5, "일부 gotcha가 README·주석에 흩어져 있음"),
          (0, "senior engineer·Slack·과거 PR에만 지식 존재")],
    "D": [(15, "'What depends on X?'에 graph/index/map으로 답하고 전파 경로 추적 가능"),
          (10, "주요 module 간 dependency와 ownership 문서화"),
          (5, "일부 architecture diagram 또는 dependency note 존재"), (0, "변경 영향 범위를 사람이 수동 추적")],
    "F": [(10, "경로 검증·coverage gap·critic review·stale 수리가 주기적으로 자동 실행"),
          (6, "CI나 script로 broken path/reference 일부 검출"), (3, "문서 owner가 있고 가끔 업데이트"),
          (0, "수동 관리, stale 여부 불명")],
    "G": [(5, "tool calls·tokens·완료 시간·정확도 등을 before/after로 측정"),
          (3, "대표 task success rate 또는 human intervention rate 측정"),
          (2, "정성적으로 '도움 된다' 수준"), (0, "AI 성능 개선 측정 없음")],
}


def level_text(cat_id: str, score: float) -> str:
    if cat_id not in LEVELS:
        return "하위 항목 합산"
    for threshold, text in LEVELS[cat_id]:
        if score >= threshold:
            return text if score == threshold else f"{text} (다음 단계 진행 중)"
    return LEVELS[cat_id][-1][1]


EFFORT_POINTS = {"S": 1, "M": 2, "L": 3}
EFFORT_KO = {"S": "작음", "M": "보통", "L": "큼"}

CATEGORIES = [
    ("A", "AI Navigation & Coverage", "AI 탐색성 & 커버리지", 15),
    ("B", "Context Document Quality", "컨텍스트 문서 품질", 20),
    ("C", "Tribal Knowledge Externalization", "암묵지 외재화", 20),
    ("D", "Cross-Module Dependency & Data Flow Mapping", "모듈 간 의존성 & 데이터 흐름", 15),
    ("E", "Verification & Quality Gates", "검증 & 품질 게이트", 15),
    ("F", "Freshness & Self-Maintenance", "최신성 & 자동 유지", 10),
    ("G", "Agent Performance Outcomes", "에이전트 성과 측정", 5),
]

ITEMS = {  # id -> (category, name_ko, max)
    "A": ("A", "Navigation Coverage", 15),
    "B1": ("B", "B1. 간결성 (Conciseness)", 4),
    "B2": ("B", "B2. 빠른 명령어 (Quick Commands)", 4),
    "B3": ("B", "B3. 핵심 파일 (Key Files)", 4),
    "B4": ("B", "B4. 비자명 패턴 (Non-Obvious Patterns)", 4),
    "B5": ("B", "B5. 상호 참조 (See Also)", 4),
    "C": ("C", "Five-Question Framework", 20),
    "D": ("D", "Dependency & Data Flow Map", 15),
    "E1": ("E", "E1. 참조 정확성 (Reference Accuracy)", 5),
    "E2": ("E", "E2. 독립 리뷰 (Critic Review)", 4),
    "E3": ("E", "E3. 작업 검증 명령 (Task Validation)", 4),
    "E4": ("E", "E4. 프롬프트/워크플로 테스트", 2),
    "F": ("F", "Freshness Automation", 10),
    "G": ("G", "Outcome Measurement", 5),
}

GRADES = [
    (90, "AI-Native / Agentic-Ready", "Agent가 대부분의 반복 작업을 자율 수행하고, context layer도 self-maintaining"),
    (75, "AI-Ready", "대부분의 개발 작업에서 AI가 안정적으로 navigation, edit, verify 가능"),
    (60, "AI-Assisted", "AI가 유용하지만 complex/domain-specific task에는 human context 필요"),
    (40, "AI-Fragile", "간단한 task는 가능하나 hidden rule과 dependency 때문에 오류 위험 높음"),
    (0, "AI-Hostile", "tribal knowledge 의존도가 높고 AI가 추측 기반으로 작업함"),
]


# ---------------------------------------------------------------------------
# Repo scanning
# ---------------------------------------------------------------------------

class Repo:
    def __init__(self, root: Path, exclude_prefixes: list[str]):
        self.root = root
        self.exclude_prefixes = [p.rstrip("/") + "/" for p in exclude_prefixes if p]
        self.files = self._list_files()
        self.file_set = set(self.files)
        self.basenames = Counter(PurePosixPath(f).name for f in self.files)
        self.dirs = set()
        for f in self.files:
            parent = PurePosixPath(f).parent
            while str(parent) not in (".", ""):
                self.dirs.add(str(parent))
                parent = parent.parent
        self._text_cache: dict[str, str] = {}

    def _git(self, *args: str) -> str:
        try:
            return subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, text=True,
                                  timeout=60).stdout
        except (OSError, subprocess.SubprocessError):
            return ""

    def _list_files(self) -> list[str]:
        out = self._git("ls-files", "--cached", "--others", "--exclude-standard")
        files = [l.strip() for l in out.splitlines() if l.strip()]
        if not files:  # not a git repo, or git unavailable
            for dirpath, dirnames, filenames in os.walk(self.root):
                dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
                for name in filenames:
                    files.append(Path(dirpath, name).relative_to(self.root).as_posix())
        result = []
        for f in files:
            parts = PurePosixPath(f).parts
            if any(p in EXCLUDE_DIRS for p in parts[:-1]):
                continue
            if any(f.startswith(p) for p in self.exclude_prefixes):
                continue
            if (self.root / f).is_file():
                result.append(f)
        return sorted(result)[:60000]

    def text(self, rel: str, limit: int = 300_000) -> str:
        if rel not in self._text_cache:
            try:
                with open(self.root / rel, "r", encoding="utf-8", errors="replace") as fh:
                    self._text_cache[rel] = fh.read(limit)
            except OSError:
                self._text_cache[rel] = ""
        return self._text_cache[rel]

    def exists(self, rel: str) -> bool:
        rel = rel.strip("/")
        return rel in self.file_set or rel in self.dirs or (self.root / rel).exists()

    def last_commit_date(self, rel: str | None = None) -> dt.datetime | None:
        args = ["log", "-1", "--format=%cI"]
        if rel:
            args += ["--", rel]
        out = self._git(*args).strip()
        try:
            return dt.datetime.fromisoformat(out) if out else None
        except ValueError:
            return None


def is_hidden(rel: str) -> bool:
    return any(p.startswith(".") for p in PurePosixPath(rel).parts[:-1])


def is_test_path(rel: str) -> bool:
    parts = PurePosixPath(rel).parts
    name = parts[-1].lower()
    return (any(p in TEST_DIR_NAMES for p in parts[:-1])
            or re.search(r"(^test_|_test\.|\.test\.|\.spec\.|tests?\.(java|kt)$)", name) is not None)


def is_ai_context(rel: str) -> bool:
    p = PurePosixPath(rel)
    s = rel
    if p.name in PRIMARY_CONTEXT_NAMES:
        return True
    return (s.startswith(".claude/rules/") or s.startswith(".cursor/rules/") or s.startswith(".github/instructions/")
            or (s.startswith(".claude/skills/") and p.name == "SKILL.md")
            or s.startswith(".claude/agents/") or s.startswith(".claude/commands/")
            or s.startswith("docs/ai/") or s.startswith(".ai/")) and p.suffix in DOC_EXT | {""}


def is_primary_context(rel: str) -> bool:
    return PurePosixPath(rel).name in PRIMARY_CONTEXT_NAMES


def is_doc(rel: str) -> bool:
    return PurePosixPath(rel).suffix.lower() in DOC_EXT or PurePosixPath(rel).name in PRIMARY_CONTEXT_NAMES


def is_readme(rel: str) -> bool:
    return PurePosixPath(rel).name.lower().startswith("readme")


# ---------------------------------------------------------------------------
# Link-following context discovery
# ---------------------------------------------------------------------------

LINK_HOPS = 2  # rubric A: "어디를 봐야 하는가"를 1-2 hops 안에 찾을 수 있음
RULE_DIR_PREFIXES = (".claude/rules/", ".cursor/rules/", ".github/instructions/")
LINKABLE_DOC_EXT = {".md", ".mdx", ".mdc"}


def is_link_seed(rel: str) -> bool:
    """Files an agent reads on its own; docs they link to are part of the AI context."""
    return is_primary_context(rel) or (rel.startswith(RULE_DIR_PREFIXES) and is_doc(rel))


def doc_link_targets(text: str) -> list[str]:
    """Raw doc-link targets: markdown link targets and backticked .md paths (fences excluded)."""
    no_fence = FENCE_RE.sub("", text)
    targets = []
    for m in MDLINK_RE.finditer(no_fence):
        targets.append(m.group(1).strip("<>"))
    for m in BACKTICK_RE.finditer(no_fence):
        tok = m.group(1).strip()
        if PurePosixPath(tok.split("#")[0]).suffix.lower() in LINKABLE_DOC_EXT:
            targets.append(tok)
    out = []
    for t in targets:
        if re.match(r"^[a-z][\w+.-]*:", t, re.I) or t.startswith(("#", "//")):  # URL, mailto:, anchor
            continue
        t = t.split("#")[0].split("?")[0]
        if t and " " not in t and not any(c in t for c in "<>{}*$"):
            out.append(t)
    return list(dict.fromkeys(out))


def resolve_doc_link(repo: Repo, target: str, from_file: str) -> str | None:
    """Resolve a link target to a doc file in the repo: relative to the linking file first,
    then relative to the repo root (backticked paths in CLAUDE.md-style docs are usually root-relative)."""
    from urllib.parse import unquote
    target = unquote(target)
    base = PurePosixPath(from_file).parent
    cands = [target.lstrip("/")] if target.startswith("/") else [str(base / target), target]
    for c in cands:
        norm = os.path.normpath(c).replace(os.sep, "/")
        if norm.startswith("..") or norm == ".":
            continue
        if norm in repo.file_set and is_doc(norm):
            return norm
        if norm in repo.dirs:  # link to a directory → its README
            readme = next((f"{norm}/{n}" for n in ("README.md", "readme.md", "index.md")
                           if f"{norm}/{n}" in repo.file_set), None)
            if readme:
                return readme
    return None


def discover_linked_context(repo: Repo, seeds: list[str], hops: int = LINK_HOPS) -> dict[str, list[str]]:
    """BFS over doc links from the seed files, up to `hops` hops.
    Returns {reached doc: [seed, ..., doc]} for every doc that is not itself a seed."""
    seeds = sorted(dict.fromkeys(seeds), key=lambda f: (not is_primary_context(f), "/" in f, f))
    paths: dict[str, list[str]] = {s: [s] for s in seeds}
    frontier = list(seeds)
    for _ in range(hops):
        nxt = []
        for f in frontier:
            for t in doc_link_targets(repo.text(f)):
                doc = resolve_doc_link(repo, t, f)
                if doc and doc not in paths:
                    paths[doc] = paths[f] + [doc]
                    nxt.append(doc)
        frontier = nxt
    return {d: p for d, p in paths.items() if len(p) > 1}


def first_h1(text: str) -> str:
    """First ATX H1 outside code fences and YAML front matter."""
    text = FENCE_RE.sub("", re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S))
    m = re.search(r"^#[ \t]+(.+?)[ \t#]*$", text, re.M)
    return m.group(1).strip() if m else ""


MODULE_UNIT_WORDS = (r"(package|module|패키지|모듈|도메인|domain|service|서비스|component|컴포넌트|layer|레이어|계층|"
                     r"app|앱|guide|가이드|directory|디렉터리|폴더|folder)")


def h1_names_module(h1: str, mod: dict, use_name: bool) -> bool:
    """Conservative: the H1 is the module name/path itself, names it in backticks, contains its full
    path, or pairs the name with a unit word (`# stock 패키지`, `# Module stock`). A bare word
    inside a longer title (`# Common Pitfalls`) does not count."""
    keys = {mod["path"].lower(), mod.get("label", mod["path"]).lower()} | ({mod["name"].lower()} if use_name else set())
    keys.discard(".")
    low = h1.lower()
    plain = re.sub(r"[`*_\"'“”:()\[\].,!?-]+", " ", low).strip()
    for k in keys:
        if plain == k or f"`{k}`" in low or f"`{k}/`" in low:
            return True
        if "/" in k and re.search(rf"(?<![\w/-]){re.escape(k)}(?![\w-])", low):
            return True
        w = rf"(?<![\w/-]){re.escape(k)}/?(?![\w/-])"
        if re.search(rf"{w}\s*{MODULE_UNIT_WORDS}(?!\w)|(?<!\w){MODULE_UNIT_WORDS}\s+{w}", plain):
            return True
    return False


def dedicated_docs(repo: Repo, mod: dict, ctx_docs: list[str], modules: list[dict]) -> list[str]:
    """AI context docs dedicated to one module, wherever they live: file stem equals the module name/label
    (`stock.md`, `docs/stock/README.md`), or the first H1 names this module and no other module."""
    names = Counter(m["name"].lower() for m in modules)
    use_name = names[mod["name"].lower()] == 1  # ambiguous names (a/api, b/api) only match by path
    keys = {mod.get("label", mod["path"]).lower()} | ({mod["name"].lower()} if use_name else set())
    out = []
    for f in ctx_docs:
        p = PurePosixPath(f)
        stem = p.stem.lower()
        if stem in keys or (stem in {"readme", "index", "claude", "agents"} and p.parent.name.lower() in keys
                            and str(p.parent) != "."):
            out.append(f)
            continue
        h1 = first_h1(repo.text(f, 20_000))
        if h1 and h1_names_module(h1, mod, use_name) and not any(
                o is not mod and h1_names_module(h1, o, names[o["name"].lower()] == 1) for o in modules):
            out.append(f)
    return out


# ---------------------------------------------------------------------------
# Module detection
# ---------------------------------------------------------------------------

def detect_modules(repo: Repo) -> list[dict]:
    manifest_dirs = sorted({str(PurePosixPath(f).parent) for f in repo.files
                            if (PurePosixPath(f).name in MANIFESTS or f.endswith(".csproj"))
                            and not is_hidden(f) and not is_test_path(f)} - {"."})
    code = [f for f in repo.files if PurePosixPath(f).suffix in CODE_EXT and not is_hidden(f) and not is_test_path(f)]
    counts = Counter()
    if len(manifest_dirs) >= 2:
        for f in code:
            owner = max((d for d in manifest_dirs if f.startswith(d + "/")), key=len, default=None)
            if owner:
                counts[owner] += 1
        mods = [{"path": d, "name": PurePosixPath(d).name, "code_files": counts.get(d, 0), "kind": "manifest"}
                for d in manifest_dirs]
        mods.sort(key=lambda m: -m["code_files"])
        return mods[:30]

    if not code:
        return []
    parts_list = [PurePosixPath(f).parent.parts for f in code]
    prefix: list[str] = []
    for i in range(min(len(p) for p in parts_list)):
        seg = {p[i] for p in parts_list}
        if len(seg) == 1:
            prefix.append(parts_list[0][i])
        else:
            break
    # Descend while one child dir holds ~all code (e.g. src/ → src/main/java/com/acme/app/)
    # so modules are the meaningful sub-packages, not a single wrapper directory.
    while True:
        groups = Counter(p[len(prefix)] for p in parts_list if len(p) > len(prefix))
        if not groups:
            break
        dominant, n = groups.most_common(1)[0]
        if n < 2 or n < 0.9 * len(parts_list):
            break
        prefix.append(dominant)
        parts_list = [p for p in parts_list if len(p) >= len(prefix) and p[len(prefix) - 1] == dominant]
    mods = []
    for child, n in groups.most_common(30):
        if n >= 2:
            path = "/".join(prefix + [child])
            mods.append({"path": path, "name": child, "code_files": n, "kind": "source-dir"})
    if len(mods) < 2:
        path = "/".join(prefix) or "."
        return [{"path": path, "name": PurePosixPath(path).name or repo.root.name,
                 "code_files": len(code), "kind": "single"}]
    return mods


def common_module_prefix(modules: list[dict]) -> str:
    """Shared parent directory of all modules, shown once instead of on every row."""
    if len(modules) < 2:
        return ""
    parts = [PurePosixPath(m["path"]).parts for m in modules]
    prefix = []
    for segs in zip(*parts):
        if len(set(segs)) != 1:
            break
        prefix.append(segs[0])
    if any(len(p) == len(prefix) for p in parts):  # a module is the prefix itself
        prefix = prefix[:-1]
    return "/".join(prefix)


def module_mention_re(mod: dict) -> re.Pattern:
    name = re.escape(mod["name"])
    path = re.escape(mod["path"])
    return re.compile(rf"({path}|(?<![\w-]){name}(?![\w-]))", re.I)


def mention_windows(text: str, pattern: re.Pattern, radius: int = 3) -> str:
    lines = text.splitlines()
    keep = set()
    for i, line in enumerate(lines):
        if pattern.search(line):
            keep.update(range(max(0, i - radius), min(len(lines), i + radius + 1)))
    return "\n".join(lines[i] for i in sorted(keep))


# ---------------------------------------------------------------------------
# Text analysis helpers
# ---------------------------------------------------------------------------

FENCE_RE = re.compile(r"```([\w+-]*)[^\n]*\n(.*?)```", re.S)
BACKTICK_RE = re.compile(r"(?<!`)`([^`\n]+)`(?!`)")
MDLINK_RE = re.compile(r"\]\(([^)\s]+)\)")
REF_EXT = {".md", ".java", ".kt", ".py", ".ts", ".tsx", ".js", ".jsx", ".go", ".rs", ".rb", ".yml", ".yaml",
           ".json", ".toml", ".gradle", ".xml", ".sql", ".sh", ".properties", ".cs", ".swift", ".php", ".mdc",
           ".txt", ".cfg", ".ini", ".env", ".html", ".css", ".scss", ".vue", ".kts", ".proto", ".graphql"}


def estimate_tokens(text: str) -> int:
    ascii_chars = sum(1 for c in text if ord(c) < 128)
    return int(ascii_chars / 4 + (len(text) - ascii_chars) * 0.8)


def command_lines(text: str) -> list[tuple[str, bool]]:
    """Return (command, has_description) for command-looking lines inside code fences."""
    cmds = []
    for m in FENCE_RE.finditer(text):
        lang = m.group(1).lower()
        body_lines = m.group(2).splitlines()
        shell = lang in {"bash", "sh", "shell", "zsh", "console", "powershell", "ps1", "cmd", "fish"}
        prev_comment = False
        for line in body_lines:
            stripped = line.strip()
            if not stripped:
                prev_comment = False
                continue
            if stripped.startswith("#") or stripped.startswith("//") or stripped.startswith("REM "):
                prev_comment = True
                continue
            if shell or CMD_PREFIX.match(stripped):
                has_desc = prev_comment or bool(re.search(r"\s#\s*\S", stripped))
                cmds.append((stripped, has_desc))
            prev_comment = False
    return cmds


def looks_like_path(tok: str) -> bool:
    tok = tok.strip().strip("'\"")
    if not tok or " " in tok or len(tok) > 200:
        return False
    if re.match(r"^[a-z]+://", tok) or tok.startswith(("~", "/", "$", "@", "#", "-")) or "@" in tok:
        return False
    if any(c in tok for c in "<>{}*?|=()[],;\\") or "..." in tok:
        return False
    low = tok.lower()
    if "path/to" in low or low.startswith(("example", "your", "my-")):
        return False
    suffix = PurePosixPath(tok).suffix.lower()
    if "/" in tok:
        first = PurePosixPath(tok.lstrip("./")).parts[0] if tok.lstrip("./") else ""
        if first in EXCLUDE_DIRS:
            return False
        # packages like com.example.foo, or API routes like /api/v1 are filtered above
        return bool(re.match(r"^[\w.\-/]+$", tok))
    return suffix in REF_EXT and bool(re.match(r"^[\w.\-]+$", tok)) and not re.match(r"^\d", tok)


def extract_path_refs(text: str) -> list[str]:
    refs = []
    # strip fenced blocks for backtick scanning (commands handled separately)
    no_fence = FENCE_RE.sub("", text)
    for m in BACKTICK_RE.finditer(no_fence):
        tok = m.group(1).strip()
        if looks_like_path(tok):
            refs.append(tok)
    for m in MDLINK_RE.finditer(text):
        tok = m.group(1).split("#")[0]
        if tok and looks_like_path(tok):
            refs.append(tok)
    return list(dict.fromkeys(refs))


def resolve_ref_kind(repo: Repo, ref: str, from_file: str) -> str | None:
    """Return "file", "dir", or None (broken) for a path-like reference in a context doc."""
    ref = ref.strip().strip("'\"").rstrip(":")
    if ref.startswith("./"):
        ref = ref[2:]
    dir_hint = ref.endswith("/")
    ref = ref.rstrip("/")
    base = PurePosixPath(from_file).parent
    # exact path, relative to the repo root or to the referencing file (normalizes ../)
    for c in (ref, str(base / ref)):
        try:
            norm = os.path.normpath(c).replace(os.sep, "/")
        except ValueError:
            continue
        if norm.startswith(".."):
            continue
        if (norm in repo.file_set or (repo.root / norm).is_file()) and not dir_hint:
            return "file"
        if norm in repo.dirs or (repo.root / norm).is_dir():
            return "dir"
    if "/" not in ref:
        if repo.basenames.get(ref, 0) > 0 and not dir_hint:
            return "file"
        return "dir" if any(PurePosixPath(d).name == ref for d in repo.dirs) else None
    # suffix match for paths given relative to some source root
    if not dir_hint and any(f.endswith("/" + ref) for f in repo.files):
        return "file"
    if any(d.endswith("/" + ref) for d in repo.dirs):
        return "dir"
    # class-style refs written without an extension, e.g. `common/exception/ErrorCode` → ErrorCode.java
    if not PurePosixPath(ref).suffix and not dir_hint:
        for f in repo.files:
            p = PurePosixPath(f)
            if p.suffix in CODE_EXT:
                stem = str(p.with_suffix(""))
                if stem == ref or stem.endswith("/" + ref):
                    return "file"
    return None


def resolve_ref(repo: Repo, ref: str, from_file: str) -> bool:
    return resolve_ref_kind(repo, ref, from_file) is not None


def check_commands(repo: Repo, cmds: list[str]) -> tuple[int, list[str]]:
    """Check executable references inside commands. Returns (checked_count, broken)."""
    pkg_scripts = set()
    if "package.json" in repo.file_set:
        try:
            pkg_scripts = set(json.loads(repo.text("package.json")).get("scripts", {}).keys())
        except (ValueError, AttributeError):
            pass
    make_targets = set()
    for mk in ("Makefile", "makefile", "GNUmakefile"):
        if mk in repo.file_set:
            make_targets |= set(re.findall(r"^([\w.\-/]+)\s*:(?!=)", repo.text(mk), re.M))
    checked, broken = 0, []
    for cmd in cmds:
        cmd = re.sub(r"^\$\s+", "", cmd)
        first = cmd.split()[0] if cmd.split() else ""
        if first.startswith("./") or re.match(r"^(scripts|bin|tools)/", first):
            checked += 1
            if not repo.exists(first[2:] if first.startswith("./") else first):
                broken.append(f"`{first}` (명령 실행 파일 없음)")
        m = re.match(r"^(npm run|yarn|pnpm( run)?|bun run)\s+([\w:\-.]+)", cmd)
        if m and pkg_scripts:
            name = m.group(3)
            builtin = {"install", "add", "remove", "test", "start", "build", "dlx", "exec", "run", "i", "ci", "init"}
            if name not in builtin:
                checked += 1
                if name not in pkg_scripts:
                    broken.append(f"`{m.group(0)}` (package.json scripts에 없음)")
        m = re.match(r"^make\s+([\w.\-/]+)", cmd)
        if m and make_targets:
            checked += 1
            if m.group(1) not in make_targets:
                broken.append(f"`make {m.group(1)}` (Makefile target 없음)")
    return checked, broken


def band(value: float, bands: list[tuple[float, int]], default: int = 0) -> int:
    for threshold, pts in bands:
        if value >= threshold:
            return pts
    return default


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

def score_repo(repo: Repo) -> dict:
    files = repo.files
    modules = detect_modules(repo)
    module_prefix = common_module_prefix(modules)
    for m in modules:
        m["label"] = m["path"][len(module_prefix) + 1:] if module_prefix else m["path"]
    static_ctx = [f for f in files if is_ai_context(f)]
    primary = [f for f in static_ctx if is_primary_context(f)]
    # Docs reachable from CLAUDE.md/AGENTS.md/rules via links (≤ LINK_HOPS) are AI context too.
    linked = discover_linked_context(repo, [f for f in static_ctx if is_link_seed(f)])
    ai_ctx = static_ctx + sorted(f for f in linked if f not in static_ctx)
    readmes = [f for f in files if is_readme(f) and is_doc(f)]
    docs = [f for f in files if is_doc(f)]
    ci_files = [f for f in files if any(f.startswith(m) or f == m or f.endswith("/" + m) for m in CI_MARKERS)]
    ci_text = "\n".join(repo.text(f) for f in ci_files)
    ai_text = "\n".join(repo.text(f) for f in ai_ctx)
    readme_text = "\n".join(repo.text(f) for f in readmes)
    codeowners = next((f for f in ("CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS", ".gitlab/CODEOWNERS")
                       if f in repo.file_set), None)

    items: dict[str, dict] = {}

    def put(item_id, score, rationale, evidence=None, gaps=None):
        items[item_id] = {"auto_score": score, "rationale": rationale,
                          "evidence": evidence or [], "gaps": gaps or []}

    # ---------------- A. Navigation coverage ----------------
    mod_rows = []
    local_by_path: dict[str, list[str]] = {}  # full list for C (the row keeps only the first 5)
    ctx_candidates = list(dict.fromkeys(ai_ctx + readmes))
    for mod in modules:
        pat = module_mention_re(mod)
        prefix = "" if mod["path"] == "." else mod["path"] + "/"
        inside = [f for f in ctx_candidates if prefix and f.startswith(prefix)]
        if mod["path"] == ".":
            inside = [f for f in ctx_candidates if "/" not in f]
        dedicated = [f for f in dedicated_docs(repo, mod, ai_ctx, modules) if f not in inside]
        local = inside + dedicated
        local_by_path[mod["path"]] = local
        ai_mention = [f for f in ai_ctx if f not in local and pat.search(repo.text(f))]
        readme_mention = [f for f in readmes if f not in local and pat.search(repo.text(f))]
        if inside:
            weight, how = 1.0, "모듈 내부 context/README"
        elif dedicated:
            doc = dedicated[0]
            chain = linked.get(doc)
            weight, how = 1.0, f"전용 문서 {doc}" + (f" ({' → '.join(chain)})" if chain else "")
        elif ai_mention:
            weight, how = 0.6, "AI context 파일에서 언급"
        elif readme_mention:
            weight, how = 0.3, "README에서만 언급"
        else:
            weight, how = 0.0, "안내 없음"
        mod_rows.append({**mod, "coverage_weight": weight, "coverage_via": how,
                         "local_context": local[:5], "mentioned_in": (ai_mention + readme_mention)[:5]})
    if modules:
        cov = sum(m["coverage_weight"] for m in mod_rows) / len(mod_rows)
        a_score = round(15 * cov)
    else:
        cov, a_score = 0.0, 0
    uncovered = [m["label"] for m in mod_rows if m["coverage_weight"] < 1.0]
    put("A", a_score,
        f"핵심 module {len(modules)}개, Navigation Coverage {cov:.0%} "
        f"(내부 context·링크로 닿는 전용 문서=1.0, AI 문서 언급=0.6, README 언급=0.3)",
        [f"{m['label']}: {m['coverage_via']} ({m['coverage_weight']})" for m in mod_rows],
        uncovered)

    # ---------------- B. Context document quality ----------------
    b_targets = primary[:]
    capped = False
    if not b_targets:
        root_readme = next((f for f in readmes if "/" not in f), None)
        if root_readme:
            b_targets, capped = [root_readme], True
    per_file = []
    for f in b_targets:
        text = repo.text(f)
        nonempty = [l for l in text.splitlines() if l.strip()]
        tokens = estimate_tokens(text)
        # B1
        if len(nonempty) < 8:
            b1 = 2
        elif tokens <= 1300 and len(nonempty) <= 50:
            b1 = 4
        else:
            b1 = band(-tokens, [(-2500, 3), (-4000, 2), (-7000, 1)], 0)
        # B2
        cmds = command_lines(text)
        described = sum(1 for _, d in cmds if d)
        has_cmd_heading = bool(re.search(r"^#+\s*(commands?|quick ?start|명령|자주 쓰는)", text, re.I | re.M))
        if not cmds:
            b2 = 0
        elif len(cmds) < 3:
            b2 = 2
        elif described >= len(cmds) / 2 or has_cmd_heading:
            b2 = 4
        else:
            b2 = 3
        # B3
        refs = extract_path_refs(text)
        # Key files are files an agent would open to make a change — directories and docs don't count.
        existing = [r for r in refs if not r.endswith((".md", ".mdc")) and resolve_ref_kind(repo, r, f) == "file"]
        n = len(existing)
        b3 = 4 if 3 <= n <= 6 else 3 if 7 <= n <= 12 else 2 if n >= 1 else 0
        # B4
        gotcha_lines = [l.strip() for l in text.splitlines()
                        if GOTCHA_RE.search(l) and not l.strip().startswith("```")]
        g = len(gotcha_lines)
        b4 = 4 if g >= 4 else 3 if g >= 2 else 2 if g == 1 else 0
        # B5
        md_links = {r for r in refs if r.endswith((".md", ".mdc"))}
        see_also = len(re.findall(r"(see also|참고|관련 (문서|모듈)|related|→\s*`)", text, re.I))
        ctx_names = sum(1 for name in PRIMARY_CONTEXT_NAMES | {".claude/rules", "ARCHITECTURE"}
                        if name in text and not f.endswith(name))
        xref = len(md_links) + min(see_also, 2) + min(ctx_names, 2)
        b5 = 4 if xref >= 3 else 3 if xref == 2 else 2 if xref == 1 else 0
        weight = 2 if "/" not in f else 1
        per_file.append({"file": f, "weight": weight, "lines": len(nonempty), "tokens": tokens,
                         "commands": len(cmds), "commands_described": described, "key_files": existing[:12],
                         "gotcha_lines": gotcha_lines[:8], "cross_refs": xref,
                         "scores": {"B1": b1, "B2": b2, "B3": b3, "B4": b4, "B5": b5}})
    total_w = sum(p["weight"] for p in per_file) or 1
    for bid in ("B1", "B2", "B3", "B4", "B5"):
        if not per_file:
            put(bid, 0, "평가할 primary context 파일(CLAUDE.md/AGENTS.md 등)과 루트 README가 없음",
                gaps=["루트에 CLAUDE.md 또는 AGENTS.md 생성"])
            continue
        avg = sum(p["scores"][bid] * p["weight"] for p in per_file) / total_w
        s = round(avg * (0.5 if capped else 1))
        detail = {
            "B1": lambda p: f"{p['file']}: 비어있지 않은 줄 {p['lines']}, 추정 {p['tokens']} tokens",
            "B2": lambda p: f"{p['file']}: 명령어 {p['commands']}개 (설명 붙은 것 {p['commands_described']}개)",
            "B3": lambda p: f"{p['file']}: 실존 파일 참조 {len(p['key_files'])}개 (디렉터리·문서 제외) {p['key_files'][:6]}",
            "B4": lambda p: f"{p['file']}: 경고성 규칙 {len(p['gotcha_lines'])}줄",
            "B5": lambda p: f"{p['file']}: 상호 참조 {p['cross_refs']}건",
        }[bid]
        ev = [detail(p) for p in per_file]
        rationale = f"primary context 파일 {len(per_file)}개 가중 평균 {avg:.1f}/4" + \
                    (" (primary context 없음 → 루트 README 평가, 50% 캡)" if capped else "")
        gaps = [p["file"] for p in per_file if p["scores"][bid] < 4]
        put(bid, s, rationale, ev, gaps)

    # ---------------- C. Tribal knowledge (Five-Question) ----------------
    global_sources = ai_ctx + [f for f in docs if re.search(
        r"(contributing|adr|decision|playbook|runbook|checklist|gotcha|convention|troubleshoot|faq)", f, re.I)]
    global_sources = list(dict.fromkeys(global_sources))
    global_text = "\n".join(repo.text(f) for f in global_sources)
    all_doc_sources = list(dict.fromkeys(global_sources + readmes))
    c_rows, c_total = [], 0.0
    for mod in mod_rows:
        pat = module_mention_re(mod)
        local = local_by_path[mod["path"]]
        local_text = "\n".join(repo.text(f) for f in local)
        window_text = "\n".join(mention_windows(repo.text(f), pat) for f in all_doc_sources
                                if f not in local)
        mod_text = local_text + "\n" + window_text
        answers = {}
        for q, rx in FIVE_Q.items():
            if mod_text.strip() and rx.search(mod_text):
                answers[q] = 1.0
            elif global_text.strip() and rx.search(global_text):
                answers[q] = 0.5
            else:
                answers[q] = 0.0
        c_total += sum(answers.values())
        c_rows.append({"module": mod["label"], "path": mod["path"], "answers": answers})
    c_score = round(20 * c_total / (5 * len(c_rows))) if c_rows else 0
    code_files = [f for f in files if PurePosixPath(f).suffix in CODE_EXT and not is_hidden(f)]
    todo_count = sum(len(re.findall(r"\b(TODO|FIXME|HACK|XXX)\b", repo.text(f, 100_000))) for f in code_files[:3000])
    adr = [f for f in files if re.search(r"(^|/)(adr|decisions?)/", f, re.I)]
    c_ev = [f"{r['module']}: " + ", ".join(f"Q{i + 1}={v}" for i, v in enumerate(r["answers"].values()))
            for r in c_rows]
    c_ev.append(f"전역 지식 소스 {len(global_sources)}개: {global_sources[:8]}")
    c_ev.append(f"ADR/decision 문서 {len(adr)}개, 코드 내 TODO/FIXME/HACK/XXX 주석 {todo_count}개")
    c_gaps = []
    for r in c_rows:
        missing = [FIVE_Q_LABELS[q] for q, v in r["answers"].items() if v < 1.0]
        if missing:
            c_gaps.append(f"{r['module']}: " + ", ".join(missing))
    put("C", c_score, "module별 Five-Question 답변 가능 여부 (module 전용 문서=1.0, 전역 문서만=0.5)", c_ev, c_gaps)

    # ---------------- D. Dependency mapping ----------------
    arch_docs = [f for f in files if re.search(r"(^|/)(architecture|arch|design|system[-_ ]?overview)[^/]*\.(md|mdx|rst|adoc)$",
                                               f, re.I)]
    diagrams = [f for f in files if PurePosixPath(f).suffix.lower() in {".mmd", ".puml", ".plantuml", ".dot", ".drawio", ".excalidraw"}]
    mermaid_docs = [f for f in docs if "```mermaid" in repo.text(f)]
    dep_sections = [f for f in docs if re.search(
        r"^#+.*(depend|의존|data ?flow|데이터 ?흐름|영향 ?범위|impact|call ?graph|module ?map|모듈 ?관계)",
        repo.text(f), re.I | re.M)]
    owner_docs = [f for f in ai_ctx + arch_docs + dep_sections
                  if re.search(r"^#+.*(code ?owners?|ownership|담당\s?(자|팀)|소유\s?(자|팀)|maintainers?)",
                               repo.text(f), re.I | re.M)]
    graph_files = [f for f in files if re.search(
        r"(dependency[-_]?(map|graph)|deps?[-_]graph|module[-_]graph)\.(json|ya?ml|dot|csv)$|"
        r"(^|/)\.dependency-cruiser|(^|/)\.importlinter$|(^|/)nx\.json$|(^|/)turbo\.json$|(^|/)\.madgerc$|"
        r"ArchitectureTest|ArchUnit|LayerTest|ArchTest", f, re.I)]
    if not graph_files:
        graph_files = [f for f in code_files[:3000] if is_test_path(f) and "com.tngtech.archunit" in repo.text(f, 20_000)]
    # Dependency notes in prose, e.g. "Dependency direction is `api → command / query → domain`" or
    # "command and query never reference each other" — the rubric's 5-point level counts these like a diagram.
    dep_note_rx = re.compile(
        r"(\w+`?\s*(→|->|=>)\s*`?\w+.*(depend|의존|direction|방향|import|호출|참조|reference|layer|계층)|"
        r"(depend|의존|direction|방향|import|호출|참조|reference|layer|계층).*\w+`?\s*(→|->|=>)\s*`?\w+|"
        r"(never|must not|do not|don't|cannot)\s+(reference|import|depend on|call)\b|"
        r"(참조|의존|호출|import)(하지|해서는|하면)\s*(않|안\s?된|안된))", re.I)
    dep_notes = []
    for f in list(dict.fromkeys(ai_ctx + arch_docs + readmes)):
        for line in FENCE_RE.sub("", repo.text(f)).splitlines():
            m = dep_note_rx.search(line)
            if m:
                lo = max(0, m.start() - 30)
                dep_notes.append(f"{f}: {'…' if lo else ''}{line[lo:m.end() + 40].strip()}")
    has_map_doc = bool(arch_docs or diagrams or mermaid_docs)
    d_signals = {"map_doc": has_map_doc or bool(dep_notes), "section": bool(dep_sections),
                 "ownership": bool(codeowners or owner_docs), "graph": bool(graph_files)}
    d = 0
    d_ev = []
    if has_map_doc:
        d_ev.append(f"architecture/diagram: {(arch_docs + diagrams + mermaid_docs)[:6]}")
    if dep_notes:
        d_ev.append(f"본문 dependency note {len(dep_notes)}건: " + " | ".join(dep_notes[:3]))
    if d_signals["map_doc"]:
        d += 5
    if dep_sections:
        d += 3
        d_ev.append(f"dependency/data-flow 섹션: {dep_sections[:6]}")
    if d_signals["ownership"]:
        d += 2
        d_ev.append(f"ownership: {([codeowners] if codeowners else []) + owner_docs[:4]}")
    if graph_files:
        d += 5
        d_ev.append(f"machine-readable graph/강제 수단: {graph_files[:6]}")
    d_gaps = []
    if not d_signals["map_doc"]:
        d_gaps.append("architecture 문서/다이어그램/dependency note 없음")
    elif not has_map_doc:
        d_gaps.append("dependency note만 있고 architecture 문서/다이어그램 없음")
    if not dep_sections:
        d_gaps.append("module 간 dependency/data-flow 전용 섹션 없음")
    if not d_signals["ownership"]:
        d_gaps.append("ownership 정보 없음")
    if not graph_files:
        d_gaps.append("'What depends on X?'에 답할 machine-readable map/아키텍처 테스트 없음")
    put("D", min(d, 15),
        "diagram·architecture 문서·dependency note(+5) / 전용 dependency·data-flow 섹션(+3) / ownership(+2) / "
        "machine-readable graph(+5)", d_ev or ["근거 없음"], d_gaps)
    items["D"]["signals"] = d_signals

    # ---------------- E1. Reference accuracy ----------------
    total_refs, broken = 0, []
    for f in ai_ctx:
        text = repo.text(f)
        for r in extract_path_refs(text):
            total_refs += 1
            if not resolve_ref(repo, r, f):
                broken.append(f"{f}: `{r}`")
        checked, bad = check_commands(repo, [c for c, _ in command_lines(text)])
        total_refs += checked
        broken += [f"{f}: {b}" for b in bad]
    if not ai_ctx:
        e1, e1_r = 0, "AI context 파일이 없어 검증 대상 없음"
    else:
        ratio = len(broken) / total_refs if total_refs else 0
        if not broken:
            e1 = 5 if total_refs >= 3 else 3
        else:
            e1 = 3 if ratio <= 0.05 else 2 if ratio <= 0.15 else 1 if ratio <= 0.30 else 0
        e1_r = f"참조 {total_refs}건 중 깨진 참조 {len(broken)}건 ({ratio:.0%})"
    put("E1", e1, e1_r, broken[:30] or [f"검증한 참조 {total_refs}건 모두 실존"], broken[:30])

    # ---------------- E2. Critic review ----------------
    pr_tpl = [f for f in files if re.search(r"pull_request_template|merge_request_templates", f, re.I)]
    review_cfg = [f for f in files if re.search(
        r"(\.claude/agents/.*review|\.coderabbit\.ya?ml$|dangerfile|reviewdog|(^|/)review[-_]?checklist|"
        r"checklist.*\.md$|\.github/workflows/.*review)", f, re.I)]
    if re.search(r"(claude-code-action|code-review|coderabbit|reviewdog|danger)", ci_text, re.I):
        review_cfg.append("(CI 워크플로의 자동 리뷰 단계)")
    e2 = min(4, (1 if pr_tpl else 0) + (1 if codeowners else 0) + (2 if review_cfg else 0))
    e2_gaps = ([] if pr_tpl else ["PR 템플릿(체크리스트) 없음"]) + ([] if codeowners else ["CODEOWNERS 없음"]) + \
              ([] if review_cfg else ["독립 리뷰 체크리스트/리뷰 에이전트/자동 리뷰 없음"])
    put("E2", e2, "PR 템플릿(+1) / CODEOWNERS(+1) / 리뷰 체크리스트·에이전트·자동 리뷰(+2)",
        [f"PR 템플릿: {pr_tpl[:3]}", f"CODEOWNERS: {codeowners}", f"리뷰 설정: {review_cfg[:5]}"], e2_gaps)
    items["E2"]["signals"] = {"pr_template": bool(pr_tpl), "codeowners": bool(codeowners), "review": bool(review_cfg)}

    # ---------------- E3. Task validation commands ----------------
    ctx_cmd_text = "\n".join(c for f in ai_ctx for c, _ in command_lines(repo.text(f))) + "\n" + \
                   "\n".join(l for l in ai_text.splitlines() if "`" in l)
    kinds = {
        "build": r"\b(build|compile|assemble|bootJar|package)\b",
        "test": r"\b(test|pytest|jest|vitest|mocha|rspec|go test|cargo test)\b",
        "lint/format": r"\b(lint|eslint|ruff|flake8|pylint|checkstyle|spotless|ktlint|golangci|prettier|black|rubocop|format)\b",
        "typecheck": r"\b(typecheck|type-check|tsc|mypy|pyright|flow)\b",
        "e2e": r"\b(e2e|playwright|cypress|selenium|integrationTest)\b",
    }
    found = [k for k, rx in kinds.items() if re.search(rx, ctx_cmd_text, re.I)]
    ci_tests = bool(ci_files) and bool(re.search(kinds["test"], ci_text, re.I))
    e3 = min(4, min(len(found), 3) + (1 if ci_tests else 0))
    put("E3", e3, "context 문서의 검증 명령 종류(최대 3) + CI 테스트 실행(+1)",
        [f"context 문서의 검증 명령 종류: {found or '없음'}", f"CI 파일: {ci_files[:5] or '없음'} (테스트 실행: {ci_tests})"],
        [f"{k} 명령 미기재" for k in kinds if k not in found] + ([] if ci_tests else ["CI에서 테스트 미실행"]))
    items["E3"]["signals"] = {"kinds": found, "ci_tests": ci_tests}

    # ---------------- E4. Prompt / workflow tests ----------------
    eval_files = [f for f in files if re.search(
        r"((^|/)evals?/|evals?\.json$|promptfoo|(^|/)golden[-_]?(tasks?|prompts?)|ai[-_]tasks?\.(json|ya?ml|md)$|"
        r"\.claude/evals/)", f, re.I)]
    task_examples = re.search(r"(example (tasks?|prompts?)|sample prompts?|대표 (작업|task|질의)|예시 (프롬프트|질의))",
                              ai_text, re.I)
    e4 = 2 if eval_files else 1 if task_examples else 0
    put("E4", e4, "eval/golden task 파일(2) / 문서의 대표 task 예시만(1)",
        [f"eval 파일: {eval_files[:6] or '없음'}", f"문서 내 대표 task 예시: {bool(task_examples)}"],
        [] if eval_files else ["대표 AI task 질의 세트와 기대 결과가 없음"])

    # ---------------- F. Freshness ----------------
    f_ev, level = [], 0
    owner_covers_ctx = False
    if codeowners:
        co = repo.text(codeowners)
        owner_covers_ctx = bool(re.search(r"^\s*(\*|/?\*\*?|.*(CLAUDE|AGENTS|docs|\.claude|\.md))\s", co, re.M))
        f_ev.append(f"CODEOWNERS가 context/문서를 커버: {owner_covers_ctx}")
    latest = repo.last_commit_date()
    ctx_dates = []
    for f in primary[:30] or static_ctx[:30]:  # primary only; linked docs don't change staleness dates
        d_ = repo.last_commit_date(f)
        if d_:
            ctx_dates.append((f, d_))
    stale = []
    if latest and ctx_dates:
        for f, d_ in ctx_dates:
            lag = (latest - d_).days
            if lag > 90:
                stale.append(f"{f}: 최근 커밋보다 {lag}일 뒤처짐")
        recent = any((latest - d_).days <= 30 for _, d_ in ctx_dates)
        f_ev.append(f"context 파일 최근 갱신: " + ", ".join(f"{f}={d_.date()}" for f, d_ in ctx_dates[:6]))
    else:
        recent = False
    # Rubric level 3 = "owner가 있고 가끔 업데이트". A recent edit without an owner is only partial credit.
    if owner_covers_ctx:
        level = 3
    elif recent:
        level = 2
        f_ev.append("owner 없이 최근 갱신만 확인 → 부분 인정(2)")
    validation_rx = re.compile(r"(lychee|markdown-link-check|remark-validate-links|linkinator|check[-_]?links|"
                               r"validate[-_]?(context|docs|refs|paths)|context[-_]?(lint|check)|doc[-_]?lint|mkdocs build --strict)",
                               re.I)
    auto_files = ci_files + [f for f in files if re.search(r"(^|/)(\.pre-commit-config\.ya?ml|\.husky/|lefthook\.ya?ml|"
                                                           r"scripts?/.*(link|context|doc).*)", f, re.I)]
    validators = [f for f in auto_files if validation_rx.search(repo.text(f)) or validation_rx.search(f)]
    if validators:
        level = 6
        f_ev.append(f"경로/링크 검증 자동화: {validators[:5]}")
    scheduled = [f for f in ci_files if re.search(r"^\s*schedule\s*:", repo.text(f), re.M)
                 and validation_rx.search(repo.text(f))]
    if scheduled:
        level = 10
        f_ev.append(f"스케줄 실행 검증: {scheduled[:5]}")
    put("F", level, "최근 갱신만(2) / owner + 갱신(3) → CI·스크립트 경로 검증(6) → 스케줄 자동 검증(10) 중 최고 단계",
        f_ev or ["자동 유지 장치 없음"],
        stale + ([] if owner_covers_ctx else ["context 문서 owner(CODEOWNERS) 없음"])
        + ([] if validators else ["context 경로/링크 검증 자동화 없음"]))
    items["F"]["signals"] = {"owner": owner_covers_ctx, "validation": bool(validators), "scheduled": bool(scheduled)}

    # ---------------- G. Outcome measurement ----------------
    metric_hits: dict[str, list[str]] = {}
    # Docs, plus data files whose name suggests measurement results (not lockfiles / API specs).
    candidates = [f for f in files if is_doc(f) or (
        PurePosixPath(f).suffix.lower() in {".json", ".csv", ".yaml", ".yml", ".jsonl"}
        and re.search(r"(eval|metric|benchmark|result|report|grading|kpi)", PurePosixPath(f).name, re.I))]
    ai_rx = re.compile(r"\b(AI|LLM|Claude|Copilot|Cursor|agent|에이전트)\b", re.I)
    qualitative = []
    before_after = []
    for f in candidates[:4000]:
        t = repo.text(f, 150_000)
        if not ai_rx.search(t):
            continue
        for k, rx in METRIC_PATTERNS.items():
            if rx.search(t) and re.search(r"\d", t):
                metric_hits.setdefault(k, []).append(f)
        if re.search(r"(도움|helpful|productiv|생산성|효율)", t, re.I):
            qualitative.append(f)
        if any(f in v for v in metric_hits.values()) and re.search(
                r"(before\s*/\s*after|before and after|baseline|전후|도입 전|개선율|\bdelta\b)", t, re.I):
            before_after.append(f)
    if len(metric_hits) >= 3 and before_after:
        g = 5
    elif metric_hits:
        g = 3
    elif qualitative:
        g = 2
    else:
        g = 0
    put("G", g, "정성 언급(2) / metric 측정(3) / 3종 이상 metric의 before-after 비교(5)",
        [f"metric 종류: {sorted(metric_hits)}", f"before/after 문서: {before_after[:4]}",
         f"정성 언급 문서: {qualitative[:4]}"],
        [] if g == 5 else ["AI task pass rate·tool calls·tokens 등의 before/after 측정 없음"])

    return {
        "items": items,
        "modules": mod_rows,
        "module_prefix": module_prefix,
        "five_questions": c_rows,
        "context_files": {"ai_context": ai_ctx, "primary": primary, "per_file_b": per_file,
                          "linked": {d: " → ".join(p) for d, p in sorted(linked.items())}},
    }


# ---------------------------------------------------------------------------
# Actions (ROI)
# ---------------------------------------------------------------------------

def default_actions(result: dict) -> list[dict]:
    """Build candidate actions. expected_gain is what finishing *this* action would add (from the
    signals that are actually missing), not the item's full remaining gap."""
    items = result["items"]
    acts = []

    def gap(item_id):
        it = items[item_id]
        return max(0, it["max"] - it["score"])

    def add(item_ids, effort, title, detail, gain=None, targets=None):
        ids = [item_ids] if isinstance(item_ids, str) else item_ids
        total = gain if gain is not None else sum(gap(i) for i in ids)
        if total <= 0:
            return
        acts.append({"item": "/".join(ids), "title": title, "detail": detail, "targets": (targets or [])[:10],
                     "effort": effort, "expected_gain": total, "source": "auto"})

    gaps = {k: v.get("gaps", []) for k, v in items.items()}
    sig = {k: v.get("signals", {}) for k, v in items.items()}
    if not result["context_files"]["primary"]:
        add("B1", "S", "루트 CLAUDE.md/AGENTS.md 생성 (25-35줄)",
            "프로젝트 개요, 자주 쓰는 명령어, 핵심 파일 3-5개, 주의할 규칙, See also로 구성")
    add("A", "M", "미안내 module에 navigation guide 추가",
        "module마다 역할·entry point·관련 파일을 담은 짧은 CLAUDE.md, 또는 CLAUDE.md에서 링크한 module 전용 문서(파일명이나 첫 H1이 module 이름) 작성",
        targets=gaps["A"])
    add("B1", "S", "context 문서를 compass 수준으로 압축",
        "약 1,000 tokens를 넘는 부분은 하위 문서로 분리하고 링크만 남김", targets=gaps["B1"])
    add("B2", "S", "Quick Commands 섹션 보강", "build/test/run/단일 테스트 명령을 코드 블록으로, 명령마다 '언제 쓰는지' 주석")
    add("B3", "S", "Key Files 섹션 추가", "수정 시 가장 자주 여는 파일 3-5개를 실존 경로 + 한 줄 설명으로 나열")
    add("B4", "S", "Non-Obvious Patterns 섹션 추가", "실패를 유발했던 이 repo 고유의 규칙·예외를 '왜'와 함께 기록")
    add("B5", "S", "See Also 섹션으로 문서 연결", "관련 module 문서, rules, architecture/dependency map으로 링크")
    add("C", "M", "module별 Five-Question 답변 채우기",
        "소유 범위·수정 패턴·실패 함정·의존성·배경 중 빠진 답을 module 문서에 추가", targets=gaps["C"])
    d = sig.get("D", {})
    d_doc_gain = (0 if d.get("map_doc") else 5) + (0 if d.get("section") else 3)
    add("D", "M", "architecture 문서에 module 간 의존·데이터 흐름 정리",
        "mermaid로 module 의존 방향과 주요 요청의 데이터 흐름을 그리고 '무엇이 X에 의존하나' 섹션 작성",
        gain=min(d_doc_gain, gap("D")))
    if not d.get("graph"):
        add("D", "M", "의존 규칙을 아키텍처 테스트/map으로 고정",
            "ArchUnit·dependency-cruiser·import-linter 등으로 허용 의존 방향을 테스트로 강제",
            gain=min(5, gap("D")))
    add("E1", "S", "context 문서의 깨진 참조 수정", "없는 경로·명령을 실제 경로로 고치거나 삭제", targets=gaps["E1"])
    e2 = sig.get("E2", {})
    f = sig.get("F", {})
    if not e2.get("codeowners"):
        # One file, three rubric items: E2 reviewer, D ownership, F document owner.
        co_gain = (min(1, gap("E2")) + (0 if d.get("ownership") else min(2, gap("D")))
                   + (max(0, 3 - items["F"]["score"]) if not f.get("owner") else 0))
        add(["E2", "D", "F"], "S", "CODEOWNERS 추가 (context 문서 포함)",
            "코드와 CLAUDE.md·docs의 owner를 지정 — 리뷰어, 의존성 ownership, 문서 owner를 한 번에 충족",
            gain=co_gain)
    if not e2.get("pr_template"):
        add("E2", "S", "PR 템플릿에 AI 변경 검증 체크리스트 추가",
            "참조 경로 확인, 테스트 결과 첨부, context 문서 갱신 여부 체크", gain=min(1, gap("E2")))
    if not e2.get("review"):
        add("E2", "S", "독립 리뷰 체크리스트/리뷰 에이전트 추가",
            ".claude/agents/reviewer.md 또는 자동 리뷰로 2차 검토", gain=min(2, gap("E2")))
    e3 = sig.get("E3", {})
    missing_kinds = [k for k in ("build", "test", "lint/format", "typecheck", "e2e") if k not in e3.get("kinds", [])]
    kind_gain = max(0, 3 - min(len(e3.get("kinds", [])), 3))
    if kind_gain:
        add("E3", "S", "변경 유형별 검증 명령 명시", "context 문서에 빠진 종류의 검증 명령 추가",
            gain=kind_gain, targets=missing_kinds)
    if not e3.get("ci_tests"):
        add("E3", "M", "CI에서 테스트 실행", "PR마다 테스트를 돌리는 CI 워크플로 추가", gain=min(1, gap("E3")))
    add("E4", "M", "대표 AI task eval 세트 만들기", "자주 하는 작업 5-10개를 프롬프트 + 기대 결과(수정 파일, 통과 테스트)로 저장")
    f_now = max(items["F"]["score"], 3)  # assume the owner step is done first
    if not f.get("validation"):
        # The scheduled run reuses the same check, so propose them together rather than ranking
        # the schedule (bigger gain) above the check it depends on.
        add("F", "M", "context 경로·링크 검증을 CI + 주간 스케줄로 실행",
            "context 문서의 파일 경로/명령 실재 여부를 PR마다 검사하고, 같은 검사를 cron으로 주 1회 실행",
            gain=max(0, 10 - f_now))
    elif not f.get("scheduled"):
        add("F", "S", "기존 경로 검증을 스케줄로도 실행", "CI 검증 워크플로에 cron trigger 추가(주 1회)",
            gain=max(0, 10 - max(f_now, 6)))
    add("G", "M", "AI 작업 성과 지표 측정", "eval 세트 기준 pass rate·tool calls·tokens·소요 시간을 context 개선 전후로 기록")
    return acts


def rank_actions(actions: list[dict], top: int) -> list[dict]:
    for a in actions:
        eff = a.get("effort", "M").upper()
        a["effort"] = eff if eff in EFFORT_POINTS else "M"
        a["roi"] = round(a["expected_gain"] / EFFORT_POINTS[a["effort"]], 2)
    actions.sort(key=lambda a: (-a["roi"], -a["expected_gain"], a["item"]))
    for i, a in enumerate(actions[:top], 1):
        a["rank"] = i
    return actions[:top]


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------

def display_path(p: Path) -> str:
    """Path relative to the working directory when possible, so committed reports don't embed home dirs."""
    try:
        return p.relative_to(Path.cwd().resolve()).as_posix() or "."
    except ValueError:
        return str(p)


def grade_for(total: float) -> dict:
    for threshold, level, meaning in GRADES:
        if total >= threshold:
            return {"level": level, "meaning": meaning, "min_score": threshold}
    return {"level": GRADES[-1][1], "meaning": GRADES[-1][2], "min_score": 0}


def assemble(repo: Repo, raw: dict, overrides: dict, top: int) -> dict:
    items = raw["items"]
    ov_items = (overrides or {}).get("items", {})
    for iid, (cat, name, mx) in ITEMS.items():
        it = items[iid]
        it.update({"id": iid, "category": cat, "name": name, "max": mx})
        it["score"] = it["auto_score"]
        it["override_reason"] = None
        if iid in ov_items:
            o = ov_items[iid]
            val = o.get("score") if isinstance(o, dict) else o
            if val is not None:
                it["score"] = max(0, min(mx, val))
                it["override_reason"] = o.get("reason") if isinstance(o, dict) else None
    unknown = set(ov_items) - set(ITEMS)
    if unknown:
        print(f"warning: overrides의 알 수 없는 item id 무시: {sorted(unknown)}", file=sys.stderr)

    cats = []
    for cid, name_en, name_ko, mx in CATEGORIES:
        its = [items[i] for i in ITEMS if ITEMS[i][0] == cid]
        cat_score = sum(i["score"] for i in its)
        cats.append({"id": cid, "name": name_en, "name_ko": name_ko, "max": mx, "level": level_text(cid, cat_score),
                     "score": sum(i["score"] for i in its), "auto_score": sum(i["auto_score"] for i in its),
                     "items": its})
    total = sum(c["score"] for c in cats)
    auto_total = sum(c["auto_score"] for c in cats)

    raw_for_actions = {"items": items, "context_files": raw["context_files"]}
    actions = default_actions(raw_for_actions)
    user_actions = (overrides or {}).get("actions", [])
    user_items = {a.get("item") for a in user_actions}
    actions = [a for a in actions if a["item"] not in user_items]
    for a in user_actions:
        iid = a.get("item")
        room = items[iid]["max"] - items[iid]["score"] if iid in items else None
        gain = a.get("expected_gain", room)
        if gain is not None and room is not None:
            gain = min(gain, room)
        actions.append({"item": iid, "title": a.get("title", ""), "detail": a.get("detail", ""),
                        "targets": a.get("targets", []), "effort": a.get("effort", "M"),
                        "expected_gain": gain or 0, "source": "review"})

    head = subprocess.run(["git", "-C", str(repo.root), "rev-parse", "--short", "HEAD"], capture_output=True,
                          text=True).stdout.strip() if (repo.root / ".git").exists() else ""
    branch = subprocess.run(["git", "-C", str(repo.root), "branch", "--show-current"], capture_output=True,
                            text=True).stdout.strip() if head else ""
    return {
        "schema_version": 2,
        "generated_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "repo": {"name": repo.root.name, "path": display_path(repo.root), "commit": head, "branch": branch,
                 "files_scanned": len(repo.files)},
        "total": total,
        "auto_total": auto_total,
        "max": 100,
        "grade": grade_for(total),
        "overrides_applied": bool(ov_items),
        "summary": (overrides or {}).get("summary"),
        "categories": cats,
        "modules": raw["modules"],
        "module_prefix": raw["module_prefix"],
        "five_questions": raw["five_questions"],
        "context_files": {"ai_context": raw["context_files"]["ai_context"],
                          "primary": raw["context_files"]["primary"],
                          "linked": raw["context_files"].get("linked", {})},
        "actions": rank_actions(actions, top),
        "verification": (overrides or {}).get("verification", []),
    }


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def e(s) -> str:
    return html.escape(str(s))


def delta_html(cur, prev) -> str:
    if prev is None:
        return ""
    d = cur - prev
    if d == 0:
        return '<span class="delta flat">±0</span>'
    cls, sign = ("up", "▲") if d > 0 else ("down", "▼")
    return f'<span class="delta {cls}">{sign}{abs(d)}</span>'


def ratio_color(ratio: float) -> str:
    return ("var(--good)" if ratio >= 0.75 else "var(--mid)" if ratio >= 0.5 else
            "var(--warn)" if ratio >= 0.25 else "var(--bad)")


def render_html(r: dict) -> str:
    total, grade = r["total"], r["grade"]
    prev = r.get("previous")
    prev_cats = prev["categories"] if prev else {}
    grade_color = ratio_color(total / 100) if total < 75 else "var(--good)"

    # --- hero comparison line
    compare = ""
    if prev:
        label = prev.get("label") or f"{prev.get('branch') or '-'} @ {prev.get('commit') or '-'}"
        version_note = ""
        if prev.get("script_version") != r["provenance"]["script_version"]:
            version_note = (f'<div class="muted small">채점 스크립트 버전이 다릅니다 (이전 {e(prev.get("script_version"))} → '
                            f'현재 {e(r["provenance"]["script_version"])}). 일부 차이는 채점 로직 변경 때문일 수 있습니다.</div>')
        compare = (f'<div class="compare">이전 <strong>{e(label)}</strong> {prev["total"]}점 ({e(prev.get("grade") or "")}) → '
                   f'현재 {total}점 {delta_html(total, prev["total"])}</div>{version_note}')

    cat_rows = []
    for c in r["categories"]:
        ratio = c["score"] / c["max"] if c["max"] else 0
        auto = "" if c["score"] == c["auto_score"] else f'<span class="muted small"> (자동 {c["auto_score"]})</span>'
        cat_rows.append(f"""
      <div class="cat">
        <div class="cat-head"><span class="cat-id">{e(c['id'])}</span><span class="cat-name">{e(c['name_ko'])}
          <span class="muted small">{e(c['name'])}</span></span>
          {delta_html(c['score'], prev_cats.get(c['id'])) if prev else ''}
          <span class="cat-score">{c['score']}<span class="muted">/{c['max']}</span>{auto}</span></div>
        <div class="bar"><div class="fill" style="width:{ratio * 100:.1f}%;background:{ratio_color(ratio)}"></div></div>
        <div class="muted small level">{e(c['level'])}</div>
      </div>""")

    item_rows = []
    for c in r["categories"]:
        for it in c["items"]:
            ev = "".join(f"<li>{e(x)}</li>" for x in it["evidence"][:20])
            gaps = "".join(f"<li>{e(x)}</li>" for x in it["gaps"][:15])
            ov = (f'<div class="override">판단 보정: 자동 {it["auto_score"]} → {it["score"]} — {e(it["override_reason"] or "")}</div>'
                  if it["score"] != it["auto_score"] or it["override_reason"] else "")
            item_rows.append(f"""
      <details class="item">
        <summary><span class="item-id">{e(it['id'])}</span><span class="item-name">{e(it['name'])}</span>
          <span class="item-score">{it['score']}/{it['max']}{'<span class="badge">보정</span>' if ov else ''}</span></summary>
        <div class="item-body">
          <p class="muted">{e(it['rationale'])}</p>{ov}
          <h4>근거</h4><ul>{ev or '<li>없음</li>'}</ul>
          {f'<h4>부족한 점</h4><ul>{gaps}</ul>' if gaps else ''}
        </div>
      </details>""")

    def targets_html(a):
        if not a.get("targets"):
            return ""
        chips = "".join(f"<code class='chip'>{e(t)}</code>" for t in a["targets"][:8])
        more = f"<span class='muted small'> 외 {len(a['targets']) - 8}건</span>" if len(a["targets"]) > 8 else ""
        return f"<div class='targets'>{chips}{more}</div>"

    act_rows = "".join(f"""
        <tr><td class="num">{a['rank']}</td><td><span class="pill">{e(a['item'])}</span></td>
          <td><strong>{e(a['title'])}</strong>{'<span class="badge">검토</span>' if a.get('source') == 'review' else ''}
            <div class="muted small">{e(a['detail'])}</div>{targets_html(a)}</td>
          <td class="num">+{a['expected_gain']}</td><td>{e(EFFORT_KO[a['effort']])}</td>
          <td class="num"><strong>{a['roi']}</strong></td></tr>""" for a in r["actions"])

    prefix = r.get("module_prefix") or ""
    mod_rows = "".join(f"""
        <tr><td><code>{e(m.get('label', m['path']))}</code></td><td class="num">{m['code_files']}</td>
          <td>{e(m['coverage_via'])}</td><td class="num">{m['coverage_weight']}</td></tr>""" for m in r["modules"])

    marks = {1.0: ("●", "q-full", "module 전용 문서에 답 있음"), 0.5: ("◐", "q-half", "전역 문서에만 있음"),
             0.0: ("○", "q-none", "답 없음")}
    fq_head = "".join(f'<th class="q" title="{e(FIVE_Q_QUESTIONS[k])}">{e(v)}</th>' for k, v in FIVE_Q_LABELS.items())
    fq_rows = "".join(
        "<tr><td><code>{}</code></td>{}</tr>".format(
            e(row["module"]),
            "".join(f'<td class="q {marks[v][1]}" title="{marks[v][2]}">{marks[v][0]}</td>'
                    for v in row["answers"].values()))
        for row in r["five_questions"])
    fq_legend = " · ".join(f"{m} {t}" for m, _, t in marks.values())

    ver = r.get("verification") or []
    ver_rows = "".join(
        f"""<tr><td>{e(v.get('claim', ''))}</td>
          <td><span class="vr {'ok' if str(v.get('result', '')).startswith(('일치', 'pass', 'ok')) else 'ng'}">{e(v.get('result', ''))}</span></td>
          <td class="small">{e(v.get('evidence', ''))}</td></tr>""" for v in ver)
    ver_section = (f"""
  <h2>검증 로그</h2>
  <section class="card scroll">
    <table><thead><tr><th>확인한 주장</th><th>결과</th><th>근거</th></tr></thead><tbody>{ver_rows}</tbody></table>
    <div class="muted small" style="margin-top:8px">스크립트가 아니라 리뷰어(사람/에이전트)가 context 문서의 서술을 코드와 대조한 기록입니다.</div>
  </section>""" if ver else "")

    summary = f'<p class="summary">{e(r["summary"])}</p>' if r.get("summary") else ""
    n_ov = sum(1 for c in r["categories"] for it in c["items"] if it["score"] != it["auto_score"])
    ov_note = (f"판단 보정 {n_ov}건이 반영된 최종 점수입니다. 자동 합계 {r['auto_total']}점." if r["overrides_applied"]
               else "휴리스틱 자동 점수입니다 (판단 보정 전).")
    repo = r["repo"]
    pv = r.get("provenance", {})
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI-Ready 점수표</title>
<style>
:root {{
  --bg:#f7f7f5; --card:#ffffff; --text:#1d1d1b; --muted:#6b6b66; --line:#e4e4df;
  --good:#2f7d4f; --mid:#3d6fb6; --warn:#c07a12; --bad:#b83b3b; --accent:#3d6fb6; --pill:#eef2f8;
}}
@media (prefers-color-scheme: dark) {{
  :root:not([data-theme="light"]) {{
    --bg:#161614; --card:#1f1f1c; --text:#ecece8; --muted:#9a9a93; --line:#33332f;
    --good:#5cb483; --mid:#6f9be0; --warn:#e0a24a; --bad:#e07070; --accent:#6f9be0; --pill:#26303f;
  }}
}}
:root[data-theme="dark"] {{
  --bg:#161614; --card:#1f1f1c; --text:#ecece8; --muted:#9a9a93; --line:#33332f;
  --good:#5cb483; --mid:#6f9be0; --warn:#e0a24a; --bad:#e07070; --accent:#6f9be0; --pill:#26303f;
}}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--text);
  font:15px/1.6 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Pretendard","Noto Sans KR",sans-serif; }}
main {{ max-width:1040px; margin:0 auto; padding:32px 16px 64px; }}
h1 {{ font-size:24px; margin:0 0 4px; }} h2 {{ font-size:18px; margin:36px 0 12px; }}
h4 {{ margin:12px 0 4px; font-size:13px; color:var(--muted); }}
.muted {{ color:var(--muted); }} .small {{ font-size:12px; }}
.card {{ background:var(--card); border:1px solid var(--line); border-radius:12px; padding:20px; }}
.hero {{ display:grid; grid-template-columns:auto 1fr; gap:24px; align-items:center; margin-top:20px; }}
.total {{ font-size:56px; font-weight:700; line-height:1; color:{grade_color}; font-variant-numeric:tabular-nums; }}
.total span {{ font-size:20px; color:var(--muted); font-weight:500; }}
.grade {{ font-size:20px; font-weight:700; color:{grade_color}; }}
.compare {{ margin-top:8px; font-size:14px; }}
.meter {{ height:10px; background:var(--line); border-radius:5px; overflow:hidden; margin-top:10px; }}
.meter .fill {{ height:100%; background:{grade_color}; width:{total}%; }}
.ticks {{ position:relative; height:16px; font-size:11px; color:var(--muted); margin-top:4px; }}
.ticks span {{ position:absolute; transform:translateX(-50%); white-space:nowrap; }}
.summary {{ margin:12px 0 0; }}
.cats {{ display:grid; gap:16px; }}
.cat-head {{ display:flex; gap:10px; align-items:baseline; }}
.cat-id {{ font-weight:700; color:var(--accent); width:18px; }}
.cat-name {{ flex:1; }} .cat-score {{ font-weight:700; font-variant-numeric:tabular-nums; }}
.bar {{ height:8px; background:var(--line); border-radius:4px; overflow:hidden; margin-top:6px; }}
.bar .fill {{ height:100%; border-radius:4px; }}
.level {{ margin-top:3px; padding-left:28px; }}
.delta {{ font-size:12px; font-weight:700; font-variant-numeric:tabular-nums; }}
.delta.up {{ color:var(--good); }} .delta.down {{ color:var(--bad); }} .delta.flat {{ color:var(--muted); }}
table {{ width:100%; border-collapse:collapse; font-size:14px; }}
th, td {{ text-align:left; padding:8px 10px; border-bottom:1px solid var(--line); vertical-align:top; }}
th {{ font-size:12px; color:var(--muted); font-weight:600; }}
td.num, th.num {{ text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap; }}
.pill {{ background:var(--pill); color:var(--accent); border-radius:6px; padding:1px 7px; font-weight:700; font-size:12px; white-space:nowrap; }}
.badge {{ margin-left:6px; font-size:11px; font-weight:600; color:var(--accent); border:1px solid var(--accent); border-radius:4px; padding:0 4px; }}
.targets {{ margin-top:4px; display:flex; flex-wrap:wrap; gap:4px; }}
.chip {{ background:var(--pill); border-radius:4px; padding:0 5px; font-size:12px; }}
.item {{ border-bottom:1px solid var(--line); }}
.item summary {{ display:flex; gap:10px; padding:10px 4px; cursor:pointer; list-style:none; }}
.item summary::-webkit-details-marker {{ display:none; }}
.item summary::before {{ content:"▸"; color:var(--muted); }} .item[open] summary::before {{ content:"▾"; }}
.item-id {{ font-weight:700; color:var(--accent); min-width:26px; }} .item-name {{ flex:1; }}
.item-score {{ font-weight:700; font-variant-numeric:tabular-nums; white-space:nowrap; }}
.item-body {{ padding:0 8px 12px 40px; font-size:14px; }} .item-body ul {{ margin:0; padding-left:18px; }}
.item-body li {{ word-break:break-word; }}
.override {{ background:var(--pill); border-left:3px solid var(--accent); padding:6px 10px; border-radius:4px; font-size:13px; }}
th.q, td.q {{ text-align:center; white-space:nowrap; }} td.q {{ font-size:16px; }}
.q-full {{ color:var(--good); }} .q-half {{ color:var(--warn); }} .q-none {{ color:var(--bad); }}
.vr {{ font-weight:700; white-space:nowrap; }} .vr.ok {{ color:var(--good); }} .vr.ng {{ color:var(--bad); }}
code {{ font-size:13px; word-break:break-all; }}
.scroll {{ overflow-x:auto; }}
footer {{ margin-top:40px; font-size:12px; color:var(--muted); border-top:1px solid var(--line); padding-top:12px; }}
footer code {{ font-size:12px; }}
@media (max-width:640px) {{ .hero {{ grid-template-columns:1fr; }} .total {{ font-size:44px; }}
  .item-body {{ padding-left:8px; }} .ticks span:nth-child(odd):not(:first-child) {{ display:none; }} }}
</style>
</head>
<body>
<main>
  <h1>AI-Ready 점수표 · {e(repo['name'])}</h1>
  <div class="muted small">{e(repo['branch'] or '-')} @ {e(repo['commit'] or '-')} · 파일 {repo['files_scanned']}개 스캔 ·
    {e(r['generated_at'])}</div>

  <section class="card hero">
    <div class="total">{total}<span>/100</span></div>
    <div>
      <div class="grade">{e(grade['level'])}</div>
      <div>{e(grade['meaning'])}</div>
      {compare}
      <div class="meter"><div class="fill"></div></div>
      <div class="ticks"><span style="left:0;transform:none">0</span><span style="left:40%">40 Fragile</span>
        <span style="left:60%">60 Assisted</span><span style="left:75%">75 Ready</span><span style="left:90%">90 Native</span></div>
      <div class="muted small" style="margin-top:6px">{ov_note}</div>
      {summary}
    </div>
  </section>

  <h2>카테고리별 점수</h2>
  <section class="card cats">{''.join(cat_rows)}</section>

  <h2>ROI 우선순위 액션</h2>
  <section class="card scroll">
    <table><thead><tr><th class="num">#</th><th>항목</th><th>액션</th><th class="num">예상 상승</th><th>노력</th>
      <th class="num">ROI</th></tr></thead><tbody>{act_rows or '<tr><td colspan="6">개선할 항목이 없습니다.</td></tr>'}</tbody></table>
    <div class="muted small" style="margin-top:8px">예상 상승 = 이 액션만 완료했을 때 오르는 점수(현재 빠진 구성요소 기준) ·
      ROI = 예상 상승 ÷ 노력(작음 1, 보통 2, 큼 3) · <span class="badge" style="margin:0">검토</span> 리뷰어가 추가한 액션</div>
  </section>
{ver_section}
  <h2>세부 항목 & 근거</h2>
  <section class="card">{''.join(item_rows)}</section>

  <h2>핵심 module 커버리지</h2>
  <section class="card scroll">
    {f'<div class="muted small" style="margin-bottom:6px">공통 경로: <code>{e(prefix)}/</code></div>' if prefix else ''}
    <table><thead><tr><th>module</th><th class="num">코드 파일</th><th>안내 경로</th><th class="num">가중치</th></tr></thead>
      <tbody>{mod_rows or '<tr><td colspan="4">module을 찾지 못했습니다.</td></tr>'}</tbody></table>
  </section>

  <h2>Five-Question Framework</h2>
  <section class="card scroll">
    <table><thead><tr><th>module</th>{fq_head}</tr></thead><tbody>{fq_rows}</tbody></table>
    <div class="muted small" style="margin-top:8px">{fq_legend} · 열 제목에 마우스를 올리면 원래 질문이 보입니다.</div>
  </section>

  <footer>
    재현 정보 · 스크립트 v{e(pv.get('script_version', '-'))} (<code>{e(pv.get('script_sha', ''))}</code>) ·
    루브릭 <code>{e(pv.get('rubric_sha', ''))}</code> ·
    보정 파일 {f"<code>{e(PurePosixPath(pv['overrides_file']).name)}</code> (<code>{e(pv.get('overrides_sha', ''))}</code>)" if pv.get('overrides_file') else '없음'}
    <div>실행: <code>{e(pv.get('command', ''))}</code></div>
  </footer>
</main>
</body>
</html>
"""


def render_actions_md(r: dict) -> str:
    prev = r.get("previous")
    head = f"총점 **{r['total']}/100** · 등급 **{r['grade']['level']}** — {r['grade']['meaning']}"
    if prev:
        d = r["total"] - prev["total"]
        head += f"  \n이전 {prev.get('label') or prev.get('commit') or ''} {prev['total']}점 → {r['total']}점 ({d:+d})"
    lines = [f"# AI-Ready 개선 액션 — {r['repo']['name']}", "", head, "",
             "예상 상승 = 이 액션만 완료했을 때 오르는 점수 · ROI = 예상 상승 ÷ 노력(작음 1, 보통 2, 큼 3)", "",
             "| # | 항목 | 액션 | 대상 | 예상 상승 | 노력 | ROI |", "|---|---|---|---|---|---|---|"]
    for a in r["actions"]:
        targets = ", ".join(f"`{t}`" for t in a.get("targets", [])[:5])
        lines.append(f"| {a['rank']} | {a['item']} | **{a['title']}** — {a['detail']} | {targets} | "
                     f"+{a['expected_gain']} | {EFFORT_KO[a['effort']]} | {a['roi']} |")
    lines += ["", "## 카테고리 점수", "", "| 카테고리 | 점수 | 이전 대비 | 단계 |", "|---|---|---|---|"]
    for c in r["categories"]:
        d = "" if not prev or c["id"] not in prev["categories"] else f"{c['score'] - prev['categories'][c['id']]:+d}"
        lines.append(f"| {c['id']}. {c['name_ko']} | {c['score']}/{c['max']} | {d} | {c['level']} |")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def file_sha(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    except OSError:
        return ""


def previous_snapshot(path: str) -> dict:
    """Summarize an earlier ai_ready_score.json so the dashboard can show per-category deltas."""
    with open(path, encoding="utf-8") as fh:
        prev = json.load(fh)
    return {
        "file": path,
        "total": prev.get("total"),
        "generated_at": prev.get("generated_at"),
        "commit": prev.get("repo", {}).get("commit"),
        "branch": prev.get("repo", {}).get("branch"),
        "script_version": prev.get("provenance", {}).get("script_version", "1.0 이하"),
        "grade": prev.get("grade", {}).get("level"),
        "categories": {c["id"]: c["score"] for c in prev.get("categories", [])},
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Score a repository against the AI-Ready rubric.")
    ap.add_argument("repo", nargs="?", default=".", help="target repository path (default: .)")
    ap.add_argument("--out", default="ai-ready-report", help="output directory (default: ./ai-ready-report)")
    ap.add_argument("--overrides", help="JSON file with judgment-based score overrides/actions/summary")
    ap.add_argument("--top", type=int, default=10, help="number of ROI actions to keep (default 10)")
    ap.add_argument("--previous", help="an earlier ai_ready_score.json to compare against (shows deltas)")
    ap.add_argument("--previous-label", help="label for the previous run, e.g. 'develop' or '2026-09-01'")
    args = ap.parse_args()

    root = Path(args.repo).resolve()
    if not root.is_dir():
        print(f"error: {root} is not a directory", file=sys.stderr)
        return 2
    out = Path(args.out).resolve()
    skill_dir = Path(__file__).resolve().parents[1]
    excludes = []
    for p in (out, skill_dir):
        try:
            excludes.append(p.relative_to(root).as_posix())
        except ValueError:
            pass

    overrides = {}
    if args.overrides:
        with open(args.overrides, encoding="utf-8") as fh:
            overrides = json.load(fh)

    repo = Repo(root, excludes)
    raw = score_repo(repo)
    result = assemble(repo, raw, overrides, args.top)
    if args.previous:
        result["previous"] = previous_snapshot(args.previous)
        if args.previous_label:
            result["previous"]["label"] = args.previous_label
    rubric = skill_dir / "references" / "rubric.md"
    result["provenance"] = {
        "script_version": SCRIPT_VERSION,
        "script_sha": file_sha(Path(__file__).resolve()),
        "rubric_sha": file_sha(rubric),
        "overrides_file": args.overrides,
        "overrides_sha": file_sha(Path(args.overrides)) if args.overrides else None,
        "command": " ".join(["python3", "score.py"] + [a for a in sys.argv[1:]]),
    }

    out.mkdir(parents=True, exist_ok=True)
    (out / "ai_ready_score.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "ai_ready_dashboard.html").write_text(render_html(result), encoding="utf-8")
    (out / "ai_ready_actions.md").write_text(render_actions_md(result), encoding="utf-8")

    print(f"{result['repo']['name']}: {result['total']}/100 ({result['grade']['level']})"
          + ("" if not result["overrides_applied"] else f"  [auto {result['auto_total']}]"))
    for c in result["categories"]:
        print(f"  {c['id']}. {c['name']:<46} {c['score']:>3}/{c['max']}")
    print("Top actions:")
    for a in result["actions"][:5]:
        print(f"  {a['rank']}. [{a['item']}] {a['title']} (+{a['expected_gain']}, effort {a['effort']}, ROI {a['roi']})")
    print(f"Wrote {out}/ai_ready_score.json, ai_ready_dashboard.html, ai_ready_actions.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
