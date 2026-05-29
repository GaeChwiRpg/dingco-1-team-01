# PRD — (제품 이름)

> 기획 단계 (라이프사이클 1단계) 책임자가 채우는 템플릿.
> 채워진 sample: [`examples/week9-team-lifecycle-coverage.md`](./examples/week9-team-lifecycle-coverage.md) 의 1단계 섹션.

## 1. 한 줄 소개

> 이 시스템은 **누구**의 **어떤 문제**를 **어떻게** 해결하는가? 1줄.

(작성 예시) 사내 IT 운영팀이 슬랙에 흩어진 티켓을 한 곳에서 추적하고 SLA 를 측정하기 위한 시스템.

## 2. 페르소나

| 페르소나 | 권한 | 핵심 행동 | 진입점 |
| --- | --- | --- | --- |
| (예: 신청자/member) | (예: 본인 티켓 CRUD) | (예: 새 티켓 생성, 본인 티켓 진행상황 확인) | (예: /tickets) |
| (예: 운영자/operator) | | | |
| (예: 관리자/admin) | | | |

## 3. User Story

> 페르소나 × 행동 × 결과 형식으로 5~10개. **공통 필수 기능 6개**(권한·트랜잭션·검색·캐시·비동기·AI)가 최소 1개씩 매핑되도록.

- US-1: (운영자)로서 (열려있는 모든 티켓을 우선순위 순서로) 보고 (담당자를 배정)할 수 있다.
- US-2: ...

## 4. 핵심 흐름 (state diagram 또는 sequence)

> 텍스트로 표현해도 OK. mermaid 권장.

```text
티켓 생성 (member)
  → AI 자동 요약
  → 운영자 트리아지
  → 상태 전이 (OPEN → IN_PROGRESS → RESOLVED → CLOSED)
  → 알림
```

## 5. 비기능 요구사항

- 사용자 규모: (예: 동시 운영자 100명)
- 응답 시간: (목표 + 측정 방법)
- 데이터 정합성: (예: 동시 상태 전이 시 last-write-wins 금지)
- 보안: (예: JWT 기반, 권한 분리)

## 6. 6 공통 필수 기능 매핑

| 공통 기능 | 본 시스템 매핑 | 담당자 |
| --- | --- | --- |
| 권한·역할 | (예: ADMIN/OPERATOR/MEMBER + Spring Security `@PreAuthorize`) | |
| 핵심 트랜잭션 | (예: 상태 전이 + 담당자 배정 묶음) | |
| 검색·필터 | (예: 티켓 status / assignee / keyword 복합 필터) | |
| 캐시 | (예: open-ticket-count `@Cacheable` + create/update 시 evict) | |
| 비동기·이벤트 | (예: TicketStatusChangedEvent Spring Events) | |
| AI 보조 | (예: 자동 요약 / 우선순위 추천) | |

## 7. 제약 사항

- 5일 안에 완성 가능한 범위로 자릅니다. Phase 3 보강 항목은 별도 표기.
- (예: JWT 본격 통합은 Phase 3, Phase 2 는 X-User-Id 헤더 stub)

## 8. 성공 지표 (Phase 2 종료 시점)

- [ ] 6 공통 필수 기능 모두 구현
- [ ] 라이프사이클 5 단계 모두 산출물 1개 이상
- [ ] 8 PR 이상 머지 + 모두 mission-guard / AI Review CI green
- [ ] (도메인 별) 핵심 흐름 1개 시연 가능
