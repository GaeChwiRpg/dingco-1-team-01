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

## 1. 엔티티 3개

### 1-1. `Inquiry` — 문의 1건, 그리고 상태의 주인

테이블 `inquiries`.

**무엇을 담나**

| 필드 | 언제 채워지나 | 알아둘 것 |
| --- | --- | --- |
| `customerId` | 접수(①) | |
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

**무엇을 담나**

| 필드 | 뜻 | `null` 이 되는 때 |
| --- | --- | --- |
| `category` | **AI 제안** | `verdict = FAILED` 일 때만 |
| `confidence` | AI 가 **스스로 매긴** 확신도 | `FAILED` / **사람 답을 재사용한 `REUSED`** |
| `model` | 판정의 출처 | 실제 호출이면 모델명, 재사용이면 `reused:{원본id}` |
| `rawResponse` | AI 원본 응답 | 응답 자체를 못 받았으면 |
| `verdict` | 판정 결과 | 없음 (필수) |
| `finalCategory` | **사람 확정** | 아직 아무도 확정 안 했으면 |
| `attemptCount` | 시도 횟수 | 없음. **1 이상**만 허용 |

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
- 그래서 이 클래스에 **setter 가 없습니다.** 확정은 `recordFinalCategory(...)` 처럼 뜻이 있는 메서드로만 엽니다 (아직 P3 가 안 만들었습니다)
- 재사용해서 만든 답의 `sourceResultId` 에는 **항상 원본**이 들어갑니다. 재사용을 또 재사용하면 원본 하나가 틀렸을 때 어디까지 퍼졌는지 추적할 수 없습니다

---

### 1-3. `InquiryReviewQueueItem` — 사람이 봐야 할 목록

테이블 `inquiry_review_queue`.

**무엇을 담나**

| 필드 | 뜻 |
| --- | --- |
| `inquiry` / `classificationResult` | 어떤 문의의 어떤 판정인지. **둘 다 필수** |
| `reason` | 왜 들어왔나. **응답에 절대 안 내보낸다** |
| `status` | `PENDING` / `RESOLVED` |
| `agentId` / `resolvedAt` | 누가 언제 확정했나 |
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

| enum | 값 | 어디 쓰나 |
| --- | --- | --- |
| `InquiryStatus` | `RECEIVED` · `CLASSIFIED` · `UNCLASSIFIED` | 문의의 상태 |
| `InquiryCategory` | 10종 (`DELIVERY` … `ETC`) | 분류 결과 |
| `Verdict` | `AUTO_ACCEPTED` · `NEEDS_REVIEW` · `FAILED` · `REUSED` | 임계값 검증의 판정 |
| `QueueReason` | `LOW_CONFIDENCE` · `CLASSIFY_FAILED` · `AUDIT_SAMPLE` | 큐에 들어온 사유 |
| `QueueStatus` | `PENDING` · `RESOLVED` | 큐 항목 상태 |
| `Channel` | `WEB` · `APP` · `EMAIL` · `PHONE` | 문의가 들어온 경로 |
| `ConflictCode` | `ALREADY_RESOLVED` · `CONCURRENT_UPDATE` | 409 의 원인 구분 |

### `InquiryStatus` — 상태는 문의마다 붙는다

```text
RECEIVED ──확신도 높음──> CLASSIFIED
    │
    └──확신도 낮음/실패──> UNCLASSIFIED ──사람이 확정──> CLASSIFIED
```

- **`UNCLASSIFIED → CLASSIFIED` 는 사람만 일으킵니다.** AI 에게 이 권한이 없습니다 (불변 규칙 2)
- `RECEIVED` 에 머물러 있는 건 **정상(분류 대기)일 수도, 유실(② 실패)일 수도** 있습니다. 둘을 시간으로 가른 게 `stuckReceived` 입니다

### `InquiryCategory` — 원인이 아니라 요구하는 조치

**"배송이 늦어서 환불해주세요"는 원인이 배송이어도 요구가 환불이라 `RETURN_REFUND`** 입니다. 이 규칙 하나가 10종을 겹치지 않게 만듭니다.

헷갈리는 경계 9개:

