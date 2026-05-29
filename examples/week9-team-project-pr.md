# Week 9 Team Project — PR 본문 sample

> 팀 레포 [`.github/PULL_REQUEST_TEMPLATE.md`](../.github/PULL_REQUEST_TEMPLATE.md) 형식 그대로, 채워진 sample.
> 학생이 본인 팀 PR 작성 시 이 sample 의 톤 / 깊이를 참고.

---

## 담당 기능

- 재고 조회/차감 API 계약을 팀 기준으로 정리.
- 담당 영역 코드 1건 + Playwright E2E 시나리오 1건을 같이 추가.
- `@Lock(PESSIMISTIC_WRITE)` 적용 + 동시 50 요청 시나리오 검증.

## 라이프사이클 단계

- [x] 코딩
- [x] 테스트
- [ ] 기획
- [ ] 리뷰
- [ ] 배포·운영

## 팀 내 역할

- 내 역할: 코드 오너 — 재고 동시성 + Playwright E2E
- 다른 단계 책임자와 연동: 코딩 단계는 팀 전원 공동, 테스트 단계는 본인 책임 — `week9-team-role-split.md` 참고

## 검증

- Contract test: `src/test/java/.../InventoryContractTest.java` 4건 통과
- E2E test: `tests/e2e/inventory-decrease.spec.ts` Playwright 50 동시 요청 결과 (음수 재고 0건)
- 담당 기능 데모 로그: `INTEGRATION-LOG.md` Day 3 항목

## API 변경

- [ ] API 변경 없음
- [x] API 변경 — `API-CONTRACT.md` Inventory v0.2 → v0.3 (POST /api/inventory/{id}/decrease 추가)

## AI 보조 흔적

- [x] AI 보조 사용
  - Playwright MCP — 시나리오 1.5h → 30m
  - 첫 시나리오에서 셀렉터 hallucination 1건 잡음 → `evidence/failure-cases.md` 추가 + prompt 에 "셀렉터 추측 금지" 제약 추가

## 의존성 / 후속 PR

- 의존: PR #N (티켓 도메인 baseline) — 머지 후 작업
- 후속: PR #M+1 (운영 단계 모니터링) — 별도 PR 로 분리 예정
- 남은 리스크:
  - 락 전략 비교 (현재 pessimistic 만, optimistic 부하 테스트 후속)
  - 캐시 invalidation / 타임아웃 처리

## 체크리스트

- [x] 라이프사이클 단계 1개 이상 체크
- [x] 검증 근거 1개 이상 명시
- [x] API 변경 → API-CONTRACT.md 갱신
- [x] AI 보조 사용 → hallucination 1건 잡힌 사례 evidence 에 누적

---

## 참고

- 역할 분담: [`week9-team-role-split.md`](./week9-team-role-split.md)
- 5 단계 적용 흔적: [`week9-team-lifecycle-coverage.md`](./week9-team-lifecycle-coverage.md)
- 일자별 통합 로그: [`week9-team-integration-log.md`](./week9-team-integration-log.md)
