# DOMAIN-MODEL — `domain/` 패키지에 뭐가 있나

> **언제 보나**: `service/` 나 `api/` 를 짜다가 "이 엔티티에 뭐가 있더라", "이 값은 언제 null 이더라"가 궁금할 때.
> **여기 없는 것**: 왜 그렇게 정했는지 — `DECISIONS.md`. 코딩 규칙 — `CLAUDE.md`. 규칙이 서로 다르면 **`CLAUDE.md` 가 이깁니다.**
> **고치는 때**: 코드가 바뀌면 **같은 PR 에서** 이 문서도 고칩니다 (`API-CONTRACT.md` 와 같은 규칙). 따로 고치면 반드시 낡습니다.

## 0. 한눈에 보기

```text
domain/
├── Inquiry.java                          문의 1건. 상태를 소유한다
├── InquiryClassificationResult.java      AI 답 + 사람 답을 둘 다 남긴다
├── InquiryReviewQueueItem.java           사람이 봐야 할 목록
├── repository/                           저장만 한다 (인터페이스 3개)
└── type/                                 enum 7개
```

| 클래스 | 한 줄 | 만드는 사람 | 고치는 사람 |
| --- | --- | --- | --- |
| `Inquiry` | 고객이 남긴 문의. **분류의 단위** | P1 (접수, ①) | P2 (②) · P3 (③) |
| `InquiryClassificationResult` | 분류 시도 1회의 기록 | P2 (②) | P3 (`finalCategory` 기록, ③) |
| `InquiryReviewQueueItem` | 검토 대기 항목 | P2 (②) | P3 (확정, ③) |

> `①②③` 은 `CLAUDE.md` 의 **핵심 트랜잭션 3개**입니다. ① 문의 저장 / ② 분류 결과 저장 / ③ 검토 확정.

---

## 0-1. DB 테이블과의 대조

**필드 이름과 컬럼 이름이 다릅니다** — 자바는 `normalizedKey`, DB 는 `normalized_key` 입니다.
쿼리를 짜거나 DB 를 직접 볼 때 이 표를 봅니다. **스키마 원본은
`src/main/resources/db/migration/V2__domain_switch.sql`** 이고, 거기 주석에 근거(D-번호)가 있습니다.

### `Inquiry` → `inquiries`

| 필드 | 컬럼 | 타입 | 비었을 수 있나 |
| --- | --- | --- | --- |
| `id` | `id` | `BIGINT` | ❌ |
| `customerId` | `customer_id` | `BIGINT` | ❌ |
| `content` | `content` | **`VARCHAR(2000)`** | ❌ |
| `channel` | `channel` | `VARCHAR(20)` | ❌ |
| `normalizedKey` | `normalized_key` | `VARCHAR(64)` | ❌ |
| `status` | `status` | `VARCHAR(20)` | ❌ |
| `currentCategory` | `current_category` | `VARCHAR(20)` | ✅ |
| `currentConfidence` | `current_confidence` | **`DECIMAL(4,3)`** | ✅ |
| `receivedAt` | `received_at` | `DATETIME(6)` | ❌ |
| `createdAt` | `created_at` | `DATETIME(6)` | ❌ |
| `updatedAt` | `updated_at` | `DATETIME(6)` | ❌ |

⚠️ **`content` 의 `2000` 은 API 검증과 반드시 같아야 합니다.** 어긋나면 **400 이어야 할 요청이
500(`DataException`)으로 나가고**, 그러면 클라이언트 잘못과 서버 잘못이 로그에서 섞입니다.

⚠️ **`normalized_key` 에 `UNIQUE` 가 없습니다.** 같은 키의 문의가 여러 건인 것이 정상이고 각자
따로 판정됩니다 — **여기 `UNIQUE` 를 걸면 그건 폐기된 그룹핑의 부활입니다** (D-031).

⚠️ **`DECIMAL(4,3)` 이 `0~1` 범위를 막아주지 않습니다.** 이 타입은 `-9.999` 까지 담기고,
마이그레이션 두 개에 **`CHECK` 제약이 0개**이며 엔티티 팩토리도 범위를 다시 안 봅니다.
**범위를 지키는 자리는 AI 응답을 읽는 곳 하나뿐입니다** (D-034) — 타입이 막아준다고 읽으면
그 검증을 빼도 된다고 오해하게 됩니다.

> **`DOUBLE` 이 아닌 이유**는 따로 있습니다. 기준값(예: `0.8`)과 **비교하는** 값이라
> 소수 오차로 판정이 뒤집히면 안 됩니다.

**`received_at` vs `created_at`** — **받은 시각**과 **DB 에 넣은 시각**입니다. 지금은 거의 같지만
과거 문의를 옮겨 담으면 갈립니다. `stuckReceived`(분류가 멈춘 문의)는 **`received_at` 기준**입니다.

**`updated_at`** — 판정 전이는 벌크 UPDATE 라 **JPA auditing 을 안 탑니다.** 그래서 UPDATE 문이
시각을 직접 넣습니다 — 안 그러면 **상태는 바뀌었는데 시각은 접수 때 그대로**인 행이 생겨
판정이 언제 났는지 읽을 수 없습니다.

