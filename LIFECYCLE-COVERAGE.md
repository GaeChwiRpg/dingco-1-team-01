# LIFECYCLE-COVERAGE — 라이프사이클 5 단계 적용 흔적 (template)

> 5 단계 누가 무엇을 적용했는지 매트릭스 + 단계별 산출물 추적.
> 채워진 sample: [`examples/week9-team-lifecycle-coverage.md`](./examples/week9-team-lifecycle-coverage.md).

## 매트릭스

| 단계 | 책임자 | 핵심 도구 | 산출물 | 상태 |
| --- | --- | --- | --- | --- |
| 1. 기획 | 김준현 | Jira MCP, AI PRD | `PRD.md`, `DECISIONS.md` | ✅ Phase 2 완료 (D-001~D-014) |
| 2. 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI | `CLAUDE.md`, `API-CONTRACT.md`, `src/`, `.claude/` | 🔄 기준 문서만 완료, `src/` 미착수 |
| 3. 테스트 | 이용택 | Playwright MCP | `tests/e2e/`, `.github/workflows/e2e.yml` | ⏳ 스켈레톤만 (`test.skip`) |
| 4. 리뷰 | 김은빈 | Claude GitHub Actions | `.github/workflows/ai-review.yml` | ✅ PR #1 에 AI 리뷰 3회 수령·반영 |
| 5. 배포·운영 | 이용택 | Sentry MCP, Docker | `MONITORING.md`, `docker-compose.yml`, `Dockerfile` | ⏳ 미착수 |

> 인원 3명 / 단계 5개이므로 이용택이 테스트 + 배포·운영 2단계를 겸한다 (D-002).
> 코딩 단계는 전원 공동이며, 코드 범위 분할(P1/P2/P3)은 아래 참조.

## 코딩 단계 작업 패키지 (D-015)

트랜잭션 경계를 분할선으로 삼는다. 경계 계약 A·B·C 는 `CLAUDE.md` 「모듈 간 계약」에 고정돼 있어, 셋이 동시에 착수할 수 있다.

| 패키지 | 담당 | 범위 | 착수 조건 |
| --- | --- | --- | --- |
| **P1** 수신·그룹핑 | 이용택 | `POST /api/errors`, fingerprint 정규화, 그룹 upsert, 원자적 카운트 증가, UNIQUE 충돌 흡수, 캐시 put | **없음 — 즉시 착수** |
| **P2** 분류·검증 | 김준현 | AI 호출 + `@Retryable`, 카테고리별 임계값 비교, 감사 샘플링, 트랜잭션 ②, `SecurityConfig` | 계약 A 확정 (완료) |
| **P3** 검토·관측 | 김은빈 | 큐 조회·확정(트랜잭션 ③), 정책 API, `StatsService`, Actuator 메트릭 | 계약 B 확정 (완료) |

**배정 근거**

- **이용택 → P1**: 테스트 단계를 겸하므로, 동시 유입 중복 방지(측정 7)와 fingerprint 정규화 단위 테스트가 본인 테스트 산출물과 직결된다. 코드 범위도 셋 중 가장 작아 2단계 겸임 부담을 덜어준다.
- **김준현 → P2**: 기획 담당으로 D-005(감사 샘플링)·D-010(blind)·D-012(측정 원자성) 설계 의도를 가장 잘 안다. 판단이 몰린 패키지다. `SecurityConfig` 는 단독 파일이라 충돌 위험이 낮아 P2 착수 전 선작업한다.
- **김은빈 → P3**: 리뷰 담당으로 관측·통계와 시너지가 있고, blind 규칙(D-010) 준수 여부를 조회 API 코드에서 직접 통제한다.

**교차 지점 주의**

- 캐시(계약 C)는 P1 이 읽고 P2 가 쓴다 → 값 구조 변경 시 양쪽 합의 필요
- 트랜잭션 ②(P2)가 삽입한 `review_queue` 행을 ③(P3)이 소비한다 → `reason` 별 보장 사항은 계약 B 고정
- `ErrorGroupCreatedEvent`(계약 A)는 P1 발행 → P2 수신

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
- ⏳ **Phase 3** — 3명 병렬 PR 시연 (P1/P2/P3 feature 브랜치별 PR + INTEGRATION-LOG)
- ⏳ **Phase 4** — Week 10 마무리 (발표 자료, 회고, 면접 답변)

## Phase 2 통과 검증 체크리스트

| 단계 | 산출물 존재 | AI 도구 설정 | 통과 |
| --- | --- | --- | --- |
| 기획 | PRD.md, DECISIONS.md | (Jira MCP 또는 dry-run) | ⏳ |
| 코딩 | src/, CLAUDE.md, API-CONTRACT.md, .claude/ | claude.md/Commands/Hooks 모두 | ⏳ |
| 테스트 | tests/e2e/, e2e.yml workflow | Playwright MCP 시나리오 sample | ⏳ |
| 리뷰 | ai-review.yml | Claude Actions 통합 | ⏳ |
| 운영 | MONITORING.md, docker-compose.yml, Dockerfile | Sentry MCP 가이드 | ⏳ |
