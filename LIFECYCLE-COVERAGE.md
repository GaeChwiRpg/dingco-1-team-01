# LIFECYCLE-COVERAGE — 라이프사이클 5 단계 적용 흔적 (template)

> 5 단계 누가 무엇을 적용했는지 매트릭스 + 단계별 산출물 추적.
> 채워진 sample: [`examples/week9-team-lifecycle-coverage.md`](./examples/week9-team-lifecycle-coverage.md).

## 매트릭스

| 단계 | 책임자 | 핵심 도구 | 산출물 | 상태 |
| --- | --- | --- | --- | --- |
| 1. 기획 | (팀원 A) | Jira MCP, AI PRD | `PRD.md`, `DECISIONS.md` | (Phase 2 ✅ / Phase 3 ⏳) |
| 2. 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI | `CLAUDE.md`, `API-CONTRACT.md`, `src/`, `.claude/` | |
| 3. 테스트 | (팀원 B) | Playwright MCP | `tests/e2e/`, `.github/workflows/e2e.yml` | |
| 4. 리뷰 | (팀원 C) | Claude GitHub Actions | `.github/workflows/ai-review.yml` | |
| 5. 배포·운영 | (팀원 D) | Sentry MCP, Docker | `MONITORING.md`, `docker-compose.yml`, `Dockerfile` | |

## 단계별 적용 흐름

### 1. 기획

- 산출물: `PRD.md`, `DECISIONS.md`
- 도구: (예: Jira MCP — PRD 분해 시 사용한 prompt + AI 보조 흔적)
- 한계: (예: Phase 2 단계라 Jira 시연은 dry-run, 실 Jira 통합은 Phase 3)

### 2. 코딩

- 산출물: `src/`, `CLAUDE.md`, `API-CONTRACT.md`, `.claude/` (commands / hooks)
- 도구:
  - claude.md 헌법 — 모든 prompt 자동 포함
  - Commands — `.claude/commands/draft-pr.md` 등
  - Hooks — `.claude/hooks/pre-tool-use.sh` (비밀 정보 / 미션 외 수정 차단)
- 한계: (예: Phase 3 진입 시 JWT 본격 통합)

### 3. 테스트

- 산출물: `tests/e2e/*.spec.ts`, `.github/workflows/e2e.yml`
- 도구: Playwright MCP — 시나리오 N개 (성공 + 실패 케이스)
- 한계: (예: 시나리오 4개 확장은 Phase 3)

### 4. 리뷰

- 산출물: `.github/workflows/ai-review.yml`
- 도구: Claude GitHub Actions Review — 팀 CLAUDE.md 핵심 룰 prompt 전달
- 한계: (예: Day 4 PR 부터 자동 활성화. 초반 PR 은 사람 리뷰만)

### 5. 배포·운영

- 산출물: `MONITORING.md`, `docker-compose.yml`, `Dockerfile`
- 도구: Sentry MCP, Docker Compose
- 한계: (예: Phase 2 단계라 실 트래픽 부재 → Phase 3 의도적 NPE 시나리오로 보강)

## Phase 진행 상태

- ✅ **Phase 1** — 기획 골격 (README, PRD, DECISIONS 초안)
- 🔄 **Phase 2** — 코드 baseline + 5 단계 도구 설정
- ⏳ **Phase 3** — 가상 4명 팀 PR 시연 (feature 브랜치별 PR + INTEGRATION-LOG)
- ⏳ **Phase 4** — Week 10 마무리 (발표 자료, 회고, 면접 답변)

## Phase 2 통과 검증 체크리스트

| 단계 | 산출물 존재 | AI 도구 설정 | 통과 |
| --- | --- | --- | --- |
| 기획 | PRD.md, DECISIONS.md | (Jira MCP 또는 dry-run) | ⏳ |
| 코딩 | src/, CLAUDE.md, API-CONTRACT.md, .claude/ | claude.md/Commands/Hooks 모두 | ⏳ |
| 테스트 | tests/e2e/, e2e.yml workflow | Playwright MCP 시나리오 sample | ⏳ |
| 리뷰 | ai-review.yml | Claude Actions 통합 | ⏳ |
| 운영 | MONITORING.md, docker-compose.yml, Dockerfile | Sentry MCP 가이드 | ⏳ |
