# API-CONTRACT v0.3

> API 계약 + 변경 이력. 모든 endpoint 변경은 이 문서 업데이트와 동반.
> 도메인 배경은 `PRD.md`, 코딩 규칙은 `CLAUDE.md`.

## 형식 원칙

- endpoint 추가 / 시그니처 변경 시 버전 bump (v0.1 → v0.2)
- 변경 이력 표를 맨 아래 누적
- 요청/응답 예시는 jq 가독성 형식으로 1세트씩

## 공통 규약

### 인증 (Phase 2 stub — JWT 는 Phase 3)

| 역할 | 헤더 | 접근 범위 |
| --- | --- | --- |
| `ROLE_INGEST` | `X-Api-Key: <sdk-key>` | `POST /api/errors` **만** |
| `ROLE_REVIEWER` | `X-User-Id`, `X-User-Role: REVIEWER` | 검토 큐 + 에러 그룹 조회/확정 |
| `ROLE_ADMIN` | `X-User-Id`, `X-User-Role: ADMIN` | 위 전체 + 정책 + 통계 |

### 공통 오류

| 코드 | 조건 |
| --- | --- |
| 400 | 필수 필드 누락 / enum 값 불일치 / threshold 범위 위반 |
| 401 | 인증 헤더 누락 또는 API key 불일치 |
| 403 | 역할 권한 부족 (예: `ROLE_INGEST` 가 검토 큐 접근) |
| 404 | 대상 리소스 없음 |
| 409 | 낙관적 락 충돌 (이미 확정된 큐 항목 재확정) |

### 카테고리 enum (10종)

`DB_CONNECTION` · `DB_QUERY` · `TIMEOUT` · `AUTH` · `VALIDATION` · `EXTERNAL_API` · `NULL_REFERENCE` · `OUT_OF_MEMORY` · `SERIALIZATION` · `CONFIG`

> 「미분류」는 카테고리가 아니라 `ErrorGroup.status = UNCLASSIFIED` 로 표현한다.

---

## Endpoints (Phase 2 종료 시점)

### 1. POST /api/errors

> 에러 수신 (`ROLE_INGEST`). **AI 분류를 기다리지 않고 즉시 반환** — 그래서 201 이 아니라 **202 Accepted**.

**요청**

```http
POST /api/errors
Content-Type: application/json
X-Api-Key: sdk-live-a1b2c3

{
  "message": "Could not open JDBC Connection for transaction",
  "stackTrace": "org.hibernate.exception.JDBCConnectionException: ...\n\tat com.dingco.repo.OrderRepo.save(OrderRepo.java:42)\n\t...",
  "source": "order-service",
  "occurredAt": "2026-07-30T10:12:03Z"
}
```

**응답**: `202 Accepted`

```json
{
  "errorGroupId": 17,
  "fingerprint": "a3f9c1e04b2d",
  "groupStatus": "NEW",
  "occurrenceCount": 1,
  "newGroup": true
}
```

- `newGroup: false` 면 AI 호출 없이 카운트만 증가한 것 (이미 분류된 그룹의 재발)
- `groupStatus` 는 응답 시점 값. `NEW` 는 아직 분류 전을 의미

**오류**: 400 (`message` blank), 401 (`X-Api-Key` 누락/불일치)

---

### 2. GET /api/error-groups

> 에러 그룹 목록 (`ROLE_REVIEWER` 이상). 발생 횟수 많은 순.

**쿼리 파라미터**

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `status` | (전체) | `NEW \| CLASSIFIED \| UNCLASSIFIED` |
| `category` | (전체) | `error_group.current_category` 기준 |
| `from` / `to` | (전체) | `last_seen_at` 범위 (ISO-8601) |
| `sort` | `occurrenceCount` | `occurrenceCount` (발생 많은 순) \| `lastSeenAt` (최근 순) |
| `page` | 0 | |
| `size` | 20 | 최대 100 |