### `InquiryClassificationResult` → `inquiry_classification_result`

| 필드 | 컬럼 | 타입 | 비었을 수 있나 |
| --- | --- | --- | --- |
| `id` | `id` | `BIGINT` | ❌ |
| `inquiry` | `inquiry_id` | `BIGINT` (FK) | ❌ |
| `category` | `category` | `VARCHAR(20)` | ✅ `FAILED` 면 비어 있음 |
| `confidence` | `confidence` | **`DECIMAL(4,3)`** | ✅ `FAILED` · 사람 답 재사용이면 비어 있음 |
| `model` | `model` | `VARCHAR(100)` | ✅ |
| `rawResponse` | `raw_response` | `TEXT` | ✅ **호출 자체가 실패하면 비어 있음** |
| `verdict` | `verdict` | `VARCHAR(20)` | ❌ |
| `finalCategory` | `final_category` | `VARCHAR(20)` | ✅ 사람이 확정하기 전까지 |
| `attemptCount` | `attempt_count` | `INT` (기본 `1`) | ❌ |
| `createdAt` | `created_at` | `DATETIME(6)` | ❌ |

**`raw_response` 는 실패했을 때만 원인을 갈라줍니다.** 성공한 건에도 응답이 있으면 그대로
저장되기 때문에, **`verdict` 를 먼저 보고 읽어야 합니다.**

| `verdict` | `raw_response` | `model` | 어떻게 읽나 |
| --- | --- | --- | --- |
| `AUTO_ACCEPTED` · `NEEDS_REVIEW` | 받은 응답 그대로 | 모델명 | 정상. 프롬프트를 고칠 때 참고한다 |
| **`FAILED`** · 값 있음 | 받은 응답 그대로 | 모델명 | **응답은 왔는데 못 읽음** (파싱·검증 실패) |
| **`FAILED`** · `null` | `null` | **`null`** | **호출 자체가 실패** — 받은 게 없다 |
| `REUSED` | **항상 `null`** | **`reused:{원본id}`** | AI 를 아예 안 불렀다 |

> 실패해도 받은 게 있으면 남깁니다 — **사유("못 읽었다")만으로는 프롬프트를 어떻게 고칠지
> 알 수 없기** 때문입니다.

**`model` 한 칸에 두 종류가 들어가는 것이 의도입니다.** 실제 호출이면 모델명, 재사용이면
`reused:1234` 처럼 **원본 판정 행의 번호**입니다. 이 구분이 없으면 **AI 절감률을 사후에
검산할 수 없습니다.**

⚠️ **`attempt_count` 는 판정마다 뜻이 조금 다릅니다.**

| `verdict` | 뜻 |
| --- | --- |
| `AUTO_ACCEPTED` · `NEEDS_REVIEW` · `FAILED` | **실제로 AI 를 부른 횟수.** `2` 이상인데 판정이 났으면 *"한 번 실패했다가 살아난 건"* |
| **`REUSED`** | **항상 `1` 이고, AI 는 한 번도 안 불렀습니다.** `0` 을 넣고 싶지만 `1 이상` 제약에 걸려 `1` 로 고정했습니다 |

**그래서 이 칸의 합으로 AI 호출 수를 세면 재사용 건만큼 부풀려집니다.** 실제 호출 수는
`model` 이 `reused:` 로 시작하지 **않는** 행만 세거나, Actuator 의 호출 카운터를 봅니다.

### `InquiryReviewQueueItem` → `inquiry_review_queue`

| 필드 | 컬럼 | 타입 | 비었을 수 있나 |
| --- | --- | --- | --- |
| `id` | `id` | `BIGINT` | ❌ |
| `inquiry` | `inquiry_id` | `BIGINT` (FK) | ❌ |
| `classificationResult` | `classification_result_id` | `BIGINT` (FK) | ❌ |
| `reason` | `reason` | `VARCHAR(20)` | ❌ |
| `status` | `status` | `VARCHAR(20)` | ❌ |
| `agentId` | `agent_id` | `BIGINT` | ✅ 확정 전까지 |
| `resolvedAt` | `resolved_at` | `DATETIME(6)` | ✅ 확정 전까지 |
| `createdAt` | `created_at` | `DATETIME(6)` | ❌ |
| `version` | `version` | `BIGINT` (기본 `0`) | ❌ |

⚠️ **`reason` 은 API 로 안 나갑니다** (blind, D-010). 나가면 감사로 뽑힌 건이 100% 드러납니다.

**`classification_result_id` 가 필수인 이유** — **세 사유 모두 판정 행이 반드시 있습니다.**
못 읽은 건(`CLASSIFY_FAILED`)도 행은 남기기 때문에, 상담원이 *"AI 가 뭐라고 했었나"* 를 볼 수
있고 **아무것도 없는 항목이 큐에 뜨는 일이 없습니다.**

**`agent_id` 와 `resolved_at` 은 항상 같이 채워집니다** — 확정 메서드 하나가 둘을 함께 넣어서,
*"누가 했는지는 아는데 언제인지 모르는"* 행이 생길 수 없습니다.

