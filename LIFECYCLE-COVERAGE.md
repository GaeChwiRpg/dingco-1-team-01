# LIFECYCLE-COVERAGE — 라이프사이클 5 단계 적용 흔적

> 5 단계 누가 무엇을 적용했는지 매트릭스 + 단계별 산출물 추적.
> 일자별 진행은 `INTEGRATION-LOG.md`, 결정 근거는 `DECISIONS.md`.
> 참고용 sample: [`examples/week9-team-lifecycle-coverage.md`](./examples/week9-team-lifecycle-coverage.md).
> **기준 시점: 2026-08-05** (PR #1~#10 머지 / **PR #11 = 도메인 전환 진행 중 — D-031**).
>
> ⚠️ **2026-08-05 도메인 전환**: 에러 분류 → CS 문의 분류 (D-031). 라이프사이클 5단계의 **구조·책임자·도구는 그대로**이고 각 단계의 산출물 내용만 바뀐다. 코딩 단계의 baseline(엔티티·스키마)은 **재작성 대상**이 됐다.

## 매트릭스

| 단계 | 책임자 | 핵심 도구 | 산출물 | 상태 |
| --- | --- | --- | --- | --- |
| 1. 기획 | 김준현 | Jira MCP, AI PRD | `PRD.md`, `DECISIONS.md` | ✅ Phase 2 완료 (D-001~D-051) — **D-031 으로 도메인 전환, PRD 전면 재작성** / **D-043·D-044 로 PRD 를 PAAR 골격에 맞춤** / **D-051 로 CodeRabbit 병행 리뷰 도입** |
| 2. 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI | `CLAUDE.md`, `API-CONTRACT.md`, `src/`, `.claude/` | 🔄 baseline **재작성** (D-031) — 엔티티 3종·enum·Flyway V1/V2·정적 팩토리. Hooks 완료 (PR #7), 서브에이전트 5종 완료 (PR #8). `service/`·`api/` 미착수, `.claude/commands/` 미작성 |
| 3. 테스트 | 이용택 | Playwright MCP | `tests/e2e/`, `.github/workflows/e2e.yml` | 🔄 `e2e.yml` + Testcontainers 8건 + 순수 단위 8건 동작. e2e 는 health 1건만 실행, 핵심 흐름은 `test.skip` |
| 4. 리뷰 | 김은빈 | Claude GitHub Actions, CodeRabbit | `.github/workflows/ai-review.yml`, `evidence/failure-cases.md`, `.coderabbit.yaml` | ✅ PR #1~#11 전원 AI 리뷰 수령·반영 (PR #1 은 5회 / 지적 21건). **리뷰가 틀린 사례도 1건 기록** — 사례 16. ✅ CodeRabbit 은 D-051 로 병행 도입, `.coderabbit.yaml` 은 Inquiry 도메인 용어로 작성 완료, **GitHub App 설치 완료 — 현재 PR 마다 실제로 동작 중** |
| 5. 배포·운영 | 이용택 | Sentry MCP, Docker | `MONITORING.md`, `docker-compose.yml`, `Dockerfile` | 🔄 Sentry SDK + Source Context + MCP 연동 실측 완료(`SENTRY-GUIDE.md`, PR #9). `MONITORING.md`·`SENTRY-GUIDE.md` 실측 기록 완료. 실제 `api/`·`service/` 트래픽 검증은 남음 |

> 인원 3명 / 단계 5개이므로 이용택이 테스트 + 배포·운영 2단계를 겸한다 (D-020).
> 코딩 단계는 전원 공동이며, 코드 범위 분할(P1/P2/P3)은 아래 참조.

## 코딩 단계 작업 패키지 (D-015)

트랜잭션 경계를 분할선으로 삼는다. 경계 계약 A·B·C 는 `CLAUDE.md` 「모듈 간 계약」에 고정돼 있어, 셋이 동시에 착수할 수 있다.

| 패키지 | 담당 | 범위 | 착수 조건 |
| --- | --- | --- | --- |
| **P1** 접수·절감 경로 | 이용택 | `POST /api/inquiries`, **정규화 규칙 + 마스킹**, 트랜잭션 ①, 계약 A 발행, **2단 절감 경로 + 캐시 레이어**, 재사용 조회 우선순위(D-033) | **없음 — baseline PR 도 본인 담당이므로 즉시 착수** |
| **P2** 분류·검증 | 김준현 | AI 호출 + `@Retryable`, 임계값 비교, 감사 샘플링, 트랜잭션 ②, `SecurityConfig` | 계약 A 확정 (완료) + baseline `build.gradle` |
| **P3** 검토·관측 | 김은빈 | 큐 조회·확정(트랜잭션 ③), `GET /api/policies`(읽기 전용), `StatsService`, Actuator 메트릭 | 계약 B 확정 (완료) + baseline `V1__init_schema.sql` |

> **P1 의 범위는 D-031 으로 재배정됐다.** 그룹핑이 사라지면서 P1 이 "접수 CRUD" 하나로 얇아졌기 때문에, **정규화 규칙과 2단 절감 경로·캐시 레이어를 P1 소유로 옮겼다.** 계약 C(캐시)도 이전에는 P1 읽기 / P2 쓰기였으나 이제 **P1 이 읽고 쓴다** — 절감 판단이 전부 P1 안에서 끝난다.

> **baseline 은 PR #2~#5 로 완료됐다 — P1·P2·P3 의 착수 조건은 전부 해제된 상태다.**
>
> **현재 미착수**: `service/`·`api/` 전체, `.claude/commands/` 미작성.
> **측정 수치**: 전부 미실측 (코드 착수 후 채울 예정).
> **E2E 시나리오**: health 1건만 실행, 핵심 흐름은 `test.skip`.
>
> **baseline = 이용택.** 착수 조건이 없는 유일한 담당이고, `docker-compose.yml`·`Dockerfile` 이 본인의 배포·운영 단계 산출물이며, baseline 이 뜨는 순간 `tests/e2e` health 테스트가 통과해 테스트 단계 산출물도 함께 확보된다.
> 다만 **`settings.gradle` + `build.gradle` + wrapper 는 20분 안에 먼저 push** 한다 — 김준현의 첫 코드(`SecurityConfig`)는 도메인 의존이 0이라 이것만으로 착수 가능하고, baseline 전체를 완성한 뒤 push 하면 반나절을 통째로 대기시킨다.

**배정 근거**

- **이용택 → P1**: 테스트 단계를 겸하므로, **정규화 규칙의 과도/과소 병합 단위 테스트**(D-031 최대 리스크)가 본인 테스트 산출물과 직결된다. 그룹핑 폐기로 범위가 줄었던 것을 절감 경로 소유로 메웠다.
- **김준현 → P2**: 기획 담당으로 D-005(감사 샘플링)·D-010(blind)·D-012(측정 원자성) 설계 의도를 가장 잘 안다. 판단이 몰린 패키지다. `SecurityConfig` 는 단독 파일이라 충돌 위험이 낮아 P2 착수 전 선작업한다.
- **김은빈 → P3**: 리뷰 담당으로 관측·통계와 시너지가 있고, blind 규칙(D-010) 준수 여부를 조회 API 코드에서 직접 통제한다.

**교차 지점 주의**

- 캐시(계약 C)는 **P1 이 읽고 쓰고, P2 가 ② 커밋 후, P3 가 ③ 커밋 후 put 한다** (D-036) → 값 구조 변경 시 **3자** 합의 필요. **P3 가 캐시를 건드리는 것은 이번이 처음**이라 여기가 가장 놓치기 쉬운 자리다
- 트랜잭션 ②(P2)가 삽입한 `inquiry_review_queue` 행을 ③(P3)이 소비한다 → `reason` 별 보장 사항은 계약 B 고정
- `InquiryReceivedEvent`(계약 A)는 P1 발행 → P2 수신. **전건 발행이므로 P2 의 분류 담당이 절감 여부를 판단**한다 (D-031)

## 측정 12개 실행 책임자

측정은 코드와 달리 **누구든 돌릴 수 있어서 아무도 안 돌리기 쉽다.** 따라서 자기 패키지가 만든 장치를 자기가 잰다 — 수치가 이상할 때 원인을 아는 사람이 같은 사람이어야 한다.

> ⚠️ **번호는 `PRD.md` §9 「확인할 목록」 과 1:1 로 일치시킨다.** 이전 판은 번호가 어긋나 있었고 그 결과 **PRD 측정 1·4 에 책임자가 없었다** — 이 표가 막으려던 바로 그 구멍이다. 속도 목표(§9 하단)는 아래 별도 표로 분리한다.
>
> **D-031 으로 12개 → 11개.** 통째로 사라진 것은 **측정 9(임계값 대조군)** 하나이고, **5ⓔ·7ⓐ** 는 상위 번호가 남는 하위 항목 삭제다. **남은 번호는 당기지 않았다** — 당기면 `evidence/` 와 커밋 이력의 참조가 전부 어긋난다.
>
> **D-043 으로 11개 → 12개.** 늘어난 것은 **8ⓐ 를 8ⓐ-1 / 8ⓐ-2 로 쪼갠 것** 하나뿐이다. 새 측정을 추가한 게 아니라 **하나로는 읽을 수 없던 것을 둘로 나눈 것**이다 — 감사 5% 로 뽑히는 표본이 2건뿐이라 비율이 안 나왔다. 상위 번호 `8ⓐ` 는 그대로 두고 하위를 붙였으므로 기존 참조는 깨지지 않는다.

**측정보다 먼저 있어야 하는 것 — 정답 문의 50건 (D-046)**

| 작업 | 책임자 | 시점 |
| --- | --- | --- |
| 본문 50건 + 정답 초안 (경계 9종 각 1건 이상, 개인정보 문자열 섞기) | 김준현 (P2) | **0단계 — 지금 ~ Day 1 오전** |
| 두 번째 사람이 본문만 보고 정답 독립 부여 | 이용택 또는 김은빈 | Day 1 오후 |
| 갈린 건 §7 경계표로 조정 → 확정 + **2인 일치율 기록** | 셋이 함께 | Day 1 종료 전 |
| 1000건으로 파생 (측정 6용, 같은 뜻 묶음 번호 승계) | 이용택 (P1) | Day 2 |

> **측정 1·8ⓐ-1 은 이 파일이 없으면 시작조차 못 한다.** 코드와 같은 날(Day 4)에 만들면 둘 다 못 끝낸다.
> 3행의 **2인 일치율이 측정 12 의 예비값**이다 — 낮게 나오면 Day 5 전에 `PRD.md` §7 을 고칠 시간이 생긴다.

**도메인 측정 지표** (`PRD.md` §9)

| 측정 | 내용 | 책임자 | 시점 |
| --- | --- | --- | --- |
| 1 | AI 분류 일치율 — 10종 × 5건 = 50건 정답 대조, 신뢰도 구간별. **재사용 끈 상태** (D-043 ⓒ) | 김준현 (P2) | Day 4 |
| 2 | 검토 큐 적체율 — 사유별 삽입 건수 + **같은 문의에 신호를 두 번 보내도 큐가 1건인지** (D-049) | 김준현 (P2) | Day 4 |
| 3 | **롤백 경계 검증** — ② 롤백 + ① **생존** 동시 확인 + `stuckReceived` 증가 (D-031) + **감사 삽입을 `REQUIRES_NEW` 로 뺀 대조 — 표본이 몇 건 새는지** (D-045 ②) | 김은빈 (P3) | **Day 3 필수 체크포인트** |
| 4 | 재시도 동작 — AI 오류 주입 → 재시도 횟수·간격 + 최종 큐 삽입 | 김준현 (P2) | Day 3 |
| 5ⓐ~ⓓ·ⓕ | 조회 `EXPLAIN` 5 케이스 (큐 / 문의 목록 / **2단 절감 경로 1순위·2순위 조인 쿼리 2개** — D-037 / **ⓕ 깊은 오프셋** — 10만 건에서 1페이지 vs 500페이지, D-045 ③). ⓓ 는 `final_category IS NOT NULL` 이 서버 필터라 `rows` 가 키당 얼마나 늘어나는지를 본다.<br>⚠️ **ⓔ 는 D-031 에서 삭제된 번호라 재사용하지 않는다** — 당겨 쓰면 옛 evidence 참조와 어긋난다 | 김은빈 (P3) | Day 4 |
| 6 | **정규화 키의 중복 포착률·과도 병합률** (2단 경로 효과) — 1000건. **입력의 정답 중복 비율을 먼저 세어 적는다.** 절감률 단일 수치로 보고하지 않는다 (D-043 ⓑ) + **동시 유입 중복 호출분** | 이용택 (P1) | Day 4 |
| 7ⓑ | 동시 `PATCH` → 409 2종 비율 (`CONCURRENT_UPDATE` 0 이면 무효) | 김은빈 (P3) | Day 2 오후 |
| 8ⓐ-1 | 신뢰도 구간별 실측 오분류율 (자동 확정 **전수**를 정답 레이블과 대조) — **이 프로젝트의 결론** (D-043 ⓐ) | 김준현 (P2) | Day 4 |
| 8ⓐ-2 | **5% 감사가 8ⓐ-1 의 오분류를 몇 건 집어냈나** — 두 값의 차이가 샘플링의 검출 한계 (D-043 ⓐ) | 김준현 (P2) | Day 5 |
| 8ⓑ | **재사용 건의 오분류율** — ⓐ 와 섞지 않는다. 비교할 AI 답이 없기 때문 (D-033) | 김준현 (P2) | Day 4 |
| 10 | blind 무결성 — **계산으로 확실히 알아내는 경우 0건** / **짐작으로 알아채는 경우** 목록화 | 김준현 (P2) | Day 5 재점검 |
| 11 | 캐시 hit rate (절감률과 별개 지표임을 수치로 확인) + **③ 확정 직후 같은 키 문의가 사람 답을 받는지** (D-036) + **키 버전 올린 뒤 옛 답이 안 나오는지 / Redis 를 끄고도 접수·분류가 되는지** (D-045 ④) + **`evicted_keys`·메모리 사용량** (D-048) | 김은빈 (P3) | Day 4 |
| 12 | **검토자 간 일치도** — 측정 8 에서 검토자 불일치분 분리 (D-027) | 김준현 (P2) 집계<br>독립 분류: 이용택·김은빈 | Day 5 |

**속도 측정** — 목표치는 `PRD.md` §9 하단, 수치는 본인 `hey` 실측만

| 측정 | 내용 | 책임자 | 시점 |
| --- | --- | --- | --- |
| a | `POST /api/inquiries` p95 < 100ms (AI 지연 비전파 확인) + **대기줄 포화 시 접수 지연과 유실 0건 확인** (D-045 ⑤) + **지연 원인이 대기줄인지 DB 연결 고갈인지 구분** + **종료 중 유실 0건** (D-047) | 이용택 (P1) | Day 4 |
| b | `GET /api/inquiry-review-queue` p95 < 200ms + **N+1 제거 전후 쿼리 수** + **목록 마스킹 재계산 비용** (D-040) + **`@EntityGraph` 대 필요한 칸만 조회 — 쿼리 수·응답시간 대조** | 김은빈 (P3) | Day 4 |

> **측정 6 이 D-031 이후 가장 중요한 측정이다.** 정규화 키의 hit rate 가 0 에 수렴하면 「캐시」 필수 기능의 정당화가 무너진다 — D-031 재평가 조항의 발동 조건이 여기서 나온다. P1 이 맡는 이유는 정규화 규칙의 소유자가 P1 이기 때문이다.
> 측정 3 을 P3 가 맡는 이유: `stuckReceived` gauge 를 노출하는 코드가 P3 소유다. 만든 사람이 지표가 0 인 것이 "정상"인지 "안 세고 있는 것"인지 구분할 수 있다.
> 측정 12 의 **독립 분류자가 P2 가 아닌 이유**: 측정 8 의 표본을 뽑고 집계하는 사람이 직접 분류하면 대조군이 오염된다. 두 분류자는 서로의 결과를 보지 않는다. **CS 문의는 카테고리 경계가 에러보다 애매하므로 이 측정의 중요도가 올라갔다** (`PRD.md` §4-0 경계표를 조일 근거).
> 측정 1·4·12 가 김준현에게 몰린 것은 **AI 호출·재시도·정답 레이블이 전부 P2 소유**이기 때문이다. 다만 Day 4 에 겹치므로 측정 4 를 Day 3(측정 3 과 같은 날)로 당겨 분산한다.

## 단계별 적용 흐름

### 1. 기획

- 산출물: `PRD.md`(12절 + PAAR 한 장 요약 · US 13개 · 측정 12개), `DECISIONS.md`(D-001~D-051)
- 도구:
  - **AI PRD** — 페·목·형·제 4요소 prompt 로 초안 생성 → AI 코드리뷰 5회로 반증. 리뷰가 잡은 설계 결함 9건은 전부 **AI 가 만든 설계**였고, 그중 무엇을 고치고 무엇을 한계로 수용할지는 사람이 정했다 (`evidence/failure-cases.md` 관찰 2)
  - **결정 로그를 불변으로 운영** — 기존 항목을 고치지 않고 후속 항목으로 무효화한다. D-004(캐시=절감률 등식) → D-014, D-007(재시도 경계) → D-016, D-002 → D-020 이 그 예다. 덮어썼다면 "왜 틀렸었는지"가 사라졌다
  - **도메인 전환도 같은 방식으로 처리했다 (D-031)** — 결정 14건(폐기 6 + 부분 개정 8)이 정리됐지만 **본문은 하나도 지우지 않고 상태 필드만 갱신**했다. 그 결과 "무엇을 포기하고 이 도메인으로 왔는가"가 로그에 남는다. 전환 근거를 D-027 이 세운 판별 기준으로 평가한 것도 같은 취지다 — **기준이 있으니 탈락 사실과 그 대가를 계산할 수 있었다**
- 한계:
  - **Jira MCP 미시연.** `.mcp.json` 에 Atlassian MCP 서버는 등록했으나(커밋 `3c2c782`) PRD → 이슈 분해를 실제로 돌리지 않았다. 잔여 3건 중 1건
  - PRD 의 측정 목표치는 **전부 미실측**이다. 값이 채워지는 시점은 `service/`·`api/` 착수 이후이며, 그 전까지 목표치는 목표일 뿐 evidence 가 아니다

### 2. 코딩

- 산출물: `src/`, `CLAUDE.md`, `API-CONTRACT.md`, `.claude/` (agents / hooks / scripts)
- 도구:
  - claude.md 헌법 — 모든 prompt 자동 포함
  - Hooks — `.claude/hooks/dispatcher.sh` (PreToolUse) → `handlers/constitution-guard.py` (편집 시점 헌법 위반 차단) + `handlers/verify-before-push.sh` (push 전 `./gradlew test`)
  - 서브에이전트 5종 — `.claude/agents/` (constitution-auditor / implementation / test / refactoring / docs). PR #8 머지 완료. **도메인 전환 시 함께 갱신했다** — 프롬프트는 헌법의 사본이 아니라 "어떻게 검증하는가"라서 도메인이 바뀌면 낡고, **낡은 프롬프트는 틀린 규칙을 자신 있게 강제**한다
  - Commands — **미작성**. `.claude/commands/` 디렉토리 자체가 없다
- **훅과 서브에이전트의 역할이 다르다**: 훅은 조건이 맞으면 무조건 돌고 정적으로 확실한 것만 본다(비밀 파일·크리덴셜 하드코딩·`api/` 의 `@Transactional`·**커밋된 마이그레이션 수정**). 판단이 필요한 규칙(트랜잭션 경계 ①②③, 계약 A/B/C, 감사 표본을 알아낼 수 있는지)은 정적 검사로 못 가리므로 `constitution-auditor` 의 몫이다. **오탐이 잦은 규칙을 훅에 넣지 않는 것이 원칙** — 정상 작업을 막는 훅은 곧 꺼지고, 꺼진 훅은 통과가 보증처럼 보여서 없는 것만 못하다
- 한계: `verify-before-push.sh` 는 로컬 Docker 환경에 의존한다 (D-026 — Docker Desktop 4.44.2 이하). 조건이 깨지면 코드와 무관하게 push 가 막힌다

### 3. 테스트

- 산출물:
  - `tests/e2e/api.spec.ts` — 실행 1건(`/actuator/health`) + `test.skip` 스켈레톤 1건
  - `.github/workflows/e2e.yml` — PR + `develop` push 에서 `docker compose up --wait` 로 실제 스택 기동 후 Playwright 실행
  - `src/test/java/` — `BaselineSmokeTest`(Testcontainers, 8건), `DomainFactoryTest`(순수 단위, 8건). `CandidateIndexMigrationTest` 는 **V2 후보 인덱스와 함께 삭제**됐다 (D-031)
- 도구: Playwright MCP (`request` fixture 기반 API 레벨). 브라우저를 띄우지 않으므로 CI 에서 chromium 설치를 제거했다 — 검토자 화면이 생겨 `page` fixture 를 쓰게 되면 되살린다
- **e2e.yml 이 실제로 검증하는 것**: "앱이 기동한다" 한 줄에 세 가지가 함께 들어 있다 — Flyway `V1` → `V2__domain_switch.sql` 이 깨끗이 적용됨 / `ddl-auto=validate` 아래에서 엔티티 3종이 **전환 후 스키마**와 일치함(어긋나면 부팅 실패) / MySQL·Redis 연결이 실제로 성립함. **이 workflow 가 red 면 P1·P2·P3 전부의 착수 전제가 깨진 것**이다. 도메인 전환 후 이 검증이 특히 중요하다 — 스키마와 엔티티를 **동시에** 갈아엎었기 때문이다
- 한계:
  - **시나리오가 health 1건뿐이다.** 핵심 흐름(투입 → 격리 → 확정)과 차별 흐름(자동 확정 → 감사 표본 → 정정)은 `service/`·`api/` 착수 후에야 쓸 수 있다. 잔여 3건 중 1건
  - 로컬 Testcontainers 는 Docker Desktop 버전에 의존한다 (D-026). CI 는 `docker compose` 를 직접 쓰므로 이 제약의 영향을 받지 않는다 — **로컬만 깨지고 CI 는 green 인 상태가 가능**하다는 뜻이라, 로컬 실패를 CI 로 덮지 않는다

### 4. 리뷰

- 산출물: `.github/workflows/ai-review.yml`, `evidence/failure-cases.md` (AI hallucination·오류 **16건** 기록), `.coderabbit.yaml`
- 도구: Claude GitHub Actions Review — 팀 CLAUDE.md 핵심 룰 prompt 전달 + **CodeRabbit** — 라인별 인라인 코멘트 · 정적분석 · 커밋 단위 증분 리뷰 (D-051, GitHub App 설치 완료로 현재 병행 동작 중)
- 한계: PR #1 부터 전원 자동 리뷰가 동작했다. 다만 검출은 **문서·설계 층에 집중**돼 있고, `service/`·`api/` 미착수라 **런타임 결함에 대한 검출력은 아직 미검증**이다 (`evidence/failure-cases.md` 「미검출 위험이 남은 영역」 참조). CodeRabbit 도입 초기라 `ai-review.yml` 과의 중복·고유 지적 비율은 아직 관측되지 않았다 — **`service/`·`api/` PR 이 열리면 두 도구의 지적을 각각 집계해 「중복 N건 / CodeRabbit 고유 N건 / ai-review 고유 N건」 형태로 `evidence/failure-cases.md` 에 기록한다.** D-051 의 핵심 판단 근거("겹치는 영역보다 못 잡는 영역이 더 크다")는 이 집계 전까지는 검증되지 않은 가설이다

### 5. 배포·운영

- 산출물:
  - `docker-compose.yml` + `Dockerfile` — mysql / redis / app 3서비스. **셋 다 healthcheck 필수**다. app 의 healthcheck 를 빼면 `--wait` 가 "컨테이너가 떴다"까지만 보고 통과해, 부팅 미완 상태에서 Playwright 가 붙어 간헐 실패가 된다
  - `.env.example` — `ANTHROPIC_API_KEY` 포함 전 환경변수 목록. 실제 `.env` 는 `.gitignore` + `constitution-guard.py` 로 이중 차단
  - `MONITORING.md` + `SENTRY-GUIDE.md` — PR #9 로 **실측 기록까지 채워졌다.** 도구 선택 표 · SDK 실제 적용값 · MCP 등록 · 운영 시나리오 실측이 모두 들어 있다
- 도구: Docker Compose (동작), Sentry SDK + Source Context + MCP 연동 (실측 검증 완료 — `SENTRY-GUIDE.md`, PR #9)
- 관측 설계는 되어 있다 — `GET /api/stats` 블록 + Actuator gauge 5종(`triage.inquiries.stuck_received` 포함). **노출할 코드가 없을 뿐 무엇을 볼지는 정해져 있다** (`API-CONTRACT.md` §8~§9)
- 한계:
  - **실 트래픽 0.** 속도 측정(a·b)과 `stuckReceived` 실측은 `service/`·`api/` 착수 후
  - Sentry MCP 연동 자체는 PR #9 로 완료됐다(`SENTRY-GUIDE.md` 실측 검증). 다만 이 시스템은 자체 에러 수집 파이프라인이라 Sentry 와 역할이 겹친다 — "무엇을 Sentry 로 보내고 무엇을 자체 큐로 보낼지"는 아직 정하지 않아 실 트래픽에서의 캡처 시나리오는 남아 있다

## 진행 상태

> ⚠️ **"Phase" 가 두 뜻으로 쓰이고 있어 구분한다.** `PRD.md` §8 의 「5일 동안 만들 것」 / 「나중에 할 것」은 **기능 범위**이고, 아래는 **주차 진행 단계**다. 같은 단어를 두 축에 쓰면 "이월"이 일정 지연인지 범위 결정인지 섞인다.

| 주차 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 기획 골격 — README, PRD, DECISIONS 초안 | ✅ |
| 2 | 코드 baseline + 5 단계 도구 설정 | 🔄 진행 중 — **2026-08-05 도메인 전환으로 baseline 재작성** (D-031) |
| 3 | 3명 병렬 PR 시연 — P1/P2/P3 feature 브랜치별 PR + `service/`·`api/` + 측정 12개 | ⏳ |
| 4 | Week 10 마무리 — 발표 자료, 회고, 면접 답변 | ⏳ |

**나중에 할 항목**은 `PRD.md` §8 표 (A~G) 참조. 주차 단계 3 과는 다른 축이다.

## 미션 통과 검증 체크리스트

| 단계 | 산출물 존재 | AI 도구 설정 | 통과 |
| --- | --- | --- | --- |
| 기획 | ✅ PRD.md, DECISIONS.md (D-001~D-051) | ⏳ Jira MCP dry-run 미시연 | 🔄 |
| 코딩 | ✅ src/ (엔티티 3종·enum·repository·Flyway V1/V2·정적 팩토리), CLAUDE.md, API-CONTRACT.md **v1.3** (PRD 와 어긋난 5건은 `api/` PR 에서 정정 — `INTEGRATION-LOG.md`) | 🔄 claude.md ✅ / Hooks ✅ / 서브에이전트 5종 🔄(PR #8 리뷰 중) / **Commands 미작성** | 🔄 |
| 테스트 | ✅ tests/e2e/, e2e.yml, Testcontainers 8건 + 단위 8건 | ⏳ Playwright MCP 시나리오는 health 1건뿐 | 🔄 |
| 리뷰 | ✅ ai-review.yml, evidence/failure-cases.md (16건), .coderabbit.yaml | ✅ PR #1~#11 전원 자동 리뷰 동작 + **CodeRabbit GitHub App 설치 완료, 실제 코멘트 동작 확인됨**(Walkthrough·리뷰 코멘트) (D-051) | ✅ |
| 운영 | ✅ docker-compose.yml, Dockerfile, .env.example, MONITORING.md, SENTRY-GUIDE.md | 🔄 Sentry SDK + Source Context + MCP 연동, 실측 검증 완료(`SENTRY-GUIDE.md`, PR #9) — 실 트래픽 0 | 🔄 |

> **잔여 3건이 실제로 남은 작업이다** — ⓐ Jira MCP dry-run ⓑ `.claude/commands/` ⓒ e2e 시나리오 확장.
> PR #9 로 Sentry MCP 연동과 `MONITORING.md` 작성이 함께 끝나 **5건 → 3건**으로 줄었다. 이 문서가 한동안 `MONITORING.md` 를 "템플릿 상태"로 적고 있었는데 실제로는 채워진 뒤였다 — **문서가 자기 잔여 목록을 과다 계상하고 있었던 셈**이라 정정한다.
> 이 표를 ⏳ 로 방치하면 "무엇이 남았는지"가 아니라 "아무것도 안 됐다"로 읽혀서 실제 잔여가 가려진다. 그래서 **산출물 존재와 도구 설정을 두 열로 나눠** 둔다.
> ⓑ 는 지금 바로 할 수 있고, ⓐ 는 외부 서비스 연동이라 시연 시나리오를 먼저 정해야 한다. **ⓒ 는 `service/`·`api/` 착수에 종속**되므로 사실상 P1/P2/P3 진행에 묶여 있다.
