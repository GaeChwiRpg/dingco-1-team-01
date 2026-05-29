> 이 레포는 **1기** 의 **1기-team-01** 팀 프로젝트 레포입니다.
> 멤버: @agbink, @whitejh, @techietaek
> 운영용 SoT: https://github.com/GaeChwiRpg/devcamp-team-submission-sample — 빈 템플릿 12개 채우면서 진행하세요.

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