**`version`** — 이 행을 고칠 때마다 1씩 오릅니다. 두 상담원이 동시에 확정하면 **나중 사람이
들고 있던 번호가 이미 낡아서** 저장이 막힙니다. 실제로 40번 중 40번 막히는 것을 확인했습니다
(`evidence/concurrent-review-confirm.md`).

### 인덱스와 각각의 용도

```sql
-- inquiries
KEY (normalized_key, created_at DESC)          -- 2단 절감 경로: 같은 키의 최근 판정
KEY (status, received_at)                      -- GET /api/inquiries: 상태 + 기간 + 정렬
KEY (status, current_category, received_at)    -- 위 + 종류 필터 동시 사용

-- inquiry_classification_result
KEY (inquiry_id, created_at DESC)              -- 문의 하나의 가장 최근 판정
KEY (verdict, confidence)                      -- 감사 대조: 확신도 구간별 집계 (측정 8)

-- inquiry_review_queue
KEY (status, created_at)                       -- 검토 목록: 대기 중인 것만, 오래된 순
```

**세 번째에서 `current_category` 를 가운데 둔 이유** — 종류는 **등치 조건**이라 앞에 두면
뒤의 기간 범위와 정렬까지 한 인덱스로 커버됩니다.

**목록용 두 개가 겹쳐 보이는데 왜 둘 다 있나** — 겹치는 것은 **`status` 하나뿐**입니다.

```text
(status, received_at)                     ← 종류 필터가 없는 조회
(status, current_category, received_at)   ← 종류 필터가 있는 조회
```

인덱스는 **앞에서부터 이어지는 만큼만** 쓸 수 있습니다. 그래서 **두 번째 것 하나만 두면**,
종류 필터가 없는 조회는 `status` 까지만 쓰고 **`received_at` 의 기간·정렬은 인덱스를 못 탑니다**
(`current_category` 를 건너뛸 수 없기 때문입니다). ⚠️ **두 번째가 첫 번째를 포함하지 않습니다** —
`(status, received_at)` 는 `(status, current_category, received_at)` 의 앞부분이 아닙니다.

⚠️ **위 두 인덱스는 「운영 매니저가 보는 전체 목록」용입니다.** 고객이 자기 문의만 보는 조회는
`customer_id` 조건이 하나 더 붙는데, **세 인덱스 중 어느 것도 `customer_id` 로 시작하지
않습니다.** 그 조회의 실행 계획은 **아직 안 쟀습니다** — 못 쟀다고 적어둡니다.

> **언제 어떻게 잴 건가** — 고객 조회(`customer_id` + 상태 + 기간 + 정렬)에 `EXPLAIN` 을 걸어
> `type` 과 `rows`, `Using filesort` 여부를 봅니다. **`filesort` 가 뜨고 `rows` 가 전체 건수에
> 가까우면** `(customer_id, received_at)` 선행 인덱스를 올립니다. 다만 **재기 전에는 안 올립니다**
> — 이득을 확인하기 전에 쓰기 비용을 얹지 않습니다 (D-018 이 남긴 논거).

**어느 evidence 가 무엇의 근거인지**

| 문서 | 무엇을 쟀나 |
| --- | --- |
| `measurement-5-list-query-explain.md` | **위 목록 쿼리**의 실행 계획 (인덱스 전/후 · 깊은 오프셋) |
| `measurement-5d-reuse-lookup-explain.md` | **2단 절감 경로 1순위 조회** — 목록 쿼리와 무관합니다 |
| `query-plan-review-queue.md` | 검토 큐 조회 + 깊은 페이지 |

---

## 1. 엔티티 3개

### 1-1. `Inquiry` — 문의 1건, 그리고 상태의 주인

테이블 `inquiries`. **컬럼·타입은 0-1 절**에 있고, 여기서는 **언제 채워지고 무엇을 뜻하는지**를 봅니다.

**무엇을 담나**

| 필드 | 언제 채워지나 | 알아둘 것 |
| --- | --- | --- |
| `customerId` | 접수(①) | **회원 테이블이 없어 FK 가 아니다.** 헤더로 받은 값을 그대로 넣는다 |
| `content` | 접수(①) | **원문 그대로 저장한다.** 가린 본문은 저장하지 않고 내보낼 때 계산한다 (D-040) |
| `channel` | 접수(①) | 분류에는 안 쓴다 |
| `normalizedKey` | 접수(①) | **AI 호출을 아끼는 조회 키.** 판정 단위가 아니다 |
| `status` | 접수(①) → 판정(②③) | `RECEIVED` / `CLASSIFIED` / `UNCLASSIFIED` |
| `currentCategory` | 판정(②③) | 미판정이면 `null` |
| `currentConfidence` | 판정(②③) | 미판정·실패·**사람 답 재사용**이면 `null` |
| `receivedAt` | 접수(①) | **파라미터로 받는다.** 아래 설명 참조 |

**만드는 법 — 팩토리 하나뿐**

```java
Inquiry.receive(customerId, content, channel, normalizedKey, receivedAt)
```

