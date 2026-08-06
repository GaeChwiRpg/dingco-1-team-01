<!--
Week 9 팀 프로젝트 PR 본문 표준 (SoT).

학생 개인 레포의 PR 형식과 다릅니다 (개인 = 미션 폴더 1개 + report.md / 팀 = 라이프사이클 단계 + 책임자 + AI 보조 흔적).

자동 추론 규칙: team-pr-guard.yml 이 공통 항목을 검사하고, `대표 PAAR PR` 선택 시 아래 증거 항목을 추가 검사합니다.
-->

## PR 유형

- [ ] 일반 PR
- [ ] 대표 PAAR PR (`PAAR-CARDS.md` 의 본인 카드와 연결)

## 담당 기능

- 이 PR 의 범위를 2~3 줄로.
- (예: 티켓 도메인 모델 + 락 + 캐시 + 이벤트 통합)

## 라이프사이클 단계

> 본 PR 이 다룬 단계 (코딩 / 테스트 / 리뷰 / 운영 중 어디. 1개 이상 명시 필수).

- [ ] 기획
- [ ] 코딩
- [ ] 테스트
- [ ] 리뷰
- [ ] 배포·운영

## 팀 내 역할

- (예: 코드 오너 — Chris / 리뷰 오너 — Bora / 문서 오너 — Alex)

## 검증

> contract test / 단위 테스트 / E2E / 측정 결과 중 1개 이상.

- (예: contract test 4건 통과 — `tests/...`)
- (예: Playwright E2E 1 시나리오 — `tests/e2e/...`)

## 대표 PAAR

> `대표 PAAR PR` 일 때만 채우세요. Result는 같은 조건의 전후 변화 또는 불변식이어야 합니다.

- PAAR card:
- Problem:
- Analyze option 1:
- Analyze option 2:
- Decision criteria:
- Action owner:
- Action PR link:
- Action evidence link:
- Baseline evidence:
- Result (same condition/invariant):
- Limitation:
- Next action:

## API 변경

- [ ] API 변경 없음
- [ ] API 변경 — `API-CONTRACT.md` 동반 갱신함 (예: v0.1 → v0.2)

## AI 보조 흔적

- [ ] 본 PR 에서 AI 보조 사용 안함
- [ ] AI 보조 사용 — 어떤 작업에 어떻게:
  - (예: PRD 분해 — Jira MCP)
  - (예: 시나리오 작성 — Playwright MCP, hallucination 1건 잡고 수정)

## 의존성 / 후속 PR

- 의존: PR #N (먼저 머지되어야 함)
- 후속: PR #M (이 PR 머지 후 작업)

## 체크리스트

- [ ] `일반 PR` 또는 `대표 PAAR PR` 중 1개만 체크
- [ ] 라이프사이클 단계 1개 이상 체크
- [ ] 검증 근거 1개 이상 명시
- [ ] 대표 PAAR PR 이라면 모든 PAAR 증거 항목 완료
- [ ] 대표 PAAR Result를 CI/PR 수/테스트 개수 같은 활동량만으로 쓰지 않음
- [ ] API 변경 시 `API-CONTRACT.md` 갱신
- [ ] AI 보조 사용 시 hallucination 잡힌 사례가 있다면 `evidence/failure-cases.md` 에 추가
