# Week 9 Lifecycle Coverage — 5 단계 적용 흔적 sample

> 팀 레포 루트의 `LIFECYCLE-COVERAGE.md` 또는 `docs/lifecycle.md`로 보관하는 sample.
> 5 단계 각각에 어떤 도구를 어떻게 적용했고 누가 책임졌는지를 증명.

## 팀 정보

- 팀: cohort1-team-A
- 프로젝트: 운영 티켓 시스템 (추천 1번)
- 4명 팀

## 단계별 적용 흔적

### 1. 기획 (책 9주차 03 — Jira MCP)

- 책임자: 팀원 A
- 도구: Jira MCP + Claude Code
- 산출물: `PRD.md` v1.0 (50줄), Jira 프로젝트 TICKET-1 ~ TICKET-23
- 적용 흐름:
  1. 팀 미팅에서 추천 프로젝트 1번(운영 티켓) 선택
  2. Claude Code에 `/jira-mcp` 연결 후 PRD 초안 자동 생성
  3. 팀원 4명이 PRD 검토 + 기능 우선순위 합의
  4. AI에게 PRD를 Jira 티켓 23개로 분해 위임
  5. 팀원별 티켓 할당
- 사람-only 대비: PRD 작성 약 4시간 → 1시간 (AI 초안 + 검토)
- 검증: PRD 페르소나 정의 1개 + 성공 지표 3개 + 제약 사항 5개 모두 사람 최종 검토

### 2. 코딩 (책 7주차 04~07, 9주차 02·04·05)

- 책임자: 4명 모두 (각자 기술 영역 + 팀 헌법은 공동)
- 도구:
  - `CLAUDE.md` 팀 헌법 (도메인 + 코딩 규칙 + 작업 경계)
  - `.claude/commands/run-explain.md`, `.claude/commands/draft-pr.md` 2개
  - `.claude/settings.json` PreToolUse hook 1개 (미션 경로 가드)
  - gh CLI + AI로 PR 본문 자동 작성
  - 팀원 C는 Sub-agents로 락 전략 후보 3개 동시 비교
- 산출물: 본 레포 코드 + `CLAUDE.md` + `API-CONTRACT.md`
- 검증 루프 4단계 (Week 8 정착):
  1. prompt 작성 시 페·목·형·제 강제
  2. claude.md 검증 규칙 적용
  3. PreToolUse hook
  4. PR 머지 전 본인 직접 실측

### 3. 테스트 (책 9주차 06 — Playwright MCP)

- 책임자: 팀원 B
- 도구: Playwright MCP (Claude Code MCP 연결)
- 산출물: `tests/e2e/` 폴더에 시나리오 4개
  - `login-flow.spec.ts`
  - `ticket-create.spec.ts`
  - `ticket-assign.spec.ts`
  - `inventory-decrease.spec.ts` (팀원 C 도메인)
- 적용 흐름:
  1. AI에게 PRD 1.4(주요 사용자 흐름)을 Playwright 시나리오로 변환 위임
  2. AI가 만든 시나리오 4개를 사람이 검수 + 셀렉터 수정
  3. CI에서 자동 실행 (`.github/workflows/e2e.yml`)
- 사람-only 대비: 시나리오 4개 작성 약 6시간 → 2시간
- AI 실패 사례: 셀렉터를 옛날 페이지 기준으로 만들어냄 (3건). 사람이 실제 페이지에서 직접 확인 후 수정.

### 4. 리뷰 (책 10주차 01 — Claude GitHub Actions)

- 책임자: 팀원 C
- 도구: Claude GitHub Actions Review (`anthropics/claude-code-action@v1`)
- 산출물: `.github/workflows/ai-review.yml` + 리뷰 메모 5건
- 설정: PR opened/synchronize 시 자동 트리거. 팀 코딩 규칙(`CLAUDE.md`)을 prompt로 전달.
- AI vs 팀원 리뷰 비교:
  - AI 강점: 일관성 (모든 PR에 같은 룰 적용), 빠른 첫 패스
  - 사람 강점: 비즈니스 의도 검증, 도메인 트레이드오프 판단
  - 결론: AI 리뷰는 first-pass, 팀원 리뷰는 second-pass로 운영
- AI 리뷰가 잡은 사례 1건: PR #14에서 트랜잭션 경계 누락 → 팀원이 즉시 수정

### 5. 배포·운영 (책 10주차 06 — Sentry MCP)

- 책임자: 팀원 D
- 도구: Sentry MCP (Claude Code 연결) + Docker Compose (배포)
- 산출물: `MONITORING.md` + `docker-compose.yml`
- 적용 흐름:
  1. Sentry 프로젝트 생성 + Spring Boot에 Sentry SDK 통합
  2. Claude Code에 Sentry MCP 연결
  3. 시연용 에러 1건을 의도적으로 발생 → AI에게 Sentry 데이터 분석 위임
  4. AI가 root cause 후보 3개 제안 → 팀원이 검증 후 fix
- 한계: 실제 트래픽이 적어 운영 학습은 시연 수준. Week 10에 보강.

## 5 단계 통과 검증

| 단계 | 산출물 | AI 도구 적용 | 사람 검증 | 통과 |
| --- | --- | --- | --- | --- |
| 기획 | PRD.md, Jira 티켓 23개 | Jira MCP + AI 분해 | 팀 4명 검토 | ✅ |
| 코딩 | CLAUDE.md + API-CONTRACT + 코드 | claude.md/Commands/Hooks/Sub-agents | 검증 루프 4단계 | ✅ |
| 테스트 | tests/e2e/ 시나리오 4개 | Playwright MCP | 셀렉터 사람 검수 | ✅ |
| 리뷰 | ai-review.yml + 리뷰 메모 | Claude Actions | 팀원 second-pass | ✅ |
| 운영 | MONITORING.md + docker-compose | Sentry MCP | 팀원 D root cause 검증 | ✅ |

## 회고

- 가장 효과 큰 단계: **테스트** (Playwright MCP). 시나리오 작성 시간이 절반 이하로.
- 가장 효과 작은 단계: **운영** (Sentry MCP). 실제 트래픽이 없어 도구 학습은 됐지만 가치 검증은 미흡.
- Week 10에 보강할 단계: 운영 (실제 부하 시나리오로 Sentry 활용 깊이 증명).