- 이 팩토리는 `status = RECEIVED`, `current*` = `null` 로 고정합니다. **그게 "미판정"의 정의**입니다
- `receivedAt` 을 밖에서 받는 이유: 엔티티가 `Instant.now()` 를 직접 부르면 **테스트에서 시각을 조작할 수 없고**, 그러면 "10분 넘게 접수됨에 머문 문의 수"(`stuckReceived`) 지표를 검증할 방법이 없습니다 (D-017)
- `normalizedKey` 를 밖에서 받는 이유: 정규화 규칙이 바뀌면 AI 절감률 전체가 바뀝니다. **DB 없이 단독으로 테스트할 수 있어야** 해서 별도 컴포넌트(P1 소유)가 계산합니다

**⚠️ 조심할 것 3개**

1. `currentCategory` / `currentConfidence` 는 분류 결과의 **복사본**입니다. **②③ 트랜잭션 안에서만** 고칩니다. 다른 데서 손대면 원본과 어긋납니다
2. **복사본이지 요약이 아닙니다.** 원본이 `null` 이면 `null` 을 그대로 복사합니다 — `0` 으로 채우면 안 됩니다 (D-039)
3. `normalizedKey` 가 같다는 이유로 **여러 문의의 상태를 같이 바꾸면 안 됩니다.** 그건 포기한 그룹핑의 부활이고, 개별 문의가 조용히 사라지는 길입니다 (D-031)

---

### 1-2. `InquiryClassificationResult` — 이 프로젝트의 결론이 나오는 자리

테이블 `inquiry_classification_result`. 문의 1건에 **여러 행**이 쌓입니다 (재시도·재분류).
**컬럼·타입은 0-1 절**에 있습니다.

**무엇을 담나**

| 필드 | 뜻 | `null` 이 되는 때 |
| --- | --- | --- |
| `category` | **AI 제안** | `verdict = FAILED` 일 때만 |
| `confidence` | AI 가 **스스로 매긴** 확신도 | `FAILED` / **사람 답을 재사용한 `REUSED`** |
| `model` | 판정의 출처 | 실제 호출이면 모델명, 재사용이면 `reused:{원본id}` |
| `rawResponse` | AI 원본 응답 | 호출 자체가 실패했으면 (0-1 절의 표 참조) |
| `verdict` | 판정 결과 | 없음 (필수) |
| `finalCategory` | **사람 확정** | 아직 아무도 확정 안 했으면 |
| `attemptCount` | 처리 시도 횟수 | 없음. **1 이상**만 허용 — `REUSED` 는 항상 `1` |

> **`category` 와 `final_category` 가 나뉜 것이 이 프로젝트의 핵심입니다.** 앞은 AI 가 말한 것,
> 뒤는 사람이 정한 것이고 **둘 다 남깁니다.** 하나로 합쳐 덮어쓰면 *"AI 가 뭐라고 했었나"* 가
> 사라지고, 그러면 오분류율을 낼 수가 없습니다.

> `category` 와 `confidence` 는 **둘 다 `null` 이거나 둘 다 있거나**입니다 — 단 하나 예외가 사람 답 재사용(`confidence` 만 `null`)입니다.

**만드는 법 — verdict 별 팩토리 5개. 생성자는 막혀 있습니다**

| 팩토리 | 언제 | 특징 |
| --- | --- | --- |
| `autoAccepted(...)` | 확신도 ≥ 기준값 | category·confidence 둘 다 필수 |
| `needsReview(...)` | 확신도 < 기준값 | 같음 |
| `failed(inquiry, model, raw, attempt)` | 재시도 3회 소진 | **category·confidence 파라미터가 아예 없다** |
| `reusedFromHuman(inquiry, category, sourceId)` | 사람 답 재사용 | **confidence 파라미터가 없다** |
| `reusedFromAi(inquiry, category, confidence, sourceId)` | AI 답 재사용 | confidence 는 원본 값 그대로 |

**왜 파라미터를 일부러 뺐나 — 컴파일러가 규칙을 지키게 하려고**

- `failed(...)` 에 `confidence` 자리가 없으니 **`confidence = 0` 을 쓸 방법이 없습니다.** 0 을 쓰면 오분류율의 최하위 구간에 *"AI 가 0 이라 답한 건"*과 *"응답이 깨진 건"*이 섞입니다 (D-022)
- `reusedFromHuman(...)` 에 `confidence` 자리가 없으니 **`1` 을 채울 방법이 없습니다.** `1` 은 거짓말이고(사람도 틀립니다), 원본 AI 값은 **사람이 뒤집은 값**이라 의미가 없습니다 (D-033)

**⚠️ 조심할 것**

- **사람이 확정할 때 `category` 를 덮어쓰지 않습니다.** 덮어쓰면 AI 가 틀렸다는 증거가 사라지고, 그 증거가 이 프로젝트의 결론입니다
- 그래서 이 클래스에 **setter 가 없습니다.** 확정은 `recordFinalCategory(finalCategory)` 하나로만 엽니다 — 이 메서드는 `category` 를 건드리지 않고 `final_category` 칸에만 씁니다
- 재사용해서 만든 답의 `sourceResultId` 에는 **항상 원본**이 들어갑니다. 재사용을 또 재사용하면 원본 하나가 틀렸을 때 어디까지 퍼졌는지 추적할 수 없습니다

---

### 1-3. `InquiryReviewQueueItem` — 사람이 봐야 할 목록

