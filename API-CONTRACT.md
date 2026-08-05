# API-CONTRACT v1.1

> API 계약 + 변경 이력. 모든 endpoint 변경은 이 문서 업데이트와 동반.
> 도메인 배경은 `PRD.md`, 코딩 규칙은 `CLAUDE.md`.
> **v1.0 은 도메인 전환(D-031)에 따른 전면 개정이다** — 에러 분류 → CS 문의 분류.
> **v1.1 은 사람 확정 답의 재사용(D-033)** — `verdict` 에 `REUSED` 추가, 감사 통계를 자동확정/재사용으로 분리.

## 형식 원칙

- endpoint 추가 / 시그니처 변경 시 버전 bump
- 변경 이력 표를 맨 아래 누적
- 요청/응답 예시는 jq 가독성 형식으로 1세트씩

## 공통 규약

### 인증 (Phase 2 stub — JWT 는 Phase 3)

| 역할 | 헤더 | 접근 범위 |
| --- | --- | --- |
| `ROLE_CUSTOMER` | `X-User-Id`, `X-User-Role: CUSTOMER` | `POST /api/inquiries` + **자기 문의 조회만** |
| `ROLE_AGENT` | `X-User-Id`, `X-User-Role: AGENT` | 검토 큐 + 전체 문의 조회/확정 |
| `ROLE_MANAGER` | `X-User-Id`, `X-User-Role: MANAGER` | 위 전체 + 통계 + 판정 설정 조회 |

### 공통 오류

| 코드 | 조건 | `code` |
| --- | --- | --- |
| 400 | 필수 필드 누락 / 길이 초과 / enum 값 불일치 | `VALIDATION_FAILED` |
| 401 | 인증 헤더 누락 | `UNAUTHORIZED` |
| 403 | 역할 권한 부족 (예: `ROLE_CUSTOMER` 가 검토 큐 접근) · 남의 문의 조회 | `FORBIDDEN` |
| 404 | 대상 리소스 없음 | `NOT_FOUND` |
| 409 | 확정 충돌 — **원인 2종을 `code` 로 구분**한다 (D-021) | `ALREADY_RESOLVED` / `CONCURRENT_UPDATE` |

**오류 응답 바디는 전 endpoint 공통 형식이다.** 3인 병렬 작업(P1/P2/P3)이 각자 다른 모양을 내보내면 E2E 가 endpoint 마다 다른 파서를 갖게 된다.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "content 는 필수입니다.",
  "fieldErrors": [
    { "field": "content", "reason": "must not be blank" }
  ]
}
```

- `code` — 클라이언트 분기용 안정 식별자. 문자열 상수이며 변경 시 버전 bump 대상
- `message` — 사람이 읽는 설명. **분기 근거로 쓰지 않는다** (문구는 예고 없이 바뀔 수 있다)
- `fieldErrors` — 400 에서만. 그 외에는 필드 자체를 생략한다
- 409 는 `fieldErrors` 대신 `reviewQueueItemId` 를 함께 담는다 (§5 참조)
- **내부 예외 메시지를 그대로 담지 않는다.** 접수 API 는 고객에게 열려 있어 서버 내부 구조가 새어 나가는 자리가 된다
- **남의 문의를 조회하면 404 가 아니라 403 이다.** 404 로 응답하면 id 를 훑어 존재 여부를 알아낼 수 있다

### 카테고리 enum (10종)

`DELIVERY` · `RETURN_REFUND` · `PAYMENT` · `PRODUCT` · `ACCOUNT` · `ORDER_CHANGE` · `PROMOTION` · `SERVICE_USAGE` · `COMPLAINT` · `ETC`

> 상호배타 **경계 정의는 `PRD.md` §7** 에 있다. 핵심 규칙: **분류 기준은 문의의 *원인*이 아니라 고객이 요구하는 *조치*다.**
> 「미분류」는 카테고리가 아니라 `Inquiry.status = UNCLASSIFIED` 로 표현한다.

---

## Endpoints (Phase 2 종료 시점)

### 1. POST /api/inquiries

> 문의 접수 (`ROLE_CUSTOMER`). **AI 분류를 기다리지 않고 즉시 반환** — 그래서 201 이 아니라 **202 Accepted**.

**요청**

```http
POST /api/inquiries
Content-Type: application/json
X-User-Id: 5001
X-User-Role: CUSTOMER