| 이쪽 | 이럴 땐 저쪽 |
| --- | --- |
| `DELIVERY` | 환불을 요구하면 `RETURN_REFUND` |
| `RETURN_REFUND` | 발송 **전** 취소는 `ORDER_CHANGE` |
| `PAYMENT` | 환불 **금액** 이의는 `RETURN_REFUND` |
| `PRODUCT` | 받은 상품 하자는 `RETURN_REFUND` |
| `ACCOUNT` | 결제 수단 등록 실패는 `PAYMENT` |
| `ORDER_CHANGE` | 발송 **후**면 `RETURN_REFUND` |
| `PROMOTION` | 쿠폰 쓴 결제가 실패하면 `PAYMENT` |
| `SERVICE_USAGE` | 상품 사용법은 `PRODUCT` |
| `COMPLAINT` | 요구가 있으면 그 요구의 종류로 |

- **`ETC` 는 판단이 어려워서 고르는 칸이 아닙니다.** 정답 데이터에서 10% 를 넘으면 경계 정의가 실패한 것이라 표부터 고칩니다
- **「미분류」는 여기 없습니다** — 카테고리가 아니라 `InquiryStatus.UNCLASSIFIED` 로 표현합니다
- 경계 정의 전문은 `PRD.md` §7

### `Verdict` — 큐 사유의 판별식

| 값 | `category` | `confidence` |
| --- | --- | --- |
| `AUTO_ACCEPTED` | 있음 | 있음 (≥ 기준값) |
| `NEEDS_REVIEW` | 있음 | 있음 (< 기준값) |
| `FAILED` | **`null`** | **`null`** |
| `REUSED` | 있음 | 사람 답이면 **`null`**, AI 답이면 있음 |

**`category == null` 을 판별에 쓰지 마세요.** 그건 결과일 뿐이고, 판별식은 `verdict` 입니다 (D-022).

### `ConflictCode` — 둘 다 필요한 이유

| 값 | 언제 | 무엇으로 잡나 |
| --- | --- | --- |
| `ALREADY_RESOLVED` | B 가 확정을 **끝낸 뒤** A 가 시도 | 조회 시점 상태 검사 |
| `CONCURRENT_UPDATE` | A·B 가 **둘 다 `PENDING` 을 읽고** 동시에 시도 | 커밋 시점 `@Version` |

- 상태 검사만 두면: 동시에 읽은 두 명이 **둘 다 통과**합니다
- `@Version` 만 두면: 시간 차 요청을 **경합이라고 잘못 보고**합니다

### `Channel` — 분류에 안 쓴다

경로가 카테고리를 정하지 않기 때문입니다. 통계용이고, **정규화 키가 채널에 따라 얼마나 덜 겹치는지** 보는 데 씁니다 (같은 내용도 전화 기록과 웹 입력은 문장이 다릅니다).

---

## 3. 셋을 관통하는 규칙 4개

1. **setter 를 만들지 않습니다.** 필드마다 setter 를 열면 위의 규칙들(덮어쓰기 금지, ②③ 안에서만 갱신, 상태 검사+버전 검사)을 지킬 자리가 사라집니다. 상태 변경은 **뜻이 있는 메서드**로만 엽니다
2. **생성은 팩토리로만.** 생성자는 막혀 있습니다. 잘못된 값 조합으로 객체를 만들 수 없게 하려는 것입니다 (D-025)
3. **판별식은 `verdict` 하나.** `null` 검사로 분기하지 않습니다
4. **연관은 전부 LAZY.** 그래서 목록을 뽑을 때 항목마다 쿼리가 더 나가는 문제(N+1)가 생깁니다 — 검토 큐 조회에서 `@EntityGraph` 로 막아야 합니다

## 4. 아직 없는 것

baseline 은 **필드·매핑·생성 팩토리까지**입니다. **상태를 바꾸는 메서드는 각 트랜잭션의 담당자가 추가합니다.**

| 없는 메서드 | 누가 | 어느 트랜잭션 |
| --- | --- | --- |
| `Inquiry` 의 상태 전이 + `current*` 갱신 | P2 | ② |
| `Inquiry` 의 사람 확정 전이 | P3 | ③ |
| `InquiryClassificationResult.recordFinalCategory(...)` | P3 | ③ |
| `InquiryReviewQueueItem.resolve(...)` | P3 | ③ |