테이블 `inquiry_review_queue`. **컬럼·타입은 0-1 절**에 있습니다.

**무엇을 담나**

| 필드 | 뜻 |
| --- | --- |
| `inquiry` / `classificationResult` | 어떤 문의의 어떤 판정인지. **둘 다 필수** |
| `reason` | 왜 들어왔나. **응답에 절대 안 내보낸다** |
| `status` | `PENDING` / `RESOLVED` |
| `agentId` / `resolvedAt` | 누가 언제 확정했나. **항상 같이 채워진다** |
| `version` | 동시 확정을 막는 값 |

**만드는 법 — 팩토리 하나. `reason` 을 고를 수 없습니다**

```java
InquiryReviewQueueItem.from(classificationResult)
```

`reason` 이 **파라미터가 아닙니다.** 안에서 `verdict` 를 보고 정합니다.

```java
switch (result.getVerdict()) {
    case NEEDS_REVIEW  -> LOW_CONFIDENCE
    case FAILED        -> CLASSIFY_FAILED
    case AUTO_ACCEPTED -> AUDIT_SAMPLE
    case REUSED        -> AUDIT_SAMPLE
}
```

- 호출부가 `reason` 을 직접 고를 경로 자체가 없습니다. **"판별 기준은 `verdict` 다"** 라는 계약 B 를 문서가 아니라 코드가 지킵니다
- 이 `switch` 는 값이 다 채워져 있어서, **`Verdict` 에 값이 늘면 여기가 컴파일 에러로 터집니다.** 계약이 조용히 깨지지 않습니다
- `inquiry` 도 파라미터가 아니라 `result` 에서 꺼냅니다 — 둘이 어긋난 행이 생길 수 없게

**⚠️ 조심할 것 — 감사 표본 가리기(blind)**

`reason` 이 응답으로 새어나가면 **감사로 뽑힌 건이 100% 드러납니다.** P3 가 DTO 로 바꾸는 자리에서 이 필드가 빠졌는지 확인하는 것이 측정 10 입니다. 확신도와 기준값도 같이 가려야 합니다 — 감사 표본은 정의상 `확신도 ≥ 기준값`이라 두 값을 주면 뺄셈 한 번으로 골라낼 수 있습니다.

---

## 2. enum 7개

**7개가 다 같은 성격이 아닙니다.** DB 에 저장되는 것과 응답에만 실리는 것을 갈라 봐야 합니다.

**DB 에 저장되는 6개** — 전부 **이름 문자열**로 넣습니다 (`@Enumerated(EnumType.STRING)`).
**순서(`ORDINAL`)로 저장하지 않습니다** — 순서로 저장하면 나중에 값을 가운데 끼워 넣었을 때
**이미 저장된 행의 뜻이 통째로 밀립니다.** DB 를 열어 봐도 숫자만 보여서 무슨 값인지 알 수 없고요.

| enum | 값 | 어느 컬럼에 | 값이 늘 수 있나 |
| --- | --- | --- | --- |
| `InquiryStatus` | `RECEIVED` · `CLASSIFIED` · `UNCLASSIFIED` | `inquiries.status` | 사실상 고정 |
| `InquiryCategory` | 10종 (`DELIVERY` … `ETC`) | `current_category` · `category` · `final_category` | 경계 정의를 고쳐야 함 |
| `Verdict` | `AUTO_ACCEPTED` · `NEEDS_REVIEW` · `FAILED` · `REUSED` | `...result.verdict` | **늘면 컴파일이 막는다** (아래) |
| `QueueReason` | `LOW_CONFIDENCE` · `CLASSIFY_FAILED` · `AUDIT_SAMPLE` | `...queue.reason` | 계약 B 변경 필요 |
| `QueueStatus` | `PENDING` · `RESOLVED` | `...queue.status` | 사실상 고정 |
| `Channel` | `WEB` · `APP` · `EMAIL` · `PHONE` | `inquiries.channel` | API 계약도 함께 고쳐야 함 |

**DB 에 안 들어가는 것 2개** — 응답으로만 나갑니다.

| enum | 값 | 어디 쓰나 | 어디 있나 |
| --- | --- | --- | --- |
| `ConflictCode` | `ALREADY_RESOLVED` · `CONCURRENT_UPDATE` | **409 응답의 `code`** — 컬럼이 아니다 | `domain/type/` (예외 → 오류 응답) |
| `CacheSource` | `HUMAN` · `AI` | 캐시 값이 사람 답인지 AI 답인지 | **`service/cache/`** — 도메인 타입이 아니다 |

> `ConflictCode` 가 `domain/type/` 에 있는 것은 **위치일 뿐 저장된다는 뜻이 아닙니다.** 확정 충돌
> 예외가 들고 다니다가 오류 응답의 `code` 로 나갑니다. 그래서 **값을 바꾸면 DB 가 아니라 API
> 계약이 깨집니다.**

### `InquiryStatus` — 상태는 문의마다 붙는다

> 값별 뜻은 아래 표, **어떤 순서로 옮겨 다니는지**는 그 밑 그림을 봅니다.

