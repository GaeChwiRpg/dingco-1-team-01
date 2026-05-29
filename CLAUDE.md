# CLAUDE.md — 팀 헌법 (template)

> 코딩 단계 (라이프사이클 2단계) 팀 전원이 공동 작성하는 템플릿.
> 이 파일은 모든 prompt 에 자동으로 포함되며 위반 시 hook 이 차단합니다.
> 채워진 sample 은 미션 종료 후 운영진이 별도 공유합니다.

## 도메인 컨텍스트

### 시스템

- (한 줄: 무엇을 하는 시스템인가)
- 페르소나: (예: 운영자 / 신청자 / 관리자)
- 핵심 흐름: (예: 티켓 생성 → AI 자동 요약 → 운영자 트리아지 → 상태 전이 → 알림 → 종료)

### 도메인 모델

```text
(예시)
Ticket(id, title, body, status, priority, requester_id, assignee_id, ai_summary, created_at, updated_at)
User(id, name, email, password_hash, role, created_at)
Comment(id, ticket_id, author_id, body, ai_drafted, created_at)
```

### 핵심 쿼리 + 인덱스

- (예: GET /api/tickets — status / assignee / keyword 복합 필터 + priority desc + created_at desc 정렬)
- 인덱스: (예: `(status, created_at DESC)`, `(assignee_id, status)`)

## 6 공통 필수 기능 매핑

> PRD.md 6 공통 필수 기능 표를 그대로 복사 + 코드 위치를 추가.

| 기능 | 본 시스템 매핑 | 코드 위치 |
| --- | --- | --- |
| 권한·역할 | | (예: `security/SecurityConfig.java`) |
| 핵심 트랜잭션 | | (예: `service/TicketService.changeStatus`) |
| 검색·필터 | | (예: `domain/TicketRepository.search`) |
| 캐시 | | (예: `service/TicketService.openTicketCount`) |
| 비동기·이벤트 | | (예: `service/TicketStatusChangedEvent`) |
| AI 보조 | | (예: `service/AiSummaryService`) |

## 코딩 규칙

### 3계층 분리 (Week 1 학습)

- Controller (`api/`) — HTTP 입출력만
- Service (`service/`) — 비즈니스 흐름 + 트랜잭션 경계
- Domain + Repository (`domain/`) — 도메인 객체 + 저장만

### `@Transactional` 위치 (Week 1 학습)

- Controller 절대 X
- 단일 read 도 X (cost > benefit)
- 묶음 read+write 만 (예: `create`, `changeStatus`, `assignTo`)

### LAZY 기본 (Week 2 학습)

- `@ManyToOne` / `@OneToMany` 모두 LAZY
- N+1 발견 시 `@EntityGraph` 또는 fetch join 적용 + evidence 남김

### 락 전략 (Week 5 학습)

- (예: 동시 변경 가능한 메서드는 `@Lock(PESSIMISTIC_WRITE)`)
- 사용자 규모 / 충돌 빈도 측정 후 재평가 (DECISIONS.md 후속)

### 캐시 전략 (Week 7 학습)

- 변경 빈도 << 조회 빈도 인 endpoint 만 캐시
- `@CacheEvict(allEntries=true)` 로 일괄 무효화

## 작업 경계

- 미션 외 디렉토리 수정 금지
- `src/main/` 외 폴더는 readonly (예: `docs/`, `tests/e2e/` 는 별도 PR)
- 비밀 정보 (`.env`, JWT secret, API key) commit 절대 금지

## 출력 형식

- 답을 통째로 주지 말고 단계별 reasoning 먼저
- API 변경은 반드시 `API-CONTRACT.md` 업데이트 동반
- 측정 결과는 본인 실측만 사용 (AI 추정값 금지)

## AI 검증 규칙 (책 11장 4비법)

- AI 가 만든 응답시간/throughput 추정 수치는 evidence 사용 금지 — 본인 hey/wrk 측정만
- AI hallucination 사례는 즉시 `evidence/failure-cases.md` 에 추가
- 모든 prompt 는 페·목·형·제 4 요소 강제 (페르소나·목표·형식·제약)

## 라이프사이클 단계 책임자

각 단계는 책임자 1명이 산출물 끝까지 책임. 단계 간 협업은 PR 본문 + INTEGRATION-LOG.md 로 동기화.

| 단계 | 책임자 | 도구 |
| --- | --- | --- |
| 기획 | (팀원 A) | Jira MCP, AI PRD |
| 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI |
| 테스트 | (팀원 B) | Playwright MCP |
| 리뷰 | (팀원 C) | Claude GitHub Actions |
| 배포·운영 | (팀원 D) | Sentry MCP, Docker |

## 변경 절차

이 헌법을 바꾸는 결정은 모두 `DECISIONS.md` 에 새 항목으로 추가. 헌법 그대로 덮어쓰기 금지.
