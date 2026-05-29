# API-CONTRACT v0.1

> API 계약 + 변경 이력. 모든 endpoint 변경은 이 문서 업데이트와 동반.
> 채워진 sample: 정답 PR 시리즈 머지 후 운영진이 별도 공유.

## 형식 원칙

- endpoint 추가 / 시그니처 변경 시 버전 bump (v0.1 → v0.2)
- 변경 이력 표를 맨 아래 누적
- 요청/응답 예시는 jq 가독성 형식으로 1세트씩

## Endpoints (Phase 2 종료 시점)

### POST /api/tickets

> 티켓 생성 (member 페르소나)

**요청**:

```http
POST /api/tickets
Content-Type: application/json
X-User-Id: 3

{
  "title": "...",
  "body": "...",
  "priority": "HIGH"
}
```

**응답**: 201 Created

```json
{
  "id": 1,
  "title": "...",
  "status": "OPEN",
  "priority": "HIGH",
  "aiSummary": "...",
  "requesterId": 3
}
```

**오류**: 400 (title blank / priority invalid), 401 (X-User-Id 헤더 누락)

### GET /api/tickets

> 티켓 목록 조회 (operator 이상)

**쿼리 파라미터**:

- `status` (optional) — `OPEN | IN_PROGRESS | RESOLVED | CLOSED`
- `assigneeId` (optional)
- `keyword` (optional, title/body 부분 일치)
- `page` (default 0)
- `size` (default 20)

**응답**: 200 OK + 배열

### GET /api/tickets/{id}

(operator 이상)

### PUT /api/tickets/{id}/status

> 상태 전이 (operator 이상)

**요청 본문**: `{"status": "IN_PROGRESS"}`
**오류**: 400 (잘못된 전이 — `TicketStatus.canTransitionTo` 위반)

### PUT /api/tickets/{id}/assignee

> 담당자 배정 (admin)

<!-- endpoint 추가 시 이 섹션에 누적 -->

## 변경 이력

| 버전 | 일자 | 변경 | PR |
| --- | --- | --- | --- |
| v0.1 | 2026-MM-DD | 초기 5 endpoint 정의 | #N |
<!-- 변경 시 한 줄씩 추가 -->