| 값 | 뜻 | 언제 이 상태가 되나 |
| --- | --- | --- |
| `RECEIVED` | **접수됨.** 아직 판정이 안 났다 | 문의를 저장한 직후(①) |
| `CLASSIFIED` | **분류 끝.** 답이 정해졌다 | AI 가 자동 확정했거나 · 지난 답을 재사용했거나 · **사람이 확정했거나** |
| `UNCLASSIFIED` | **사람이 봐야 한다.** 아직 답이 없다 | 확신도가 기준값 미만이거나 · 답을 못 읽었거나 |

```text
RECEIVED ──확신도 높음──> CLASSIFIED
    │
    └──확신도 낮음/실패──> UNCLASSIFIED ──사람이 확정──> CLASSIFIED
```

- **`UNCLASSIFIED → CLASSIFIED` 는 사람만 일으킵니다.** AI 에게 이 권한이 없습니다 (불변 규칙 2)
- `RECEIVED` 에 머물러 있는 건 **정상(분류 대기)일 수도, 유실(② 실패)일 수도** 있습니다. 둘을 시간으로 가른 게 `stuckReceived` 입니다
- ⚠️ **`UNCLASSIFIED` 는 「분류 실패」가 아니라 「사람 차례」입니다.** 이름 때문에 오해하기 쉬운데, 시스템이 제 할 일을 다 하고 사람에게 넘긴 **정상 상태**입니다

### `InquiryCategory` — 원인이 아니라 요구하는 조치

> **고르는 규칙**을 먼저 읽고, 표에서 **값별 뜻과 헷갈리는 경계**를 봅니다.

**"배송이 늦어서 환불해주세요"는 원인이 배송이어도 요구가 환불이라 `RETURN_REFUND`** 입니다. 이 규칙 하나가 10종을 겹치지 않게 만듭니다.

| 값 | 무엇을 담나 | 이런 문의 | 헷갈리는 경계 |
| --- | --- | --- | --- |
| `DELIVERY` | 배송 상태·지연·분실·주소 변경 | *"3일째 배송중이라고만 떠요"* | 환불을 요구하면 `RETURN_REFUND` |
| `RETURN_REFUND` | 반품·교환·환불 | *"받아보니 흠집이 있어 반품할게요"* | 발송 **전** 취소는 `ORDER_CHANGE` |
| `PAYMENT` | 결제 수단·결제 실패·중복 청구 | *"카드가 두 번 결제됐어요"* | 환불 **금액** 이의는 `RETURN_REFUND` |
| `PRODUCT` | 상품 사양·재고 (주로 **사기 전**) | *"이 옷 재입고 되나요?"* | 받은 상품 하자는 `RETURN_REFUND` |
| `ACCOUNT` | 로그인·비밀번호·회원정보·탈퇴 | *"가입할 때 쓴 번호를 바꾸고 싶어요"* | 결제 수단 등록 실패는 `PAYMENT` |
| `ORDER_CHANGE` | 발송 **전** 주문 변경·취소 | *"아직 안 보냈으면 수량 바꿀게요"* | 발송 **후**면 `RETURN_REFUND` |
| `PROMOTION` | 쿠폰·적립금·할인·이벤트 | *"적립금을 어디서 쓰나요?"* | 쿠폰 쓴 결제가 실패하면 `PAYMENT` |
| `SERVICE_USAGE` | 앱·웹 **사용법** (상품 아님) | *"주문 내역이 어디 있나요?"* | 상품 사용법은 `PRODUCT` |
| `COMPLAINT` | 요구사항 **없이** 불만만 | *"서비스가 왜 이래요"* | 요구가 있으면 그 요구의 종류로 |
| `ETC` | 위 9가지에 없음 | 배송·주문과 무관한 제휴 문의 등 | **판단이 어려워서 고르는 칸이 아니다** |

- **`ETC` 는 도피처가 아닙니다.** 정답 데이터에서 10% 를 넘으면 경계 정의가 실패한 것이라 표부터 고칩니다
- **「미분류」는 여기 없습니다** — 카테고리가 아니라 `InquiryStatus.UNCLASSIFIED` 로 표현합니다. **"아직 안 정했다"와 "정했는데 기타다"는 다른 것**이라 칸을 나눴습니다
- ⚠️ **`SERVICE_USAGE` 경계가 가장 얇습니다.** 두 사람이 갈린 5건 중 4건, AI 가 틀린 건, AI 답과 정답이 갈린 4건 중 3건이 전부 여기 얽혀 있었습니다 — 같은 신호가 **세 번** 나왔습니다
- **값을 늘리면 정답 데이터를 다시 붙여야 합니다.** 이미 매긴 50건의 정답이 새 종류를 모르기 때문입니다
- 경계 정의 전문은 `PRD.md` §7

### `Verdict` — 큐 사유의 판별식

> 표에서 **네 값의 뜻**을, 그 아래에서 **두 갈래로 읽는 법**과 **값을 늘리면 어디가 막히는지**를 봅니다.