> **`sort` 를 둔 이유 (D-012)**: `from`/`to` 범위 필터와 `occurrence_count DESC` 정렬을 함께 쓰면 한 B-tree 로 커버되지 않아 filesort 가 발생한다. 기간 필터를 쓰는 조회는 `sort=lastSeenAt` 을 선택하면 `(status, last_seen_at)` 인덱스로 **범위 + 정렬을 함께 커버**한다. 어느 쪽이 실제로 유리한지는 측정 5번 EXPLAIN 결과로 확정한다.
>
> | 조회 패턴 | 권장 `sort` | 인덱스 |
> | --- | --- | --- |
> | 기간 필터 없음 | `occurrenceCount` | `(status, current_category, occurrence_count DESC)` |
> | 기간 필터 있음 | `lastSeenAt` | `(status, last_seen_at)` |

> `category` / `confidence` 는 `classification_result` 를 조인하지 않고 **`error_group` 에 역정규화된 `current_category` / `current_confidence` 를 읽는다** (D-011). 목록 조회에서 그룹당 조인이 발생하는 것을 막고, `(status, current_category, occurrence_count DESC)` 인덱스로 필터+정렬을 함께 커버하기 위함.

**응답**: `200 OK`

```json
{
  "content": [
    {
      "id": 17,
      "fingerprint": "a3f9c1e04b2d",
      "sampleMessage": "Could not open JDBC Connection for transaction",
      "status": "CLASSIFIED",
      "occurrenceCount": 1284,
      "category": "DB_CONNECTION",
      "confidence": 0.93,
      "firstSeenAt": "2026-07-30T10:12:03Z",
      "lastSeenAt": "2026-07-30T14:51:40Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 38
}
```

---

### 3. GET /api/error-groups/{id}

> 그룹 상세 + 분류 시도 이력 (`ROLE_REVIEWER` 이상).

**응답**: `200 OK`

```json
{
  "id": 17,
  "fingerprint": "a3f9c1e04b2d",
  "sampleMessage": "Could not open JDBC Connection for transaction",
  "status": "CLASSIFIED",
  "occurrenceCount": 1284,
  "classifications": [
    {
      "id": 31,
      "category": "DB_CONNECTION",
      "confidence": 0.93,
      "model": "claude-sonnet-5",
      "verdict": "AUTO_ACCEPTED",
      "finalCategory": null,
      "attemptCount": 1,
      "createdAt": "2026-07-30T10:12:04Z"
    }
  ]
}
```

- `finalCategory: null` = 아직 사람이 확정하지 않음
- `category ≠ finalCategory` 인 레코드가 **오분류 1건**

**오류**: 404

---

### 4. GET /api/review-queue

> 검토 큐 조회 (`ROLE_REVIEWER` 이상). **오래된 순** (`created_at ASC`) — 적체 방지.

**쿼리 파라미터**

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `status` | `PENDING` | `PENDING \| RESOLVED` |
| `from` / `to` | (전체) | `created_at` 범위 |
| `page` / `size` | 0 / 20 | |

> ⚠️ **blind 보증 — `reason` · `confidence` · `threshold` 는 요청 파라미터로도 응답 필드로도 제공하지 않는다.** (D-005, D-010)
>
> `reason` 만 가리는 것으로는 부족하다. 격리 사유는 3종뿐이고 `AUDIT_SAMPLE` 은 **정의상 `confidence >= threshold`** 이므로, 두 값을 함께 주면 검토자가 뺄셈 한 번으로 감사 표본을 100% 식별한다. `category` 필터 역시 AI 제안을 노출하므로 제거했다.
>
> 사유별·신뢰도별 조회는 `GET /api/stats` (ADMIN) 에서만.

**응답**: `200 OK`

```json
{
  "content": [
    {
      "id": 902,
      "errorGroupId": 17,
      "sampleMessage": "Could not open JDBC Connection for transaction",
      "occurrenceCount": 1284,
      "suggestedCategory": "DB_CONNECTION",
      "status": "PENDING",
      "createdAt": "2026-07-30T10:12:05Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 7
}
```