{
  "content": "3일 전에 주문한 상품이 아직도 배송중이라고만 나와요. 그냥 환불해주세요. 주문번호 20260803-771234",
  "channel": "WEB"
}
```

**요청 필드** — 길이 상한은 `V1__init_schema.sql` 의 컬럼 정의와 일치시킨다. 어긋나면 400 이어야 할 요청이 500(`DataException`)으로 나가고, 그러면 **클라이언트 잘못과 서버 잘못이 로그에서 섞인다.**

| 필드 | 필수 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- | --- |
| `content` | ✅ | string | 1~2000자 (`inquiries.content`) | blank 면 400 |
| `channel` | ⭕ | enum | `WEB \| APP \| EMAIL \| PHONE` | 생략 시 `WEB` |

> `normalizedKey` 는 요청에 받지 않는다. 클라이언트가 정하면 같은 문의가 채널마다 다른 키가 되고, **키가 곧 AI 절감**이므로 절감률이 클라이언트 구현에 좌우된다. 서버가 본문에서 산출한다.
>
> ⚠️ **`content` 는 고객이 쓴 자연어라 개인정보가 섞여 들어온다.** 정규화 단계에서 주문번호·연락처·금액·날짜를 마스킹한 뒤 AI 로 보낸다. 위 예시의 주문번호가 마스킹 대상이다.

**응답**: `202 Accepted`

```json
{
  "inquiryId": 4471,
  "status": "RECEIVED",
  "receivedAt": "2026-08-05T10:12:03Z"
}
```

- `status: RECEIVED` 는 **접수됐고 아직 분류 전**을 의미한다. 분류 결과는 이 응답에 담기지 않는다
- 응답에 `normalizedKey` 나 AI 절감 여부를 **담지 않는다** — 고객에게 의미가 없고, 같은 키의 다른 문의가 존재한다는 사실이 새어 나간다

**오류**: 400 (`content` blank / 2000자 초과), 401

---

### 2. GET /api/inquiries

> 문의 목록. `ROLE_CUSTOMER` 는 **자기 문의만**, `ROLE_AGENT` 이상은 전체. 최근 접수 순.

**쿼리 파라미터**

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `status` | (전체) | `RECEIVED \| CLASSIFIED \| UNCLASSIFIED` |
| `category` | (전체) | `inquiries.current_category` 기준 |
| `from` / `to` | (전체) | `received_at` 범위 (ISO-8601) |
| `page` | 0 | |
| `size` | 20 | 최대 100 |

> `category` / `confidence` 는 `inquiry_classification_result` 를 조인하지 않고 **`inquiries` 에 역정규화된 `current_category` / `current_confidence` 를 읽는다** (D-011). 목록 조회에서 행마다 조인이 발생하는 것을 막고, `(status, current_category, received_at)` 인덱스로 필터 + 정렬을 함께 커버하기 위함.
>
> **정렬 축은 `received_at` 하나뿐이라 `sort` 파라미터를 두지 않는다.** 이전 도메인에는 `occurrence_count` 라는 두 번째 정렬 축이 있어 D-013 이 필요했지만, 그 컬럼이 사라지면서 함께 폐기됐다.

**응답**: `200 OK`

```json
{
  "content": [
    {
      "id": 4471,
      "content": "3일 전에 주문한 상품이 아직도 배송중이라고만 나와요. 그냥 환불해주세요. 주문번호 ****",
      "channel": "WEB",
      "status": "CLASSIFIED",
      "category": "RETURN_REFUND",
      "confidence": 0.93,
      "receivedAt": "2026-08-05T10:12:03Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 38
}
```

- `content` 는 **마스킹된 본문**을 반환한다. 원문은 저장하되 응답으로 되돌려주지 않는다
- `confidence` 는 `ROLE_AGENT` 이상에게만 포함된다 — 고객에게는 생략

**오류**: 403 (`ROLE_CUSTOMER` 가 `customerId` 필터 없이 전체 조회 시도)

---

### 3. GET /api/inquiries/{id}

> 문의 상세 + 분류 시도 이력. `ROLE_CUSTOMER` 는 자기 문의만.

**응답**: `200 OK`

```json
{
  "id": 4471,
  "content": "3일 전에 주문한 상품이 아직도 배송중이라고만 나와요. 그냥 환불해주세요. 주문번호 ****",
  "channel": "WEB",
  "status": "CLASSIFIED",
  "category": "RETURN_REFUND",
  "confidence": 0.93,
  "receivedAt": "2026-08-05T10:12:03Z",
  "classifications": [
    {
      "id": 8802,
      "category": "RETURN_REFUND",
      "confidence": 0.93,
      "verdict": "AUTO_ACCEPTED",
      "finalCategory": null,
      "model": "claude-haiku-4-5-20251001",
      "attemptCount": 1,
      "createdAt": "2026-08-05T10:12:05Z"
    }
  ]
}
```

- `classifications` 는 `ROLE_AGENT` 이상에게만 포함된다
- `model` 이 **재사용 출처를 담는 자리**다 (D-031). 2단 절감 경로로 이전 결과를 재사용한 건은 **원본 결과 id** 를 남겨 실제 AI 호출과 구분한다 — 구분이 없으면 측정 6 과 8ⓑ 를 검산할 수 없다
- `verdict = REUSED` 는 AI 를 부르지 않고 같은 정규화 키의 원본 결과를 재사용한 건이다 (D-033). **사람이 확정한 답을 재사용했으면 `confidence` 가 `null`** 이다 — 사람은 확신도를 매기지 않으므로 `1` 이나 원본 AI 값을 채우지 않는다
- `verdict = FAILED` 는 AI 호출·파싱 실패뿐 아니라 **값 검증 실패**도 포함한다 (D-034) — `confidence` 가 `0.0~1.0` 밖이거나, `category` 가 enum 10종에 없거나, 두 필드 중 하나가 아예 없는 경우다. **범위 밖 값을 잘라 넣지 않고 `FAILED` 로 본다** — `1.5` 를 `1.0` 으로 clamp 하면 측정 8ⓐ 의 최상위 구간이 오염된다
- `verdict` 는 **큐에 들어간 사유의 판별식**이기도 하다 (계약 B). 단 이 값은 문의 상세에서만 보이고 **검토 큐 응답에는 나가지 않는다** (§4 blind)

**오류**: 403 (남의 문의), 404

---

### 4. GET /api/inquiry-review-queue

> 검토 큐 조회 (`ROLE_AGENT` 이상). **오래된 순** (`created_at ASC`) — 적체 방지.

**쿼리 파라미터**

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `status` | `PENDING` | `PENDING \| RESOLVED` |
| `from` / `to` | (전체) | `created_at` 범위 |
| `page` / `size` | 0 / 20 | `size` 최대 100 (§2 와 동일) |

> ⚠️ **blind 보증 — `reason` · `confidence` · `threshold` 는 요청 파라미터로도 응답 필드로도 제공하지 않는다.** (D-005, D-010)
>
> `reason` 만 가리는 것으로는 부족하다. 격리 사유는 3종뿐이고 `AUDIT_SAMPLE` 은 **정의상 `confidence >= threshold`** 이므로, 두 값을 함께 주면 상담원이 뺄셈 한 번으로 감사 표본을 100% 식별한다. `category` 필터 역시 AI 제안을 노출하므로 제거했다.
>
> 사유별·신뢰도별 조회는 `GET /api/stats` (`ROLE_MANAGER`) 에서만.

**응답**: `200 OK`

```json
{
  "content": [
    {
      "id": 902,
      "inquiryId": 4471,
      "content": "3일 전에 주문한 상품이 아직도 배송중이라고만 나와요. 그냥 환불해주세요. 주문번호 ****",
      "suggestedCategory": "RETURN_REFUND",
      "status": "PENDING",
      "createdAt": "2026-08-05T10:12:05Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 7
}
```

- `suggestedCategory` 는 남긴다 — 이것까지 가리면 `CLASSIFY_FAILED`(제안 없음)와 나머지가 구별되고, 검토 생산성도 크게 떨어진다
- `CLASSIFY_FAILED` 항목은 `suggestedCategory: null` (분류 결과의 `category` 가 null 이므로 — D-022)
- **이 null 은 blind 위반이 아니다 (D-022)**: 가려야 하는 대상은 **감사 표본**이고, 감사 표본은 정의상 `AUTO_ACCEPTED` 라 `suggestedCategory` 가 **항상 존재**한다. 즉 null 인 항목은 "감사 표본이 **아님**"만 알려줄 뿐, 나머지 중 어느 것이 감사 표본인지는 여전히 알려주지 않는다. D-019 분류로 **결정적 역산 아님**
- **알려진 한계 1 — 앵커링 (D-010)**: AI 제안을 보여주므로 상담원에게 앵커링 편향이 남는다. 사람이 먼저 분류하고 그다음 AI 제안을 공개하는 2단계 방식은 Phase 3
- **알려진 한계 2 — 확률적 추론 (D-019)**: `content` 는 **제거할 수 없다.** 상담원이 문의 원문을 못 읽으면 분류 작업 자체가 불가능하기 때문이다. 다만 숙련된 상담원은 *"이건 딱 봐도 명확한 환불 문의인데 왜 내 큐에 있지"* 로 감사 표본을 **확률적으로** 추론할 수 있다
- 두 한계의 성격이 다르다 — **결정적 역산은 0건이어야 하고**(그건 결함이다), **확률적 추론은 남는다**(그건 감수한다). 따라서 `misclassificationRate` 는 **하한값**으로만 해석한다
- **`REUSED` 건이 감사로 뽑혀 들어와도 응답은 다른 항목과 구별되지 않는다** (D-033). `verdict` 는 검토 큐 응답에 나가지 않으므로, 상담원은 이것이 AI 가 방금 분류한 건인지 지난 답을 재사용한 건인지 알 수 없다 — blind 는 그대로 유지된다

> **이전 도메인보다 확률적 추론이 쉬워졌다.** 에러 메시지는 비전문가에게 균일하게 어렵지만, CS 문의는 상담원이 읽는 순간 난이도를 직관적으로 안다. 측정 10 에서 이 점을 한계로 함께 기록한다.

**오류**: 403 (`ROLE_CUSTOMER` 접근)

---

### 5. PATCH /api/inquiry-review-queue/{id}

> 수동 분류 확정 (`ROLE_AGENT` 이상). 큐 확정 + `final_category` 기록 + 문의 상태 전이를 **한 트랜잭션**으로.

**요청**

```http
PATCH /api/inquiry-review-queue/902
Content-Type: application/json
X-User-Id: 7
X-User-Role: AGENT

{
  "finalCategory": "DELIVERY"
}
```

**응답**: `200 OK`

```json
{
  "id": 902,
  "status": "RESOLVED",
  "inquiryId": 4471,
  "inquiryStatus": "CLASSIFIED",
  "suggestedCategory": "RETURN_REFUND",
  "finalCategory": "DELIVERY",
  "matched": false,
  "agentId": 7,
  "resolvedAt": "2026-08-05T15:02:11Z"
}
```

- `matched: false` = AI 제안과 사람 확정 불일치 → 오분류 집계 대상
- **`suggestedCategory` 가 null(= `CLASSIFY_FAILED`)이면 `matched` 도 `null`** — 비교할 AI 제안이 없으므로 `false` 가 아니다 (D-022). `false` 로 채우면 측정 8 의 오분류 건수에 "AI 가 틀린 건"과 "AI 가 아예 답을 못 낸 건"이 합산된다
- 감사 표본이었더라도 응답에 그 사실은 드러내지 않는다 (blind 유지)

**오류**: 400 (`finalCategory` enum 불일치), 403, 404, **409 (확정 충돌 — 아래 2종)**

### 409 의 두 원인 (D-021)

같은 409 지만 **발생 시점과 원인이 다르므로 `code` 로 구분**한다. 하나로 뭉뚱그리면 동시성 테스트에서 "락이 실제로 동작했는지"를 검증할 수 없다.

| `code` | 시나리오 | 검출 지점 | 필요한 장치 |
| --- | --- | --- | --- |
| `ALREADY_RESOLVED` | B 가 확정을 **끝낸 뒤** A 가 확정 시도 (시간 차) | 조회 시점 상태 검사 (`status != PENDING`) | 상태 검사만으로 충분 |
| `CONCURRENT_UPDATE` | A·B 가 **둘 다 PENDING 을 읽고** 동시에 확정 시도 | 커밋 시점 `@Version` 불일치 | **`@Version` 필수** |

**상태 검사만으로는 부족한 이유**: 두 상담원이 동시에 `PENDING` 을 읽으면 **둘 다 상태 검사를 통과**한다 (check-then-act 경합). 이 창을 막는 것이 `@Version` 이다. 반대로 `@Version` 만 있고 상태 검사가 없으면, 시간 차를 두고 온 요청이 `CONCURRENT_UPDATE` 로 잘못 보고된다 — **경합이 없었는데 경합이라고 말하는 셈**이다.

```json
{
  "code": "CONCURRENT_UPDATE",
  "message": "다른 상담원이 방금 이 항목을 확정했습니다.",
  "reviewQueueItemId": 902
}
```

측정 7ⓑ 에서 두 코드의 발생 비율을 기록한다 — `CONCURRENT_UPDATE` 가 0 이면 경합 창이 재현되지 않은 것이므로 **테스트가 무의미**하다는 신호다.

> **이 endpoint 가 D-031 이후 유일하게 남은 동시성 장치다.** 원자적 UPDATE 와 UNIQUE 충돌 재시도는 대상 컬럼·제약이 함께 사라졌다 (D-007 → D-031).

---

### 6. GET /api/policies

> **판정 설정 조회** (`ROLE_MANAGER`). Phase 2 는 **읽기 전용**이다.

**응답**: `200 OK`

```json
{
  "threshold": 0.8,
  "audit": {
    "sampleRate": 0.05
  }
}
```

**Phase 2 에서 읽기 전용인 이유 (D-028)** — 출처는 `application.yml` (`classification.threshold`, `classification.audit.sample-rate`) 이고 변경은 재기동을 동반한다.

| 필드 | 의미 | 왜 읽기 전용인가 |
| --- | --- | --- |
| `threshold` | 자동 확정 기준 신뢰도 (0.8) | 판정 기준이 실행 중에 바뀌면 측정 1·8 의 결과가 어느 기준에서 나온 것인지 사후에 구분되지 않는다 |
| `audit.sampleRate` | 자동 확정 건 중 감사 표본 추출 비율 (0.05) | **측정 8 의 모집단을 정하는 값**이다. 실행 중 변경을 허용하면 `GET /api/stats` 의 `actualSampleRate` 괴리가 "표본 누락"인지 "설정 변경"인지 갈리지 않아, 감사 장치를 감사하려던 D-012 의 목적이 무너진다 |

> **카테고리별 임계값(`policies` 배열)과 `PATCH /api/policies/{category}` 는 삭제됐다.** 팀 스코프 조정으로 D-006 이 폐기되면서 `classification_policy` 테이블과 함께 사라졌다 (D-031). Phase 3 항목 B·C.
>
> 값 자체를 endpoint 에서 지우지 않고 조회로 남긴 이유는 D-028 그대로다 — `GET /api/stats` 가 `configuredSampleRate` 를 이미 내보내는데 **그 값을 어디서 정하는지가 계약에 없는 상태가 더 나쁘다.**

**오류**: 403

---

### 7. GET /api/stats

> 운영 통계 (`ROLE_MANAGER`). 적체 · 분류 성공률 · **감사 결과**. TTL 10s 캐시.

**응답**: `200 OK`

```json
{
  "backlog": {
    "total": 7,
    "byReason": { "LOW_CONFIDENCE": 4, "CLASSIFY_FAILED": 1, "AUDIT_SAMPLE": 2 },
    "oldestPendingAt": "2026-08-05T10:12:05Z"
  },
  "classification": {
    "inquiriesTotal": 1284,
    "autoAccepted": 1180,
    "needsReview": 96,
    "failed": 8,
    "autoAcceptRate": 0.919,
    "stuckReceived": 0
  },
  "aiCallSavings": {
    "inquiriesReceived": 1284,
    "aiCallsMade": 412,
    "savingsRate": 0.679
  },
  "cache": {
    "hitRate": 0.604,
    "hits": 776,
    "misses": 508
  },
  "audit": {
    "configuredSampleRate": 0.05,
    "autoAccepted": {
      "eligibleTotal": 1180,
      "sampledTotal": 59,
      "actualSampleRate": 0.050,
      "reviewed": 40,
      "mismatched": 6,
      "misclassificationRate": 0.150,
      "byConfidenceBucket": [
        { "range": "0.8-0.9", "reviewed": 24, "mismatched": 5, "actualAccuracy": 0.792 },
        { "range": "0.9-1.0", "reviewed": 16, "mismatched": 1, "actualAccuracy": 0.938 }
      ]
    },
    "reused": {
      "eligibleTotal": 412,
      "sampledTotal": 21,
      "actualSampleRate": 0.051,
      "reviewed": 15,
      "mismatched": 1,
      "misclassificationRate": 0.067
    }
  }
}
```

> **`audit` 을 `autoAccepted` / `reused` 두 블록으로 나눈 이유 (D-033)**: 합치면 이 프로젝트의 결론인 측정 8ⓐ 가 오염된다. `autoAccepted` 는 "AI 답 vs 사람 답" 비교이지만 `reused` 에는 **비교할 AI 답이 없다** — 재사용된 건이기 때문이다. `reused.misclassificationRate` 가 `autoAccepted` 쪽보다 유의미하게 높으면 **재사용이 오류를 증폭하고 있다는 신호**이고, 그때는 사람 확정 재사용을 끄는 것이 재평가 조건이다.
>
> `audit.autoAccepted.byConfidenceBucket` 이 이 프로젝트의 결론이 나오는 자리다 — "AI 가 0.85 라고 한 것들의 **실제** 정확도". **여기 수치는 전부 형식 예시이며, 확정값은 본인 실측으로만 기록한다** (`CLAUDE.md` AI 검증 규칙).
>
> `cache.hitRate` 를 `aiCallSavings` 밖으로 분리한 이유 (D-014, 근거는 D-031 이 교체): **캐시는 DB 조회를 줄이고, AI 호출을 줄이는 것은 2단 경로 전체다.** 캐시 miss 여도 DB 에 같은 정규화 키의 이전 결과가 있으면 AI 를 부르지 않으므로 `hitRate < savingsRate` 가 정상이다. 한 객체 안에 두면 같은 현상의 두 표현으로 오독된다.
>
> `eligibleTotal` / `actualSampleRate` / `configuredSampleRate` 는 **감사 장치 자체를 감사**하기 위한 필드다 (D-012). 표본 삽입이 누락되면 `misclassificationRate` 의 분모가 조용히 줄어 측정 8 이 왜곡되므로, 설정값과 실측 비율의 괴리를 항상 확인할 수 있게 한다.
>
> `stuckReceived` 는 접수 후 10분 이상 `RECEIVED` 에 머문 문의 수다 (D-017). **②트랜잭션이 롤백되면 문의가 여기 남는데 아무도 다시 분류하지 않는다** — 이 시스템이 막으려는 "조용히 유실된 건"을 스스로 만드는 구멍이라 관찰만이라도 한다. 측정 3 에서 실제로 증가하는지 확인한다.

**오류**: 403

---

### 8. Actuator

| endpoint | 용도 |
| --- | --- |
| `GET /actuator/health` | E2E 헬스 체크 (`tests/e2e/api.spec.ts`) |
| `GET /actuator/metrics/triage.queue.backlog` | 큐 적체 건수 gauge (`stats:summary` 캐시 경유) |
| `GET /actuator/metrics/triage.inquiries.stuck_received` | 10분 이상 `RECEIVED` 에 머문 문의 수 — ②롤백으로 조용히 방치된 건 탐지 (D-017). **0 이 아니면 분류 파이프라인이 실패 중** |
| `GET /actuator/metrics/triage.ai.calls` | 실제 AI 호출 횟수 counter (재사용분 제외) |
| `GET /actuator/metrics/triage.classification.success.rate` | 분류 성공률 |
| `GET /actuator/metrics/cache.gets` | `classification:byNormalizedKey` hit/miss |

---

## 변경 이력

| 버전 | 일자 | 변경 | PR |
| --- | --- | --- | --- |
| v0.1 | 2026-07-30 | 에러 분류 검증 파이프라인 8 endpoint + Actuator 초기 정의. 템플릿의 ticket 도메인 예시 폐기 (D-001) | #1 |
| v0.2 | 2026-07-30 | AI 리뷰 반영 — `GET /api/review-queue` 에서 `confidence`·`threshold` 응답 필드와 `category` 필터 제거 (blind 누설 차단, D-010). `GET /api/error-groups` 의 `category`·`confidence` 출처를 역정규화 컬럼으로 명시 (D-011) | #1 |
| v0.3 | 2026-07-30 | AI 리뷰 2차 반영 — `GET /api/error-groups` 에 `sort` 파라미터 추가 (기간 필터 시 filesort 회피). `GET /api/stats` 의 `audit` 에 `eligibleTotal`·`actualSampleRate`·`configuredSampleRate` 추가 (감사율 검증, D-012) | #1 |
| v0.4 | 2026-07-30 | AI 리뷰 3차 반영 — `GET /api/stats` 의 `cacheHitRate` 를 `aiCallSavings` 밖으로 분리해 `cache` 객체로 독립 (캐시 hit rate ≠ AI 절감률, D-014) | #1 |
| v0.5 | 2026-07-30 | AI 리뷰 4차 반영 — `GET /api/stats` 에 `classification.stuckNew` + Actuator gauge 추가 (판정 롤백으로 방치된 그룹 탐지, D-017). §4 blind 한계를 결정적 역산/확률적 추론으로 구분 (D-019) | #1 |
| v0.6 | 2026-07-30 | AI 리뷰 5차 반영 — 409 를 `ALREADY_RESOLVED` / `CONCURRENT_UPDATE` 2종 `code` 로 분리 (D-021) | #1 |
| v0.7 | 2026-07-31 | 코드 착수 전 정합 점검 — 파싱 실패 건의 `confidence` 를 `0` 이 아닌 `null` 로 확정. `suggestedCategory: null` 이 blind 위반이 아닌 근거 추가, `matched` 를 nullable 로 정정 (D-022) | develop 직접 (구현 #3) |
| v0.8 | 2026-08-04 | `service/`·`api/` 착수 전 계약 공백 메우기 — ⓐ 공통 오류 **응답 바디 형식**과 `code` 상수 신설 ⓑ §1 **요청 필드 표** ⓒ §6 에 `mode`·`globalThreshold`·`audit.sampleRate` 를 **읽기 전용**으로 노출 (D-028) ⓓ §7 을 **upsert** 로 정정하고 404 제거 (D-029) ⓔ §2 `sort` 근거의 결정 번호 정정 (D-012 → D-013) | #10 |
| **v1.0** | 2026-08-05 | **도메인 전환에 따른 전면 개정 (D-031)** — 에러 분류 → CS 문의 분류. ⓐ endpoint 8개 → **7개 + Actuator**: `POST/GET /api/inquiries`, `GET /api/inquiries/{id}`, `GET/PATCH /api/inquiry-review-queue`, `GET /api/policies`(읽기 전용 축소), `GET /api/stats` ⓑ **`PATCH /api/policies/{category}` 삭제** (D-006·D-029 폐기) ⓒ 역할 `ROLE_INGEST`/`REVIEWER`/`ADMIN` → **`CUSTOMER`/`AGENT`/`MANAGER`** ⓓ 카테고리 enum 10종 **전면 교체** + 경계 규칙("원인이 아니라 조치")을 `PRD.md` §7 으로 위임 ⓔ `sort` 파라미터 삭제 (D-013 폐기 — 정렬 축이 하나뿐) ⓕ `stuckNew` → **`stuckReceived`** ⓖ 개인정보 마스킹 규약 신설 (문의 본문은 고객 자연어) ⓗ 남의 문의 조회는 **404 가 아니라 403** (id 훑기 차단) | #11 |
| v1.1 | 2026-08-05 | **사람 확정 답의 재사용 (D-033)** — ⓐ `verdict` 에 `REUSED` 추가. 사람 확정을 재사용한 건은 `confidence` 가 `null` (D-022 부분 개정) ⓑ `GET /api/stats` 의 `audit` 을 `autoAccepted` / `reused` 두 블록으로 분리 — 합치면 측정 8ⓐ 가 오염된다 ⓒ `model` 에 **원본 결과 id** 를 남기도록 명시 ⓓ 계약 B 는 불변 — `REUSED` 는 격리 사유가 아니고 감사로 뽑힐 때만 `AUDIT_SAMPLE` 로 큐에 들어간다 | #11 |
<!-- 변경 시 한 줄씩 추가 -->