| 값 | 뜻 | 언제 이 값이 되나 | `category` | `confidence` |
| --- | --- | --- | --- | --- |
| `AUTO_ACCEPTED` | **AI 답을 그대로 받아들였다** | 확신도 ≥ 기준값 | 있음 | 있음 (≥ 기준값) |
| `NEEDS_REVIEW` | **자신 없다고 해서 사람에게 넘겼다** | 확신도 < 기준값 | 있음 | 있음 (< 기준값) |
| `FAILED` | **답을 못 읽었다** | 3번 불러도 응답이 없거나 · 값이 이상하거나 | **`null`** | **`null`** |
| `REUSED` | **같은 내용의 지난 답을 가져다 썼다** | 1단 캐시나 2단 DB 에서 찾았을 때 | 있음 | 사람 답이면 **`null`**, AI 답이면 있음 |

**넷을 두 갈래로 읽으면 쉽습니다.**

```text
사람 손을 안 거치고 확정   AUTO_ACCEPTED · REUSED   → 감사 대상 (20건에 1건 뽑음)
사람에게 넘어감            NEEDS_REVIEW · FAILED    → 전건이 검토 목록으로
```

**`category == null` 을 판별에 쓰지 마세요.** 그건 결과일 뿐이고, 판별식은 `verdict` 입니다 (D-022).

⚠️ **값이 늘면 컴파일이 막습니다.** 이 enum 을 보는 `switch` 가 **네 곳**에 있고 전부 값을 다 채운 형태라, 다섯 번째 값을 넣는 순간 네 곳이 전부 에러로 터집니다.

| 어디 | 무엇을 정하나 |
| --- | --- |
| `InquiryReviewQueueItem.from` | 큐에 들어갈 때의 **사유** |
| `ClassificationService.statusFor` | 이 판정이 문의를 **확정시키나** |
| `ClassificationService.newResult` | 어떤 **팩토리**로 판정 행을 만드나 |
| `ClassificationService.enqueueIfNeeded` | **큐에 넣나** — 전건인가 일부인가 |

*"새 판정이 큐에 들어가야 하나"* 를 아무도 안 정한 채로 지나갈 수 없게 한 장치입니다.

### `QueueReason` — 왜 이 항목이 사람에게 왔나

> 표의 마지막 칸(**전건인가 일부인가**)이 이 enum 의 핵심입니다.

| 값 | 뜻 | 어떤 `verdict` 에서 오나 | 전건인가 일부인가 |
| --- | --- | --- | --- |
| `LOW_CONFIDENCE` | AI 가 **자신 없다**고 했다 | `NEEDS_REVIEW` | **전건** |
| `CLASSIFY_FAILED` | AI 답을 **못 읽었다** | `FAILED` | **전건** |
| `AUDIT_SAMPLE` | 잘 처리됐지만 **몰래 뽑혔다** | `AUTO_ACCEPTED` · `REUSED` | **일부** (20건에 1건) |

- **호출부가 이 값을 고를 수 없습니다.** `verdict` 를 보고 안에서 정합니다 (아래 팩토리 참조)
- **`AUDIT_SAMPLE` 만 성격이 다릅니다.** 앞의 둘은 *"시스템이 못 해서"* 온 것이고, 이건 *"잘 됐는데 그래도 확인하려고"* 온 것입니다. **이 항목이 이 프로젝트의 존재 이유**입니다
- ⚠️ **이 값은 화면에 절대 안 나갑니다.** 나가면 상담원이 *"아 이건 감사구나"* 하고 다르게 봅니다

### `QueueStatus` — 검토 항목의 두 가지 상태

> 값이 둘뿐이라 표는 짧고, **그 아래 세 줄**이 실제로 걸리는 것들입니다.

| 값 | 뜻 | 함께 채워지는 것 |
| --- | --- | --- |
| `PENDING` | **아직 아무도 확정 안 함.** 목록에 뜬다 | `agentId`·`resolvedAt` 이 `null` |
| `RESOLVED` | **확정됨.** 목록에서 빠진다 | `agentId`·`resolvedAt` 이 채워짐 |

- **되돌아가는 전이가 없습니다.** `RESOLVED → PENDING` 은 없고, 그래서 확정은 한 번뿐입니다
- **적체 통계는 `PENDING` 만 셉니다.** 그래서 감사로 뽑힌 건도 확정되고 나면 적체에서 빠집니다 — 측정할 때 이걸 모르면 *"감사가 큐에 안 들어갔다"* 로 잘못 읽습니다
- 「처리 중(누가 보고 있음)」 상태는 **아직 없습니다** — `PRD.md` §8 항목 H 로 미뤄뒀습니다

### `ConflictCode` — 둘 다 필요한 이유 (DB 저장 아님 · 응답 전용)

둘 다 `409` 로 나가지만 **원인이 다르고, 상담원에게 할 말도 다릅니다.**

| 값 | 언제 | 무엇으로 잡나 | 사용자에게 |
| --- | --- | --- | --- |
| `ALREADY_RESOLVED` | B 가 확정을 **끝낸 뒤** A 가 시도 | 조회 시점 상태 검사 | *"이미 처리된 항목입니다"* |
| `CONCURRENT_UPDATE` | A·B 가 **둘 다 `PENDING` 을 읽고** 동시에 시도 | 커밋 시점 `@Version` | *"방금 다른 분이 처리했습니다"* |

