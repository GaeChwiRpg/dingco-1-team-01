# Week 9 Team Project — Evidence Checklist (PR 단위)

> Week 9 는 개인 레포 미션 제출이 아니라 **팀 레포 PR 기준**입니다.
> PR 1개당 본문 + evidence 에 남길 자산을 정리. 팀 단위 (5 단계 산출물) 는 root [`LIFECYCLE-COVERAGE.md`](../LIFECYCLE-COVERAGE.md) 가 매트릭스로 추적합니다.

## 개인 PR 마다 (최소)

PR 본문에 모두 명시 (`team-pr-guard.yml` 이 검증):

- [ ] 담당 기능 범위 (2~3 줄)
- [ ] **본 PR 이 다룬 라이프사이클 단계** (코딩 / 테스트 / 리뷰 / 배포·운영 중 1개 이상)
- [ ] 검증 근거 1개 이상 (contract test, E2E, 측정 결과 중)
- [ ] 팀 내 역할 + 연동 포인트
- [ ] AI 보조 사용 여부 + (사용 시) hallucination 잡힌 사례

## 권장 증빙 (PR 본문 또는 `evidence/` 폴더)

- contract test 결과 또는 단위 테스트 통과 로그
- API 요청/응답 예시 (jq 형식)
- E2E 시나리오 결과 (Playwright/Browser MCP)
- 통합 체크리스트 (의존 PR / 후속 PR)
- 장애 / 병목 메모
- AI hallucination 사례 + 잡은 방법 (`evidence/failure-cases.md` 누적)

## 팀 단위 산출물 (5 단계)

> 이 섹션은 별도 추적 — root [`LIFECYCLE-COVERAGE.md`](../LIFECYCLE-COVERAGE.md) 의 매트릭스를 채워가세요.
> PR 본문에서는 본 PR 이 어느 단계에 기여했는지만 체크박스로 명시하면 충분합니다.

## AI 보조 기능 (공통 필수)

- AI 보조 기능 1개 이상 구현 (요약/분류/우선순위 추천 등)
- AI 결과 검증 메모 (정확도 측정 방법, 사람 최종 판단 기준)
- hallucination 사례 1건 + 잡은 방법 → `evidence/failure-cases.md`

## 예시 문장

PR 본문 1~2줄을 어떻게 적을지 막막할 때:

- "이번 PR 은 재고 조회/차감 API 를 담당했습니다 (코딩 + 테스트 단계)."
- "주문 서비스와의 연동 계약은 `inventory-reserve`, `inventory-release` 두 개입니다."
- "검증은 contract test 4건 + Playwright E2E 1건 + 동시 요청 50건 시나리오로 확인했습니다."
- "리뷰 단계는 `.github/workflows/ai-review.yml` 자동 트리거, 운영 단계는 별도 PR 로 분리 예정."
- "남은 리스크는 캐시 invalidation 과 타임아웃 처리입니다."
