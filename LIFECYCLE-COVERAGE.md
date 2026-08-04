# LIFECYCLE-COVERAGE — 라이프사이클 5 단계 적용 흔적 (template)

> 5 단계 누가 무엇을 적용했는지 매트릭스 + 단계별 산출물 추적.
> 채워진 sample: [`examples/week9-team-lifecycle-coverage.md`](./examples/week9-team-lifecycle-coverage.md).

## 매트릭스

| 단계 | 책임자 | 핵심 도구 | 산출물 | 상태 |
| --- | --- | --- | --- | --- |
| 1. 기획 | 김준현 | Jira MCP, AI PRD | `PRD.md`, `DECISIONS.md` | ✅ Phase 2 완료 (D-001~D-027) |
| 2. 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI | `CLAUDE.md`, `API-CONTRACT.md`, `src/`, `.claude/` | 🔄 baseline 완료 (PR #2~#5) — 엔티티·enum·Flyway V1/V2·정적 팩토리. **Hooks·서브에이전트 완료 (PR #7~#8)**. `service/`·`api/` 미착수, `.claude/commands/` 미작성 |
| 3. 테스트 | 이용택 | Playwright MCP | `tests/e2e/`, `.github/workflows/e2e.yml` | 🔄 `e2e.yml` + Testcontainers 테스트 4개 동작. e2e 는 health 1건만 실행, 핵심 흐름은 `test.skip` |
| 4. 리뷰 | 김은빈 | Claude GitHub Actions | `.github/workflows/ai-review.yml` | ✅ PR #1~#8 전원 AI 리뷰 수령·반영 (PR #1 은 5회 / 지적 21건) |
| 5. 배포·운영 | 이용택 | Sentry MCP, Docker | `MONITORING.md`, `docker-compose.yml`, `Dockerfile` | 🔄 산출물 3종 + `.env.example` 존재. 실기동·실 트래픽 검증은 Phase 3 |

> 인원 3명 / 단계 5개이므로 이용택이 테스트 + 배포·운영 2단계를 겸한다 (D-002).
> 코딩 단계는 전원 공동이며, 코드 범위 분할(P1/P2/P3)은 아래 참조.

## 코딩 단계 작업 패키지 (D-015)

트랜잭션 경계를 분할선으로 삼는다. 경계 계약 A·B·C 는 `CLAUDE.md` 「모듈 간 계약」에 고정돼 있어, 셋이 동시에 착수할 수 있다.

| 패키지 | 담당 | 범위 | 착수 조건 |
| --- | --- | --- | --- |
| **P1** 수신·그룹핑 | 이용택 | `POST /api/errors`, fingerprint 정규화, 그룹 upsert, 원자적 카운트 증가, UNIQUE 충돌 흡수, 캐시 put | **없음 — baseline PR 도 본인 담당이므로 즉시 착수** |
| **P2** 분류·검증 | 김준현 | AI 호출 + `@Retryable`, 카테고리별 임계값 비교, 감사 샘플링, 트랜잭션 ②, `SecurityConfig` | 계약 A 확정 (완료) + baseline `build.gradle` |
| **P3** 검토·관측 | 김은빈 | 큐 조회·확정(트랜잭션 ③), 정책 API, `StatsService`, Actuator 메트릭 | 계약 B 확정 (완료) + baseline `V1__init_schema.sql` |

> **baseline 은 PR #2~#5 로 완료됐다 — P1·P2·P3 의 착수 조건은 전부 해제된 상태다.** 셋 다 지금 바로 자기 패키지의 `service/`·`api/` 를 짜기 시작할 수 있다.
>
> **baseline = 이용택.** 착수 조건이 없는 유일한 담당이고, `docker-compose.yml`·`Dockerfile` 이 본인의 배포·운영 단계 산출물이며, baseline 이 뜨는 순간 `tests/e2e` health 테스트가 통과해 테스트 단계 산출물도 함께 확보된다.
> 다만 **`settings.gradle` + `build.gradle` + wrapper 는 20분 안에 먼저 push** 한다 — 김준현의 첫 코드(`SecurityConfig`)는 도메인 의존이 0이라 이것만으로 착수 가능하고, baseline 전체를 완성한 뒤 push 하면 반나절을 통째로 대기시킨다.

**배정 근거**

- **이용택 → P1**: 테스트 단계를 겸하므로, 동시 유입 중복 방지(측정 7)와 fingerprint 정규화 단위 테스트가 본인 테스트 산출물과 직결된다. 코드 범위도 셋 중 가장 작아 2단계 겸임 부담을 덜어준다.
- **김준현 → P2**: 기획 담당으로 D-005(감사 샘플링)·D-010(blind)·D-012(측정 원자성) 설계 의도를 가장 잘 안다. 판단이 몰린 패키지다. `SecurityConfig` 는 단독 파일이라 충돌 위험이 낮아 P2 착수 전 선작업한다.
- **김은빈 → P3**: 리뷰 담당으로 관측·통계와 시너지가 있고, blind 규칙(D-010) 준수 여부를 조회 API 코드에서 직접 통제한다.

**교차 지점 주의**

- 캐시(계약 C)는 P1 이 읽고 P2 가 쓴다 → 값 구조 변경 시 양쪽 합의 필요
- 트랜잭션 ②(P2)가 삽입한 `review_queue` 행을 ③(P3)이 소비한다 → `reason` 별 보장 사항은 계약 B 고정
- `ErrorGroupCreatedEvent`(계약 A)는 P1 발행 → P2 수신

## 측정 12개 실행 책임자

측정은 코드와 달리 **누구든 돌릴 수 있어서 아무도 안 돌리기 쉽다.** 따라서 자기 패키지가 만든 장치를 자기가 잰다 — 수치가 이상할 때 원인을 아는 사람이 같은 사람이어야 한다.

> ⚠️ **번호는 `PRD.md` §8 과 1:1 로 일치시킨다.** 이전 판은 번호가 어긋나 있었고(이 표의 1 = PRD 6, 4·6 은 PRD §8 에 없는 항목), 그 결과 **PRD 측정 1·4 에 책임자가 없었다** — 이 표가 막으려던 바로 그 구멍이다. `PRD.md` §5 비기능 측정은 아래 별도 표로 분리한다.

**§8 도메인 측정 지표**

| 측정 | 내용 | 책임자 | 시점 |
| --- | --- | --- | --- |
| 1 | AI 분류 일치율 — 10종 × 5건 = 50건 정답 대조, 신뢰도 구간별 | 김준현 (P2) | Day 4 |
| 2 | 검토 큐 적체율 — 사유별 삽입 건수 | 김준현 (P2) | Day 4 |
| 3 | 판정 트랜잭션 롤백 + `stuckNew` 증가 확인 | 김은빈 (P3) | **Day 3 필수 체크포인트** |
| 4 | 재시도 동작 — AI 오류 주입 → 재시도 횟수·간격 + 최종 큐 삽입 | 김준현 (P2) | Day 3 |
| 5ⓐ~ⓓ | 조회 `EXPLAIN` 4 케이스 | 김은빈 (P3) | Day 4 |
| 5ⓔ | **인덱스 유무별 `POST /api/errors` 쓰기 p95** (V2 전/후) | 이용택 (P1) | Day 4 |
| 6 | **AI 호출 절감률** (그룹핑 효과) — 1000건 투입 대비 실제 호출 수 | 이용택 (P1) | Day 4 |
| 7ⓐ | 20스레드 동시 `POST` → 그룹 1개만 생성 | 이용택 (P1) | Day 2 오후 |
| 7ⓑ | 동시 `PATCH` → 409 2종 비율 (`CONCURRENT_UPDATE` 0 이면 무효) | 김은빈 (P3) | Day 2 오후 |
| 8 | 신뢰도 구간별 실측 오분류율 — **이 프로젝트의 결론** | 김준현 (P2) | Day 4 |
| 9 | 카테고리별 임계값 vs 단일 임계값 대조 (`policy.mode`) | 김준현 (P2) | Day 4 |
| 10 | blind 무결성 — 결정적 역산 0건 / 확률적 추론 목록화 | 김준현 (P2) | Day 5 재점검 |
| 11 | 캐시 hit rate (절감률과 별개 지표임을 수치로 확인) | 김은빈 (P3) | Day 4 |
| 12 | **검토자 간 일치도** — 측정 8 에서 검토자 불일치분 분리 (D-027) | 김준현 (P2) 집계<br>독립 분류: 이용택·김은빈 | Day 5 |

**§5 비기능 측정** — 목표치는 `PRD.md` §5, 수치는 본인 `hey` 실측만

| 측정 | 내용 | 책임자 | 시점 |
| --- | --- | --- | --- |
| §5-a | `POST /api/errors` p95 < 100ms (AI 지연 비전파 확인) | 이용택 (P1) | Day 4 |
| §5-b | `GET /api/review-queue` p95 < 200ms + **N+1 제거 전후 쿼리 수** | 김은빈 (P3) | Day 4 |

> 측정 5ⓔ 만 P1 이 맡는 이유: A/B 대상이 **수신 경로의 쓰기 지연**이라 부하 스크립트를 P1 이 이미 갖고 있다 (측정 6·7ⓐ·§5-a 와 같은 도구).
> 측정 3 을 P3 가 맡는 이유: `stuckNew` gauge 를 노출하는 코드가 P3 소유다. 만든 사람이 지표가 0 인 것이 "정상"인지 "안 세고 있는 것"인지 구분할 수 있다.
> 측정 12 의 **독립 분류자가 P2 가 아닌 이유**: 측정 8 의 표본을 뽑고 집계하는 사람이 직접 분류하면 대조군이 오염된다. 두 분류자는 서로의 결과를 보지 않는다.
> 측정 1·4·12 가 김준현에게 몰린 것은 **AI 호출·재시도·정답 레이블이 전부 P2 소유**이기 때문이다. 다만 Day 4 에 5건이 겹치므로 측정 4 를 Day 3(측정 3 과 같은 날)로 당겨 분산한다.

## 단계별 적용 흐름

### 1. 기획

- 산출물: `PRD.md`, `DECISIONS.md`
- 도구: (예: Jira MCP — PRD 분해 시 사용한 prompt + AI 보조 흔적)
- 한계: (예: Phase 2 단계라 Jira 시연은 dry-run, 실 Jira 통합은 Phase 3)

### 2. 코딩

- 산출물: `src/`, `CLAUDE.md`, `API-CONTRACT.md`, `.claude/` (agents / hooks / scripts)
- 도구:
  - claude.md 헌법 — 모든 prompt 자동 포함
  - Hooks — `.claude/hooks/dispatcher.sh` (PreToolUse) → `handlers/constitution-guard.py` (편집 시점 헌법 위반 차단) + `handlers/verify-before-push.sh` (push 전 `./gradlew test`)
  - 서브에이전트 5종 — `.claude/agents/` (constitution-auditor / implementation / test / refactoring / docs)
  - Commands — **미작성**. `.claude/commands/` 디렉토리 자체가 없다
- 한계: `verify-before-push.sh` 는 로컬 Docker 환경에 의존한다 (D-026 — Docker Desktop 4.44.2 이하). 조건이 깨지면 코드와 무관하게 push 가 막힌다

### 3. 테스트

- 산출물: `tests/e2e/*.spec.ts`, `.github/workflows/e2e.yml`
- 도구: Playwright MCP — 시나리오 N개 (성공 + 실패 케이스)
- 한계: (예: 시나리오 4개 확장은 Phase 3)

### 4. 리뷰

- 산출물: `.github/workflows/ai-review.yml`, `evidence/failure-cases.md` (AI hallucination·오류 **13건** 기록)
- 도구: Claude GitHub Actions Review — 팀 CLAUDE.md 핵심 룰 prompt 전달
- 한계: PR #1 부터 전원 자동 리뷰가 동작했다. 다만 검출은 **문서·설계 층에 집중**돼 있고, `service/`·`api/` 미착수라 **런타임 결함에 대한 검출력은 아직 미검증**이다 (`evidence/failure-cases.md` 「미검출 위험이 남은 영역」 참조)

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
| 기획 | ✅ PRD.md, DECISIONS.md (D-001~D-027) | ⏳ Jira MCP dry-run 미시연 | 🔄 |
| 코딩 | ✅ src/ (엔티티·enum·repository·Flyway), CLAUDE.md, API-CONTRACT.md | 🔄 claude.md ✅ / Hooks ✅ / 서브에이전트 5종 ✅ / **Commands 미작성** | 🔄 |
| 테스트 | ✅ tests/e2e/, e2e.yml, Testcontainers 테스트 4개 | ⏳ Playwright MCP 시나리오는 health 1건뿐 | 🔄 |
| 리뷰 | ✅ ai-review.yml | ✅ PR #1~#8 전원 자동 리뷰 동작 | ✅ |
| 운영 | ✅ MONITORING.md, docker-compose.yml, Dockerfile, .env.example | ⏳ Sentry MCP 미연동 | 🔄 |

> **남은 4건이 Phase 2 의 실제 잔여 작업이다** — Jira MCP dry-run / `.claude/commands/` / e2e 시나리오 확장 / Sentry MCP.
> 넷 다 산출물은 있고 **AI 도구 설정만 비어 있다.** 이 표를 ⏳ 로 방치하면 "무엇이 남았는지"가 아니라 "아무것도 안 됐다"로 읽혀서, 실제 잔여 작업이 가려진다.
> PR #7~#8 로 Hooks·서브에이전트가 채워져 잔여가 5건 → 4건으로 줄었다. `.claude/commands/` 는 디렉토리 자체가 없으므로 **여기가 코딩 단계의 유일한 남은 도구 항목**이다.