- 상태 검사만 두면: 동시에 읽은 두 명이 **둘 다 통과**합니다
- `@Version` 만 두면: 시간 차 요청을 **경합이라고 잘못 보고**합니다
- **두 코드를 나눈 덕에 측정이 가능해졌습니다** — 동시 확정 40회를 재현했을 때 `CONCURRENT_UPDATE` 가 40번, `ALREADY_RESOLVED` 가 0번 나온 것이 *"진짜로 동시에 부딪혔다"* 는 증거였습니다. 한 코드로 뭉쳤으면 구분할 수 없었습니다

### `Channel` — 분류에 안 쓴다

> 값 목록보다 **「분류에 안 쓰면서 왜 담나」** 가 이 절의 요점입니다.

| 값 | 뜻 |
| --- | --- |
| `WEB` | 웹사이트 문의 폼 |
| `APP` | 모바일 앱 |
| `EMAIL` | 이메일로 온 것 |
| `PHONE` | 전화 상담을 상담원이 받아적은 것 |

**경로가 카테고리를 정하지 않기 때문에 분류에는 안 씁니다.** 전화로 왔다고 배송 문의인 건 아니니까요.

그럼 왜 담나 — **정규화 키가 채널에 따라 얼마나 덜 겹치는지** 보려고 담습니다. 같은 내용이라도 **전화 기록은 상담원이 요약해 적고 웹 입력은 고객이 직접 씁니다.** 문장이 달라지면 키가 달라지고, 키가 달라지면 AI 를 또 부릅니다. 절감률이 기대보다 낮게 나올 때 **채널 탓인지 정규화 규칙 탓인지** 가르는 데 씁니다.

- ⚠️ **값을 늘리려면(예: `KAKAO`) `API-CONTRACT.md` 를 같이 고쳐야 합니다.** 계약에 허용 값이 `WEB | APP | EMAIL | PHONE` 로 못박혀 있어서, enum 만 늘리면 **문서와 실제가 갈립니다**
  - 다만 **코드 쪽은 안 깨집니다** — 이 값을 보는 `switch` 도, DB `CHECK` 제약도 없고 `VARCHAR(20)` 이라 새 값이 그냥 들어갑니다

---

## 3. 셋을 관통하는 규칙 4개

1. **setter 를 만들지 않습니다.** 필드마다 setter 를 열면 위의 규칙들(덮어쓰기 금지, ②③ 안에서만 갱신, 상태 검사+버전 검사)을 지킬 자리가 사라집니다. 상태 변경은 **뜻이 있는 메서드**로만 엽니다
2. **생성은 팩토리로만.** 생성자는 막혀 있습니다. 잘못된 값 조합으로 객체를 만들 수 없게 하려는 것입니다 (D-025)
3. **판별식은 `verdict` 하나.** `null` 검사로 분기하지 않습니다
4. **연관은 전부 LAZY.** 그래서 목록을 뽑을 때 항목마다 쿼리가 더 나가는 문제(N+1)가 생깁니다 — 검토 큐 조회에서 `@EntityGraph` 로 막아야 합니다

## 4. 상태를 바꾸는 메서드 — 어디서 누가 부르나

**setter 가 없으므로 상태는 아래 메서드로만 바뀝니다.** 각각 **정해진 트랜잭션 안에서만** 불립니다 — 다른 데서 부르면 원본과 사본이 어긋납니다.

| 메서드 | 무엇을 바꾸나 | 어느 트랜잭션 | 만든 사람 |
| --- | --- | --- | --- |
| `Inquiry.applyClassification(category, confidence)` | 판정 결과를 문의에 복사 | ② | P2 |
| `Inquiry.confirmByAgent(finalCategory)` | 사람이 정한 답으로 확정 | ③ | P3 |
| `InquiryClassificationResult.recordFinalCategory(finalCategory)` | 사람 답을 **별도 칸에** 기록 | ③ | P3 |
| `InquiryReviewQueueItem.resolve(agentId, resolvedAt)` | 큐 항목을 `RESOLVED` 로 | ③ | P3 |

> 문의의 **상태 전이 자체**(`RECEIVED → CLASSIFIED` 등)는 엔티티 메서드가 아니라 **저장소의 조건부 UPDATE 한 문장**이 합니다. *"아직 접수됨일 때만 바꿔라"* 를 DB 에 맡겨야 **같은 신호가 두 번 와도 큐 항목이 2건이 되지 않기** 때문입니다.

## 5. 아직 없는 것

| 없는 것 | 왜 | 어디에 |
| --- | --- | --- |
| 검토 항목 **선점**(누가 보고 있음) | 5일 범위 밖. 미룬 것 중 **가장 먼저 붙일 것** | `PRD.md` §8 항목 H · TRI-93 |
| 멈춘 문의 **자동 재분류** | 지금은 지표로 보이게만 했다 | `PRD.md` §8 항목 E · TRI-94 |
| `masked_content` 컬럼 | **일부러 안 만들었다** — 저장해두면 가리는 규칙을 조여도 옛 행은 옛 규칙 그대로 남는다 | D-040 |
| `classification_policy` 테이블 | 카테고리별 기준값이 폐기되면서 함께 사라졌다 | D-031 |