- `suggestedCategory` 는 남긴다 — 이것까지 가리면 `CLASSIFY_FAILED`(제안 없음)와 나머지가 구별되고, 검토 생산성도 크게 떨어진다
- `CLASSIFY_FAILED` 항목은 `suggestedCategory: null`
- **알려진 한계 (D-010)**: AI 제안을 보여주므로 검토자에게 앵커링 편향이 남는다. 따라서 `GET /api/stats` 의 `audit.misclassificationRate` 는 **하한값**이며 실제 오분류율은 이보다 높다. 사람이 먼저 분류하고 그 다음 AI 제안을 공개하는 2단계 방식은 Phase 3 (항목 I)

**오류**: 403 (`ROLE_INGEST` 접근)

---

### 5. PATCH /api/review-queue/{id}

> 수동 분류 확정 (`ROLE_REVIEWER` 이상). 큐 확정 + `final_category` 기록 + 그룹 상태 전이를 **한 트랜잭션**으로.

**요청**

```http
PATCH /api/review-queue/902
Content-Type: application/json
X-User-Id: 7
X-User-Role: REVIEWER

{
  "finalCategory": "DB_QUERY"
}
```

**응답**: `200 OK`

```json
{
  "id": 902,
  "status": "RESOLVED",
  "errorGroupId": 17,
  "groupStatus": "CLASSIFIED",
  "suggestedCategory": "DB_CONNECTION",
  "finalCategory": "DB_QUERY",
  "matched": false,
  "reviewerId": 7,
  "resolvedAt": "2026-07-30T15:02:11Z"
}
```

- `matched: false` = AI 제안과 사람 확정 불일치 → 오분류 집계 대상
- 감사 표본이었더라도 응답에 그 사실은 드러내지 않는다 (blind 유지)

**오류**: 400 (`finalCategory` enum 불일치), 404, **409 (이미 `RESOLVED` — 낙관적 락 충돌)**

---

### 6. GET /api/policies

> 카테고리별 임계값 조회 (`ROLE_ADMIN`).

**응답**: `200 OK`

```json
{
  "defaultThreshold": 0.9,
  "policies": [
    { "category": "AUTH",           "threshold": 0.90, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "DB_CONNECTION",  "threshold": 0.85, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "OUT_OF_MEMORY",  "threshold": 0.85, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "CONFIG",         "threshold": 0.80, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "TIMEOUT",        "threshold": 0.75, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "DB_QUERY",       "threshold": 0.75, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "EXTERNAL_API",   "threshold": 0.75, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "SERIALIZATION",  "threshold": 0.70, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "NULL_REFERENCE", "threshold": 0.60, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" },
    { "category": "VALIDATION",     "threshold": 0.60, "updatedBy": 3, "updatedAt": "2026-07-30T09:00:00Z" }
  ]
}
```

**임계값 배정 근거**: 오분류 비용이 큰 쪽을 높게. `AUTH` 는 보안 인접이라 최고, `DB_CONNECTION` / `OUT_OF_MEMORY` 는 장애 직결. `NULL_REFERENCE` / `VALIDATION` 은 흔하고 오분류 비용이 낮음. 미등록 카테고리는 `defaultThreshold 0.9` fallback (보수적 = 격리 쪽으로 실패).

---

### 7. PATCH /api/policies/{category}

> 임계값 조정 (`ROLE_ADMIN`).

**요청**

```http
PATCH /api/policies/DB_CONNECTION
Content-Type: application/json
X-User-Id: 3
X-User-Role: ADMIN

{
  "threshold": 0.80
}
```

**응답**: `200 OK`

```json
{
  "category": "DB_CONNECTION",
  "threshold": 0.80,
  "previousThreshold": 0.85,
  "updatedBy": 3,
  "updatedAt": "2026-07-30T15:20:00Z"
}
```

- 소급 적용 없음. 이미 판정된 그룹은 재분류하지 않는다 (자동 재분류는 Phase 3)

**오류**: 400 (`threshold` 가 0.0~1.0 밖), 403 (`ROLE_REVIEWER` 접근), 404 (미정의 카테고리)

