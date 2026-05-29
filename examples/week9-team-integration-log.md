# Week 9 Team Integration Log — 팀 통합 진행 sample

> 팀 레포 루트의 `INTEGRATION-LOG.md`로 보관하는 sample. 일자별 + 담당자별로 누가 무엇을 했는지 추적.

## 팀 정보

- 팀: cohort1-team-A
- 기간: 2026-07-25 (월) ~ 2026-07-31 (금)
- 4명 팀 (A·B·C·D)

## Day 1 (월) — 기획 + 환경 세팅

### 팀 공통

- 추천 프로젝트 1번(운영 티켓 시스템) 선정
- 4명이 라이프사이클 단계 + 기술 영역 분담 매트릭스 합의 (`week9-team-role-split.md` 기준)
- 팀 레포 생성 + 초기 baseline (Spring Boot 3.3.5, Java 21)
- 팀 `CLAUDE.md` 초안 작성

### 개인

- 팀원 A: PRD.md v0.1 초안 + Jira MCP 연결
- 팀원 B: 검색 도메인 스키마 설계
- 팀원 C: 동시성 baseline 코드 (Week 5 미션에서 가져옴)
- 팀원 D: Sentry 프로젝트 생성 + SDK 통합 시도

### 진행 메모

- Jira MCP 연결에서 토큰 권한 이슈 → 팀원 A가 30분 디버깅 후 해결
- Sentry SDK 통합 시 application.yml 수정 PR 1건 (팀원 D)
- 팀원 C의 Week 5 락 코드를 팀 도메인(티켓)에 맞게 변환 시작

## Day 2 (화) — 코딩 본격

### 개인

- 팀원 A: 인증/권한 PR #2 (JWT + Spring Security)
- 팀원 B: 티켓 검색 PR #3 (인덱스 + 페이징)
- 팀원 C: 티켓 상태 전이 PR #4 (트랜잭션 + 락)
- 팀원 D: Redis 캐시 PR #5 (인기 검색어 캐시)

### 진행 메모

- 팀원 B의 PR #3에서 검색 쿼리 N+1 발견 → 팀원 B가 fetch join으로 해결 (Week 2 학습 그대로)
- 팀원 D의 PR #5에서 캐시 키 충돌 가능성 → 팀원 B와 합의 후 prefix 결정

## Day 3 (수) — API 계약 정합 + E2E 시작

### 팀 공통

- `API-CONTRACT.md` v0.5 작성 (8 endpoint 정합)
- 팀원 B 주도 Playwright MCP 셋업 (책 9주차 06 따라)

### 개인

- 팀원 A: PR #6 인증 예외 처리 + GlobalExceptionHandler
- 팀원 B: tests/e2e/login-flow.spec.ts 작성 시작
- 팀원 C: PR #7 락 전략 비교 (pessimistic vs optimistic) Sub-agents로 동시 평가
- 팀원 D: MONITORING.md v0.2 + Sentry 첫 시연용 에러 트리거

### 진행 메모

- 팀원 C의 Sub-agents 활용 사례를 팀 회고에서 공유 — pessimistic 채택 + optimistic은 후순위
- API 계약 변경 1건 (팀원 B 검색 endpoint 응답 DTO 필드 추가 → 팀원 D 캐시 측 응답 검증 영향)

## Day 4 (목) — 리뷰 도입 + 통합 테스트

### 팀 공통

- `.github/workflows/ai-review.yml` 활성화 (팀원 C 주도)
- 첫 자동 리뷰가 팀원 A의 PR #2에 트랜잭션 경계 누락 지적 → 팀원 A 즉시 수정
- 4 endpoint E2E 시나리오 통합 검증

### 개인

- 팀원 A: PR #8 리뷰 반영 (트랜잭션 경계 추가)
- 팀원 B: tests/e2e/ticket-create.spec.ts + ticket-assign.spec.ts
- 팀원 C: PR #9 락 락 보유 시간 줄이기 (트랜잭션 안에서 외부 호출 제거)
- 팀원 D: MONITORING.md v1.0 + Sentry 에러 분석 결과

### 진행 메모

- AI 리뷰가 second-pass 사람 리뷰보다 먼저 일관된 첫 패스 제공 → 팀원 리뷰 시간이 약 30% 줄어듦
- AI hallucination 1건: AI 리뷰가 존재하지 않는 메서드(`getTotalAmount()`) 호출을 지적함 → 팀원 C가 reject + 메모

## Day 5 (금) — 통합 + 회고

### 팀 공통

- 5 단계 통과 검증 (`LIFECYCLE-COVERAGE.md` 작성)
- 4 endpoint 모두 동작 + AI 보조 기능 1개 (티켓 우선순위 추천) 동작 확인
- 팀 회고 + Week 10 통합/발표 준비 계획

### 개인

- 팀원 A: PR #10 PRD vs 구현 일치 검증 메모
- 팀원 B: PR #11 인덱스 추가 + 응답 시간 측정 결과
- 팀원 C: PR #12 락 + 동시성 evidence 정리
- 팀원 D: PR #13 운영 가이드 + 부하 테스트 보고

## 통계

- 총 PR 수: 13
- 개인 PR 수: 팀원 A 4 / B 4 / C 3 / D 2
  - 모든 팀원이 미션 진급 게이트인 "개인 PR 2개 이상" 충족
- AI 리뷰 자동 트리거 횟수: 13 (PR마다 1회)
- AI 리뷰가 잡은 진짜 이슈: 4건
- AI hallucination: 2건 (잡아냄)
- E2E 시나리오: 4개 모두 통과
- API 계약 변경 횟수: 3회 (모두 변경 이력에 기록)

## 다음 주(Week 10) 보강 항목

- 운영 단계 부하 테스트 + Sentry 실제 활용 깊이
- AI 보조 기능(우선순위 추천)의 정확도 측정 + 사람 평가 기준
- 발표용 시연 시나리오 정리
