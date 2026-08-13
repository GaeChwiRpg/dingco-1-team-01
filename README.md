# AI 문의 분류 검증 파이프라인

> 고객 문의를 AI 가 자동 분류하되, **AI 가 스스로 확신한다고 말한 결과도 다시 의심하는** 서버입니다.
> 
> 이 레포는 **1기** 의 **1기-team-01** 팀 프로젝트 레포입니다.
> 1기 team-01 · @agbink · @whitejh · @techietaek

**한 줄로** — 문의를 AI 가 분류하고 ① 확신 못 한 건은 사람에게 넘기고 ② **확신한 건도 20건에 1건을 몰래 뽑아 사람이 다시 봅니다.** 그리고 **AI 답과 사람 답이 다른 비율을 숫자로 냅니다.**

## 무엇을 알아냈나

정답을 미리 붙여둔 문의 **50건**을 실제로 넣어 확인했습니다.

```text
문의 50건
   32건  AI 가 "확신한다"며 사람을 안 거치고 확정   ← 아무도 다시 안 본다
   15건  AI 가 자신 없다고 해서 사람에게
    3건  AI 가 답을 못 줌 (3번 재시도 후 사람에게)

그 32건 중 3건이 오답.  그중 하나는 AI 가 "95% 확신한다"고 말한 건이었습니다.
```

| 무엇 | 값 | 어디 |
| --- | --- | --- |
| **AI 가 확신하면서 틀린 비율** | **0.094** (32건 중 3건) | [`evidence/measurement-1-8a.md`](./evidence/measurement-1-8a.md) |
| 20건에 1건 감사가 잡아낸 몫 | **0.333** — 3건 중 1건. **2건은 놓쳤다** | 〃 |
| 지난 답을 다시 쓴 건의 오답률 | **0.062** — 재사용이 오류를 **증폭하지 않았다** | [`measurement-8b.md`](./evidence/measurement-8b.md) |
| AI 호출을 얼마나 아꼈나 | **0.490** (1000건 기준) · 캐시 적중 0.454 | [`measurement-6-11-actual.md`](./evidence/measurement-6-11-actual.md) |
| 접수 응답 속도 (목표 0.1초) | **6.4~8.8초 — 목표를 못 넘었다** | [`api-response-time.md`](./evidence/api-response-time.md) |

> **못 넘은 목표도 그대로 적습니다.** 접수가 느린 것은 **대기열이 찼을 때 문의를 버리지 않고
> 접수한 요청이 대신 분류를 떠안게 한 대가**입니다(D-045 ⑤) — *"느려지는 건 보이지만 사라지는
> 건 안 보인다"* 를 택한 결과이고, `PRD.md` §9 가 미리 예견한 트레이드오프입니다.

## 어떻게 도나

```text
① 접수                                      ② 분류 (다른 스레드)
고객 ─POST /api/inquiries─> 문의 저장 ─신호─> ├ 1단 캐시 조회 (정규화 키)
                              │              ├ 2단 DB 조회   ┐ 있으면 AI 안 부름
                        202 즉시 응답        └ 없으면 AI 호출 ┘
                     (AI 를 기다리지 않음)          │
                                                    ↓
                                        확신도 >= 0.8 ?
                                    ┌───────┴────────┐
                                   예               아니오
                                    │                │
                          자동 확정 (아무도 안 봄)   검토 목록으로
                                    │                     │
                          20건에 1건 몰래 뽑기 ──────────>─┤
                                                          ↓
                                              ③ 상담원이 확정 (PATCH)
                                                          │
                                          사람 답으로 캐시 덮어쓰기 ─┐
                                                                     ↓
                                          같은 문의가 또 오면 올바른 답
```

**검증이 두 겹입니다.** 1차(확신도)만으로는 **AI 가 자신 있게 틀린 건을 절대 못 잡습니다** — 위 3건이 그 증거입니다.

## 자동 확정 기준값을 `0.8` 로 둔 근거

**정답이 있어서 고른 값이 아니라, 고른 뒤 재보고 고칠 초기값입니다.**