---

### 8. GET /api/stats

> 운영 통계 (`ROLE_ADMIN`). 적체 · 분류 성공률 · **감사 결과**. TTL 10s 캐시.

**응답**: `200 OK`

```json
{
  "backlog": {
    "total": 7,
    "byReason": { "LOW_CONFIDENCE": 4, "CLASSIFY_FAILED": 1, "AUDIT_SAMPLE": 2 },
    "oldestPendingAt": "2026-07-30T10:12:05Z"
  },
  "classification": {
    "groupsTotal": 38,
    "autoAccepted": 29,
    "needsReview": 8,
    "failed": 1,
    "autoAcceptRate": 0.763
  },
  "aiCallSavings": {
    "eventsReceived": 1284,
    "aiCallsMade": 38,
    "savingsRate": 0.970,
    "cacheHitRate": 0.968
  },
  "audit": {
    "eligibleTotal": 29,
    "sampledTotal": 12,
    "actualSampleRate": 0.414,
    "configuredSampleRate": 0.05,
    "reviewed": 10,
    "mismatched": 2,
    "misclassificationRate": 0.200,
    "byConfidenceBucket": [
      { "range": "0.8-0.9", "reviewed": 6, "mismatched": 2, "actualAccuracy": 0.667 },
      { "range": "0.9-1.0", "reviewed": 4, "mismatched": 0, "actualAccuracy": 1.000 }
    ]
  }
}
```

> `audit.byConfidenceBucket` 이 이 프로젝트의 결론이 나오는 자리다 — "AI 가 0.85 라고 한 것들의 **실제** 정확도". 여기 수치는 형식 예시이며, 확정값은 본인 실측으로만 기록한다 (`CLAUDE.md` AI 검증 규칙).
>
> `eligibleTotal` / `actualSampleRate` / `configuredSampleRate` 는 **감사 장치 자체를 감사**하기 위한 필드다 (D-012). 표본 삽입이 누락되면 `misclassificationRate` 의 분모가 조용히 줄어 측정 8 이 왜곡되므로, 설정값과 실측 비율의 괴리를 항상 확인할 수 있게 한다. (위 예시는 초기 표본이 적어 실측 비율이 설정값과 크게 벌어진 상태)

**오류**: 403

---

### 9. Actuator

| endpoint | 용도 |
| --- | --- |
| `GET /actuator/health` | E2E 헬스 체크 (`tests/e2e/api.spec.ts`) |
| `GET /actuator/metrics/triage.queue.backlog` | 큐 적체 건수 gauge (`stats:summary` 캐시 경유) |
| `GET /actuator/metrics/triage.ai.calls` | AI 호출 횟수 counter |
| `GET /actuator/metrics/triage.classification.success.rate` | 분류 성공률 |
| `GET /actuator/metrics/cache.gets` | `classification:byFingerprint` hit/miss |

---

## 변경 이력

| 버전 | 일자 | 변경 | PR |
| --- | --- | --- | --- |
| v0.1 | 2026-07-30 | 에러 분류 검증 파이프라인 8 endpoint + Actuator 초기 정의. 템플릿의 ticket 도메인 예시 폐기 (D-001) | #1 |
| v0.2 | 2026-07-30 | AI 리뷰 반영 — `GET /api/review-queue` 에서 `confidence`·`threshold` 응답 필드와 `category` 필터 제거 (blind 누설 차단, D-010). `GET /api/error-groups` 의 `category`·`confidence` 출처를 역정규화 컬럼으로 명시 (D-011) | #1 |
| v0.3 | 2026-07-30 | AI 리뷰 2차 반영 — `GET /api/error-groups` 에 `sort` 파라미터 추가 (기간 필터 시 filesort 회피). `GET /api/stats` 의 `audit` 에 `eligibleTotal`·`actualSampleRate`·`configuredSampleRate` 추가 (감사율 검증, D-012) | #1 |
<!-- 변경 시 한 줄씩 추가 -->