| | 내용 |
| --- | --- |
| **왜 실행 중에 못 바꾸나** | 도중에 바뀌면 *"이 결과가 어느 기준에서 나온 것인지"* 를 사후에 구분할 수 없습니다. 그래서 기동 시 고정입니다 (D-028) |
| **바꾸면 어떻게 되나 (계산값)** | `0.5` → 자동 확정 47건 · 사람이 볼 건 **0** / `0.8` → 32건 · 15건 / `0.9` → 16건 · 31건 |
| **왜 세 값으로 각각 안 쟀나** | 카테고리별 차등(D-006)이 폐기되며 대조군 측정도 함께 사라졌습니다. **한 번 돌린 결과에서 구간별 집계로 역산**했고, 계산값임을 evidence 에 밝혔습니다 |
| **다시 볼 조건** | 자동 확정 구간에서 오분류가 **0 으로 나오면** 감사가 잡을 것이 없어 결론을 못 읽습니다 → 그때 기준값을 낮춰 다시 잽니다 |

**AI 가 확신도 `0.5` 미만을 한 번도 안 냈습니다.** 그래서 `0.5` 로 낮추면 **사람이 볼 건이 0** 이 되어 1차 겹이 통째로 사라집니다.

## 돌려보기

```bash
cp .env.example .env      # ANTHROPIC_API_KEY 를 채웁니다 (없어도 뜹니다)
docker compose up -d      # mysql · redis · app

curl -X POST http://localhost:8080/api/inquiries \
  -H "Content-Type: application/json" -H "X-User-Id: 5001" -H "X-User-Role: CUSTOMER" \
  -d '{"content":"3일째 배송중이라고만 뜨는데 언제 오나요?","channel":"WEB"}'

curl -H "X-User-Id: 7001" -H "X-User-Role: AGENT" \
  "http://localhost:8080/api/inquiry-review-queue?status=PENDING" | jq
```

⚠️ **Docker Desktop 버전 제약이 있습니다** — [`ONBOARDING.md`](./ONBOARDING.md) 를 먼저 보세요.

## 문서 지도

| 궁금한 것 | 어디 |
| --- | --- |
| **처음 합류했다** | [`ONBOARDING.md`](./ONBOARDING.md) — 환경 셋업부터 PR 머지까지 |
| 모르는 단어가 나왔다 | [`GLOSSARY.md`](./GLOSSARY.md) — 「0. 5분 요약」부터 |
| 무엇을 만드는지 (자세히) | [`PRD.md`](./PRD.md) |
| **왜 그렇게 정했는지** | [`DECISIONS.md`](./DECISIONS.md) — D-001~. **기존 항목을 고치지 않고 후속으로 덮습니다** |
| 코딩 규칙 · 작업 경계 | [`CLAUDE.md`](./CLAUDE.md) — 팀 헌법 |
| API 규격 | [`API-CONTRACT.md`](./API-CONTRACT.md) |
| 테이블·엔티티 구조 | [`DOMAIN-MODEL.md`](./DOMAIN-MODEL.md) |
| **잰 숫자와 그 한계** | [`evidence/`](./evidence/) |
| 밖에 보여줄 한 장 요약 | [`PROJECT-BRIEF.md`](./PROJECT-BRIEF.md) |

## 이 프로젝트가 지키는 것

- **재보지 않은 수치를 만들지 않습니다.** 모르는 값은 *"재보기 전에는 모른다"* 로 둡니다
- **AI 가 틀린 사례를 지우지 않고 모읍니다** — [`evidence/failure-cases.md`](./evidence/failure-cases.md) (26건)
- **폐기된 결정도 안 지웁니다.** 왜 틀렸었는지가 남아야 같은 실수를 안 합니다
- **못 넘은 목표를 못 넘었다고 적습니다** — 위 속도 측정이 그 예입니다

---

<details>
<summary><b>부트캠프 제출 템플릿 안내 (원본 그대로 보존)</b></summary>

> 운영용 SoT: https://github.com/GaeChwiRpg/devcamp-team-submission-sample

# Dev Camp Team Submission Sample

> 딩코딩코 부트캠프 Week 9~10 팀 프로젝트 제출 형식 sample · 라이프사이클 5 단계 팀 분담 안내
> 이 레포는 코호트 시작 시 자동으로 팀 레포로 복제됩니다 (`bootcamp-admin` 이 Git clone). 팀이 아래 템플릿을 채워가는 흐름.

## 이 레포에서 시작하는 법

레포 루트에 비어 있는 템플릿이 깔려 있습니다. 팀이 채워야 할 순서:

1. **`PRD.md`** — 기획 단계 책임자가 페르소나 / User Story / 6 공통 필수 기능 매핑
2. **`DECISIONS.md`** — 첫 의사결정 D-001 부터 누적
3. **`CLAUDE.md`** — 팀 헌법 (도메인 + 코딩 규칙 + 작업 경계). 모든 prompt 에 자동 포함
4. **`API-CONTRACT.md`** — API endpoint + 변경 이력
5. **`LIFECYCLE-COVERAGE.md`** — 5 단계 누가 무엇을 했는지 매트릭스
6. **`INTEGRATION-LOG.md`** — Day 1~5 일자별 진행 + 회고 통계
7. **`MONITORING.md`** — 운영 단계 책임자가 도구 선택 + 시나리오
8. **`RETROSPECTIVE.md`** — Phase 2 종료 시 KPT + Phase 3 우선순위
9. **`presentation/SLIDE-OUTLINE.md`** + **`presentation/DEMO-SCRIPT.md`** — Week 10 발표 골격
10. **`INTERVIEW-ANSWERS.md`** — Week 10 면접 답변 5장 흐름 6개

PR 본문 표준은 [`.github/PULL_REQUEST_TEMPLATE.md`](./.github/PULL_REQUEST_TEMPLATE.md). PR 마다 라이프사이클 단계 + 검증 + AI 보조 흔적을 명시해야 [`team-pr-guard`](./.github/workflows/team-pr-guard.yml) CI 통과.

채워진 정답 sample 은 [`GaeChwiRpg/devcamp-team-project-showcase`](https://github.com/GaeChwiRpg/devcamp-team-project-showcase) 참고 — 단, **본인 팀 진행 중에는 정답을 보지 않는 것이 학습 효과 큽니다.**



## Week 9의 위치

| | Week 8 | **Week 9 (이 레포)** | Week 10 |
| --- | --- | --- | --- |
| 단위 | 개인 | **팀** | 팀 + 개인 |
| 목표 | 코딩 단계 6 요소 + 라이프사이클 **2 단계** 손에 익히기 | **5 단계 모두 팀이 분담**해서 적용 | 통합 + 발표 + 면접 전환 |
| 산출물 | 본인 미션 1개 재구현 + 6 요소 evidence | 팀 프로젝트 코드 + 라이프사이클 분담 evidence + 개인 PR 2개+ | 최종 발표 + 이력서 |

Week 8에서 개인이 6 요소(claude.md / Commands / Hooks / 페목형제 / Context / Needle)와 라이프사이클 2 단계를 손에 익혔다면, Week 9는 팀이 **라이프사이클 5 단계 모두 분담**해서 실제 서비스 한 사이클을 굴려보는 미션입니다.

## 미션의 3 축

### 축 1. 추천 프로젝트 5개 중 1택 (또는 자율 주제)

같은 난이도 조건을 만족하면 자율 주제도 가능:

- 운영 티켓 / 작업 관리 시스템
- 커머스 주문 후처리 / 클레임 운영 시스템
- 채용 / 과제 / 면접 파이프라인 운영 시스템
- 협업형 콘텐츠 검수 / 승인 시스템
- 의료 / 상담 / 예약 운영 보조 시스템

### 축 2. 공통 필수 기능 6개 (모두 포함)

도메인 무관 반드시:

- 권한 또는 역할 구분 1개 이상
- 핵심 트랜잭션 1개 이상
- 검색/필터 리스트 1개 이상
- 캐시 적용 포인트 1개 이상
- 비동기 또는 이벤트 흐름 1개 이상
- AI 보조 기능 1개 이상 (요약/분류/우선순위 추천/답변 초안 등)

### 축 3. 라이프사이클 5 단계 팀 분담 (모든 단계 적용)

책 `books/05-week8-10x-ai-native-developer.md` 9·10주차 도구를 팀이 분담해서 운영:

| 단계 | 책 챕터 | 권장 도구 | 팀 산출물 |
| --- | --- | --- | --- |
| **1. 기획** | 9주차 03 | Jira MCP, AI PRD 자동 생성 | `PRD.md`, 티켓 분해 |
| **2. 코딩** | 7주차 04~07, 9주차 02·04·05 | claude.md 헌법, Commands, Hooks, gh CLI, Sub-agents, claude-squad | `CLAUDE.md`, `API-CONTRACT.md` |
| **3. 테스트** | 9주차 06 | Playwright MCP, Browser MCP | `E2E-TESTS.md` + 시나리오 |
| **4. 리뷰** | 10주차 01 | Claude GitHub Actions Review, CodeRabbit | `.github/workflows/ai-review.yml` + 리뷰 메모 |
| **5. 배포·운영** | 10주차 06, 02~05 | Sentry MCP, Docker, Terraform | `MONITORING.md`, IaC 또는 Docker Compose |

5 단계 모두 적용 흔적이 evidence에 있어야 통과. 단 도구 깊이는 팀 사정에 맞게 조정 (예: 배포 단계는 Docker Compose로만 끝내도 OK).

## 산출물

### 팀 공통

- `PRD.md` — 제품 요구사항 (기획 단계)
- `CLAUDE.md` — 팀 헌법 (도메인 + 코딩 규칙 + 작업 경계)
- `API-CONTRACT.md` — API 계약 + 변경 이력 (코딩 단계)
- `E2E-TESTS.md` 또는 `tests/e2e/` — Playwright 시나리오 (테스트 단계)
- `.github/workflows/ai-review.yml` — Claude Actions 또는 CodeRabbit 설정 (리뷰 단계)
- `MONITORING.md` — Sentry 또는 운영 도구 설정 (운영 단계)
- `INTEGRATION-LOG.md` — 팀 통합 진행 로그
- `LIFECYCLE-COVERAGE.md` — 5 단계 누가 무엇을 적용했는지

### 개인

- 핵심 기능 PR 1개 이상
- 통합/리뷰 반영 PR 1개 이상
- 본인이 담당한 라이프사이클 단계의 evidence 기여

## 역할 분담

기술 영역(인증·조회·동시성·캐시 등)과 라이프사이클 단계(기획·코딩·테스트·리뷰·운영)를 **2축으로 결합**합니다. 자세한 매트릭스는 [examples/week9-team-role-split.md](./examples/week9-team-role-split.md).

## 핵심 원칙

- Week 9는 개인 레포 미션 디렉토리 제출이 아닙니다 (팀 레포 PR 기준 집계).
- PR 본문에는 **담당 기능 + 검증 범위 + 라이프사이클 단계 + 팀 내 역할**이 모두 드러나야 합니다.
- 코드보다 "**내가 어디를 어떤 도구로 어떻게 검증했는지**"가 설명 가능해야 합니다.
- AI를 써서 구현 속도는 올리되 품질 통제는 사람이 유지합니다 (Week 8 검증 루프 4단계 그대로).

## 포함 examples

- [week9-team-project-pr.md](./examples/week9-team-project-pr.md) — PR 본문 sample (라이프사이클 단계 표시)
- [week9-team-project-evidence-checklist.md](./examples/week9-team-project-evidence-checklist.md) — 팀 산출물 체크리스트
- [week9-team-role-split.md](./examples/week9-team-role-split.md) — 라이프사이클 × 기술 영역 매트릭스
- [week9-team-lifecycle-coverage.md](./examples/week9-team-lifecycle-coverage.md) — 5 단계 적용 흔적 sample
- [week9-team-integration-log.md](./examples/week9-team-integration-log.md) — 팀 통합 진행 로그 sample

## 평가 기준 요약

- 추천 프로젝트 또는 자율 주제 1택 + 공통 필수 기능 6개 모두 포함
- 라이프사이클 **5 단계 모두**에 도구 적용 흔적 (단계별 산출물 ≥ 1)
- 개인 PR 2개 이상 + PR마다 라이프사이클 단계 명시
- API 계약 변경 이력 추적 가능
- AI 보조 기능 검증 메모 포함
- 팀 통합 로그에 단계별 담당자 + 진행 상태

</details>
