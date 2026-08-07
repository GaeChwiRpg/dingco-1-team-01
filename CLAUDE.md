# CLAUDE.md — 팀 헌법

> 이 파일은 모든 prompt 에 자동으로 포함되며 위반 시 hook 이 차단합니다.
> 변경은 반드시 `DECISIONS.md` 새 항목으로. 덮어쓰기 금지.
> 상세 요구사항은 `PRD.md`, endpoint 계약은 `API-CONTRACT.md`.
> 처음 합류했다면 `ONBOARDING.md` 부터 — 로컬 환경 셋업(⚠️ Docker Desktop 버전 제약)과 작업 순서 안내.

## 도메인 컨텍스트

### 시스템

- 고객 문의를 AI가 자동 분류하고, **신뢰도가 임계값 미만이면 격리**하며, **자동 확정된 결과도 일부를 무작위 감사**하는 검증 파이프라인
- 페르소나: 고객 (`ROLE_CUSTOMER`) / 상담원 (`ROLE_AGENT`) / 운영 매니저 (`ROLE_MANAGER`)
- 핵심 흐름: 문의 접수 (즉시 응답) → 비동기 AI 분류 (**정규화 키로 호출 절감**) → **임계값 검증** → 자동 확정 or 검토 큐 격리 → 상담원 확정 → 캐시 갱신
- 감사 경로: **자동 확정 건 5% + 재사용 건 5%** 무작위 → 검토 큐 (`AUDIT_SAMPLE`) → 사람 확정 → `category ≠ final_category` 면 오분류 1건 집계

**이 시스템의 존재 이유**: AI가 스스로 신고한 신뢰도를 무조건 믿지 않는다. 신뢰도 검증(1차) + 감사 샘플링(2차) 두 겹.

**이 도메인이라야 하는 이유 — 판별 기준 (D-027, 적용 조건은 D-031 이 정정)**

설계 자산의 대부분은 도메인 중립이라 도메인이 교체 가능해 보이지만, 아래를 못 넘는 도메인에서는 이 설계가 성립하지 않는다. **도메인 교체 제안은 기준 통과를 먼저 보인다.** 못 넘으면 새 `DECISIONS.md` 항목 없이 반려한다.

1. ~~**묶음 동질성**~~ — **현행 도메인은 이 기준에서 탈락했고, 탈락을 인정하고 그룹핑을 포기했다 (D-031).** 이 기준은 *그룹핑을 하는 설계*의 전제이지 모든 도메인의 관문이 아니다. 그룹핑을 되살리자는 제안에만 다시 적용된다
2. **정답 단일성** — 팀이 확정한 정답이 **유일한가**. 안 맞으면 오분류율에 검토자 불일치가 섞여 측정 8 을 읽을 수 없다 (측정 1·8 의 전제)
3. **AI 존재 이유** — 규칙·룩업 테이블로 풀리지 않는가. 안 맞으면 AI 가 부수 기능으로 강등된다 (D-001 의 전제)

> **기준 1 을 대체하는 새 관문 (D-031)**: 그룹핑을 포기했으므로 이후 도메인 제안은 **그룹핑에 매달렸던 자산 — 특히 「캐시」와 AI 절감률 — 을 무엇으로 대체하는지**를 함께 보여야 한다. 현행 도메인의 답은 아래 「캐시 전략」의 **2단 절감 경로**다.
> 검토·탈락한 후보 4종은 D-027, 전환 근거와 그 대가는 D-031 참조.

### 도메인 모델

```text
Inquiry(id, customer_id, content, channel, normalized_key, status,
        current_category, current_confidence, received_at, created_at, updated_at)
  └ 테이블명은 inquiries. 분류의 단위. status: RECEIVED | CLASSIFIED | UNCLASSIFIED
  └ normalized_key 는 AI 호출 절감용 조회 키다. 판정 단위가 아니다 (D-031)
  └ current_* 는 분류 결과의 역정규화 사본 (D-011). 목록 조회 조인 제거용
       사본이지 요약이 아니다 — 원본이 null 이면 null 을 그대로 복사한다 (D-039)
       current_confidence = null : 미판정 · FAILED · 사람 확정을 재사용한 REUSED
       current_category   = null : 미판정뿐. REUSED 에도 카테고리는 있다
  └ content 는 원문으로 저장한다. 마스킹본은 저장하지 않고 내보낼 때 계산한다 (D-040)

InquiryClassificationResult(id, inquiry_id, category, confidence, model, raw_response,
                           verdict, final_category, attempt_count, created_at)
  └ verdict: AUTO_ACCEPTED | NEEDS_REVIEW | FAILED | REUSED
  └ category = AI 제안, final_category = 사람 확정. 둘 다 보존 (덮어쓰기 금지)
  └ category·confidence 가 둘 다 null = verdict FAILED (D-022)
  └ confidence 만 null = REUSED 중 사람 확정을 재사용한 건 (D-033)
       사람은 확신도를 매기지 않으므로 1 이나 원본 AI 값을 채우지 않는다
  └ REUSED 는 model 에 원본 결과 id 를 남긴다 — 재사용 경로를 추적할 수 없으면 측정 6·8ⓑ 를 못 읽는다

InquiryReviewQueueItem(id, inquiry_id, classification_result_id, reason, status,
                       agent_id, resolved_at, created_at, version)
  └ reason: LOW_CONFIDENCE | CLASSIFY_FAILED | AUDIT_SAMPLE
  └ status: PENDING | RESOLVED

InquiryCategory (10종): DELIVERY, RETURN_REFUND, PAYMENT, PRODUCT, ACCOUNT,
                        ORDER_CHANGE, PROMOTION, SERVICE_USAGE, COMPLAINT, ETC
  └ 상호배타 경계 정의는 PRD.md §7. 경계가 흔들리면 측정 8 이 오염된다 (기준 2)
```

임계값은 `classification.threshold` **단일 설정값**이다. 카테고리별 차등(D-006)과 `classification_policy` 테이블은 폐기됐다 — D-031.

**불변 규칙 3개**

1. `final_category` 기록 시 `category` 를 덮어쓰지 않는다. 덮어쓰면 오분류 증거가 사라진다.
2. `UNCLASSIFIED → CLASSIFIED` 전이는 **사람만** 일으킨다. AI 에게 이 전이 권한 없음.
3. `Inquiry.current_*` 는 역정규화 사본이므로 **판정이 확정되는 트랜잭션(②③) 안에서만** 갱신한다. 다른 경로에서 손대면 원본과 어긋난다. — D-011

> 이전 판의 불변 규칙 1(상태는 `ErrorGroup` 이 소유)은 그룹핑 폐기로 사라졌다. 이제 `Inquiry` 가 자기 상태를 소유하며, 이는 자명해서 규칙으로 둘 필요가 없다. **단 `normalized_key` 가 같다는 이유로 상태를 공유시키려는 시도는 그룹핑의 부활이므로 금지한다 (D-031).**

### 핵심 쿼리 + 인덱스

| 쿼리 | 인덱스 | 예상 EXPLAIN |
| --- | --- | --- |
| `GET /api/inquiry-review-queue` — status + 기간 필터 + `created_at ASC` (오래된 순) | `(status, created_at)` | `ref` + 인덱스 순서로 정렬, filesort 없음 |
| 2단 절감 경로의 **1순위** — 같은 키에서 사람이 확정한 답 (`inquiries ⋈ result`, `final_category IS NOT NULL`) | 구동 `inquiries(normalized_key, created_at DESC)`<br>조인 `result(inquiry_id, created_at DESC)` | 구동 `ref` + 정렬 커버, 조인 `ref`, `final_category` 는 **서버 필터** ⚠️ |
| 2단 절감 경로의 **2순위** — 위가 비었을 때만. AI 자동확정 (`verdict='AUTO_ACCEPTED'`) | 같은 두 인덱스 | 같음. `verdict` 는 등치라 필터가 싸다 |
| `GET /api/inquiries` — status 필터 + 기간 범위 + `received_at` 정렬 | `(status, received_at)` | `range`, 정렬까지 커버 |
| 위 + `category` 필터 동시 사용 | `(status, current_category, received_at)` | category 는 등치라 선행 컬럼에 두면 뒤의 범위 + 정렬까지 커버 가능 |
| 문의별 최신 분류 결과 | `(inquiry_id, created_at DESC)` | `ref` |
| 감사 대조 — `verdict=AUTO_ACCEPTED AND final_category IS NOT NULL` 신뢰도 구간별 집계 | `(verdict, confidence)` | `range` |

> 측정 5 에서 위 예상이 맞는지 `EXPLAIN` 실측으로 확인하고, 틀렸으면 `evidence/` 에 기록한다.
>
> ⚠️ **2단 절감 경로의 조회는 조인이다 (D-037).** `normalized_key` 는 `inquiries` 에, `final_category` · `verdict` 는 `inquiry_classification_result` 에 있다. 이전 판이 이 조회를 단일 테이블처럼 적어둔 탓에 인덱스 하나가 조회 전체를 커버하는 것처럼 보였다 — **커버되는 것은 구동 테이블 쪽뿐**이다.
>
> ⚠️ **정렬 축은 `inquiries.created_at` 이고, 이는 판정 시각의 근사다 (D-041).** 이 쿼리가 집는 것은 "가장 최근 판정"이 아니라 **"가장 최근 문의의 판정"** 이라, 늦게 확정된 옛 문의의 사람 답을 지나칠 수 있다. 그래도 바꾸지 않는다 — 같은 키의 사람 답이 서로 다르다면 그건 정렬 문제가 아니라 **검토자 불일치(측정 12)** 이고, 정렬로 덮으면 불일치가 있다는 사실이 안 보이게 된다.
> `GET /api/inquiries` 의 정렬 축이 `received_at` 인 것과 어긋나지 않는다 — **둘은 다른 쿼리이고 다른 인덱스를 쓴다.**
>
> ⚠️ **1순위와 2순위는 쿼리를 따로 친다 (D-037).** 우선순위를 `ORDER BY (final_category IS NOT NULL) DESC` 같은 정렬식으로 표현하면 filesort 가 확정이고, 그렇다고 `LIMIT 1` 로 최신 한 건만 뽑으면 **사람 확정 행을 지나쳐 D-033 이 무력화된다.** 1순위가 맞으면 거기서 끝나고, 두 번째 쿼리는 1순위가 빈 경우에만 나간다.
>
> ⚠️ **`final_category IS NOT NULL` 은 인덱스로 걸리지 않는다 (AI 리뷰 지적).** 1순위 조회는 위 두 인덱스로 행을 좁힌 뒤 이 조건을 **서버에서 필터**하므로, 같은 키의 판정 행이 쌓일수록 훑는 양이 늘어난다.
> 그렇다고 지금 인덱스를 더 붙이지 않는다 — **이득을 측정으로 확인하기 전에 쓰기 비용을 얹지 않는다**(D-018 이 남긴 논거). 측정 5ⓓ 에 이 쿼리의 `EXPLAIN` 을 케이스로 넣어 `rows` 가 실제로 문제 될 규모인지 먼저 본다. 키당 판정 행이 한 자릿수면 인덱스는 낭비다.
>
> **인덱스 컬럼의 갱신 빈도를 함께 보는 습관은 유지한다.** 근거였던 D-018 은 대상 컬럼(`occurrence_count`)이 사라져 폐기됐지만, 논거 자체는 살아 있다 — `inquiries` 는 INSERT 위주이고 UPDATE 는 판정 확정 시 1회뿐이라 지금은 트레이드오프가 성립하지 않을 뿐이다. **갱신 빈도가 높은 컬럼을 인덱스에 넣으려 할 때 D-018 을 다시 읽는다.**
> 검토 큐에 `category` 필터가 없는 이유는 blind 규칙 — 아래 참조.

## 6 공통 필수 기능 매핑

| 기능 | 본 시스템 매핑 | 코드 위치 |
| --- | --- | --- |
| 권한·역할 | 3역할 분리 — 접수 주체 ≠ 판정 주체 ≠ 관측 주체 | `config/SecurityConfig.java` |
| 핵심 트랜잭션 | ① 문의 저장 (즉시 응답, AI 대기 없음)<br>② 분류 결과 저장 + 문의 전이 + 조건부 큐 삽입 (**감사 표본 삽입 포함** — D-012)<br>③ 큐 확정 + `final_category` 기록 + 문의 전이 | ① `service/InquiryIngestService.receive`<br>② `service/ClassificationService.verifyAndPersist`<br>③ `service/ReviewService.confirm` |
| 검색·필터 | 큐 복합 필터 + 페이징 | `domain/repository/InquiryReviewQueueRepository.search` |
| 캐시 | ① `normalized_key → 분류 결과` (AI 호출 절감 경로의 1단)<br>② 적체·감사 요약 통계 (TTL 10s) | ① `service/ClassificationCache`<br>② `service/StatsService.summary` |
| 비동기·이벤트 | `InquiryReceivedEvent` → `@Async` 분류 담당 + `@Retryable(3)` + `@Recover` | `service/event/InquiryReceivedEventListener`, `service/event/` |
| AI 보조 | 분류 + **임계값 검증** + **감사 샘플링** | `service/AiClassificationService`, `service/AuditSamplingPolicy` |

## 코딩 규칙

### 3계층 분리

- `api/` — HTTP 입출력만. DTO 변환까지. 도메인 객체를 그대로 반환 금지
- `service/` — 비즈니스 흐름 + 트랜잭션 경계
- `domain/` — 도메인 객체 + `repository/` 저장만

### `@Transactional` 위치

- Controller 절대 X
- 단일 read 도 X (cost > benefit)
- 묶음 read+write 만 — 위 표의 ①②③ **세 메서드**. 여기 밖에 새로 붙이려면 근거를 PR 본문에 쓴다
- **트랜잭션 ①과 ②는 반드시 분리된 상태로 둔다 (D-031).** ① 은 고객에게 접수 확인을 돌려준 시점에 이미 커밋돼 있어야 한다. ②가 실패해도 ①은 **살아남아야 한다** — 롤백되면 고객이 받은 접수 확인이 거짓말이 된다. 대신 문의가 `RECEIVED` 로 방치되므로 stuck 지표로 드러낸다 (D-017)
- **측정 장치를 트랜잭션 밖에 두지 않는다.** 감사 표본 큐 삽입은 ② 안에 포함한다 — 누락되면 감사율이 설정값 미달이 되어 측정 8 의 분모가 조용히 줄어든다. "부차적이라 분리한다"는 판단은 외부 의존성에만 적용한다 (D-012)
- **캐시 갱신은 커밋 후** (`@TransactionalEventListener(AFTER_COMMIT)`). 롤백된 판정이 캐시에 남으면 안 됨
- **분류 캐시를 건드리는 자리는 ②와 ③ 둘뿐이다 (D-036).** ③(사람 확정)도 캐시를 갱신한다 — 안 하면 사람이 정정한 답을 두고 캐시가 옛 AI 답을 계속 재사용한다

### LAZY 기본

- `@ManyToOne` / `@OneToMany` 모두 LAZY
- **알려진 N+1 지점**: `GET /api/inquiry-review-queue` 에서 항목별 `Inquiry` + `InquiryClassificationResult` 접근 → `@EntityGraph` 필수. 적용 후 쿼리 수 evidence 남김

### 락·동시성 전략 (D-007 → D-031 으로 축소)

| 지점 | 수단 | 이유 |
| --- | --- | --- |
| 큐 항목 중복 확정 | **상태 검사 + 낙관적 락 `@Version`** → 409 (`code` 2종) | 두 상담원이 같은 항목을 집는 빈도가 낮아 비관적 락은 과잉. **둘 다 필요하다** — 상태 검사만으로는 동시에 `PENDING` 을 읽은 경합(check-then-act)을 못 막고, `@Version` 만으로는 시간 차 요청을 경합으로 오보한다 (D-021) |

| 트랜잭션 ② 의 중복 실행 | **조건부 UPDATE** — `SET status=? WHERE id=? AND status='RECEIVED'`, 갱신 0행이면 이미 처리된 것으로 보고 반환 | 이벤트가 두 번 도착하면 큐 항목이 2건 되어 **상담원이 같은 문의를 두 번 보고 측정 8ⓐ 의 분모가 부푼다.** 상태 검사(check-then-act)로는 동시 도착을 못 막고, UNIQUE 제약은 D-016 탓에 재시도를 트랜잭션 밖으로 빼는 구조가 따라온다 (D-049) |

> **2행째는 D-049 로 채워졌다 — 그리고 그 수단이 하필 D-031 에서 잃었던 원자적 UPDATE 다.** 대상 컬럼(`occurrence_count`)이 사라져 수단까지 소멸한 줄 알았는데, **같은 수단이 다른 자리에서 필요해졌다.** 함께 소멸했던 UNIQUE 충돌 재시도(D-016)는 여전히 없고, D-016 의 교훈은 D-049 가 UNIQUE 를 **택하지 않은 근거**로 살아 있다. 이 손실과 회수는 둘 다 감추지 않는다.
>
> **3행째로 예정된 것: 검토 항목 선점(claim) — D-032.** 낙관적 락이 충돌을 *사후에 감지*한다면 선점은 *사전에 예방*한다. 둘이 함께 있어야 D-007 의 "경합 성격이 다르면 수단도 다르다"를 다시 주장할 수 있다. 5일 범위 밖이고, `service/`·`api/` 가 예정보다 빨리 끝나면 가장 먼저 붙인다.
>
> **같은 `normalized_key` 문의가 동시에 유입되면 AI 를 중복 호출할 수 있다.** 이건 락으로 막지 않고 **수용한다** — 막으려면 키 단위 직렬화가 필요한데, 그것은 접수 경로를 느리게 만들고 무엇보다 그룹핑으로 되돌아가는 길이다. 중복 호출은 절감률을 조금 떨어뜨릴 뿐 정확성을 해치지 않는다. 측정 6 에서 이 손실분을 함께 기록한다.
>
> **D-016 의 교훈은 폐기되지 않았다** — 제약 위반 예외를 같은 트랜잭션 안에서 캐치해 재조회하면 Hibernate 의 rollback-only 마킹 탓에 `UnexpectedRollbackException` 으로 실패한다. 향후 어떤 UNIQUE 제약을 도입하든 재시도는 **트랜잭션 밖**에 둔다.

### 캐시 전략

- 변경 빈도 << 조회 빈도 인 지점만
- **2단 절감 경로 (D-031)** — 그룹핑을 대체해 AI 호출을 줄이는 장치. 판정 단위는 문의 1건 그대로이고 **재사용하는 것은 AI 호출뿐이다**

  ```text
  @Async 분류 담당
    ├ 1단 캐시 조회 (normalized_key)   → hit  : AI 호출 없이 결과 재사용
    ├ 2단 DB 조회 (쿼리 2번 — 아래)    → hit  : AI 호출 없이 재사용 + 캐시 put
    └ miss                                   : AI 호출
  ```

  **2단 DB 조회의 우선순위 (D-033)** — 사람 노동까지 아끼는 자리다. **쿼리를 두 번 친다 (D-037)**

  | 순위 | 대상 | 이유 |
  | --- | --- | --- |
  | 1 | 사람이 확정한 것 (`final_category IS NOT NULL`) | 사람 답이 AI 답보다 신뢰도가 높다 |
  | 2 | 위가 비었을 때만 — AI 가 자동 확정한 것 (`verdict = AUTO_ACCEPTED`) | |
  | — | **재사용을 다시 재사용하지 않는다** | **체인 금지** — 원본이 틀렸을 때 어디까지 퍼졌는지 추적할 수 없다. 2순위의 `verdict = AUTO_ACCEPTED` 등치 조건이 `REUSED` 를 걸러낸다 |

  1순위가 없으면 같은 내용의 저확신 문의가 **사람이 아무리 확정해도 계속 큐에 쌓인다.** AI 호출만 아끼고 사람 노동은 하나도 못 아끼는 상태가 된다.

  > **1순위는 `verdict = REUSED` 행도 포함한다 (D-037).** 재사용 건에 `final_category` 가 있다는 것은 **감사로 뽑혀 사람이 다시 판단했다**는 뜻이므로, 참조하는 값은 재사용된 답이 아니라 사람이 새로 매긴 답이다 — 체인이 아니라 새 원본이다. 여기서 빼면 **감사가 잡아낸 정정이 재사용 경로에 못 들어간다.**
  >
  > **우선순위를 정렬식으로 쓰지 않는다.** `ORDER BY (final_category IS NOT NULL) DESC` 는 filesort 가 확정이고, `LIMIT 1` 로 최신 한 건만 뽑으면 사람 확정 행을 지나친다.

  - **캐시가 줄이는 것은 DB 조회이지 AI 호출이 아니다** (D-014). 캐시 miss 여도 DB 에 같은 키의 이전 결과가 있으면 AI 를 부르지 않는다
  - 따라서 `hit rate` 와 `AI 절감률` 은 **별개 메트릭으로 각각 노출**한다. hit rate 는 항상 절감률 이하다
  - **2단(DB)을 빼고 캐시만 두면 안 된다** — Redis 재시작 시 절감이 0 으로 리셋되고, 두 지표가 같은 값이 되어 D-014 가 무의미해진다
- **덮어쓰기 방향은 한 방향이다 (D-036)** — **사람 답은 AI 답을 덮고, AI 답은 사람 답을 덮지 않는다**
  - 사람이 확정하면(③) 그 답으로 캐시를 **덮어쓴다.** 안 덮으면 1단 hit 이 나는 동안 2단이 아예 실행되지 않아 **위 1순위 규칙이 실행될 기회조차 없다** — 감사가 잡아낸 정정을 시스템이 도로 무시하게 된다
  - ②의 put 은 기존 값이 사람 답(`source=HUMAN`)이면 덮지 않는다
  - **이 검사는 원자적으로 한다 (D-048).** GET → 판단 → SET 으로 쪼개면 그 사이에 ③이 넣은 사람 답을 ②가 덮는다 — D-036 이 막으려던 상황이 그대로 재현된다. Lua 스크립트 1개로 처리한다. **③의 put 은 조건 없이 덮으므로 그냥 SET 이다**
  - **`maxmemory` + `allkeys-lru` 로 상한을 둔다 (D-048).** 1단 캐시는 evict 가 없어 키가 무한히 쌓인다. **TTL 로 상한을 대신하지 않는다** — 캐시가 비는 시점이 시계에 달리면 측정 6·11 이 실행마다 다른 값을 낸다. 측정 11 에 `evicted_keys` 와 메모리 사용량을 함께 기록한다
  - **완벽한 원자성은 목표가 아니다.** 분류 담당이 AI 를 부르는 사이 사람이 확정하면 창이 남지만, 그 결과는 옛 AI 답이 잠시 더 재사용되는 것이고 재사용 건은 감사 대상이다 (D-033). 막으려고 키 단위 직렬화를 넣으면 그룹핑으로 되돌아간다
- **정규화 규칙** — 소문자화 · 연속 공백/문장부호 정리 · **주문번호·날짜·금액·연락처 마스킹**. 강도는 측정으로 확정한다 (D-031 재평가 조항)
  - **과도 병합**(서로 다른 문의가 한 키로) 과 **과소 병합**(같은 문의가 다른 키로) 을 **양쪽 다** 테스트 케이스로 만든다
  - **과도 병합이 더 위험하다** — 잘못된 분류가 재사용되면서 조용히 퍼진다. 정규화를 조일 때는 항상 이쪽을 먼저 확인한다
- `stats:summary` — TTL 10s + 큐 삽입/확정 시 `@CacheEvict(allEntries=true)`. Actuator gauge 가 매 스크랩마다 전수 count 치는 것 방지
  - **재사용 자동 확정에는 evict 를 걸지 않는다 (D-042).** 접수 경로마다 일어나 빈도가 높아서, 걸면 캐시가 상시 비어 있게 되고 "변경 빈도 << 조회 빈도" 전제가 무너진다. TTL 10s 가 이미 지연 상한이다
  - **큐 삽입/확정에 evict 가 붙은 이유는 빈도가 아니라 관측자다** — 그쪽은 사람이 방금 한 행동의 결과를 화면에서 확인하는 경로라 10초 지연이 "확정했는데 숫자가 안 변한다"로 보인다. 재사용 확정에는 그 자리에서 기다리는 사람이 없다
- **개인정보 마스킹은 저장하지 않고 내보낼 때 계산한다 (D-040).** 원문은 그대로 저장한다 — 상담원이 답변하려면 필요하다
  - `masked_content` 컬럼을 두지 않는 이유: **마스킹 강도는 측정으로 조일 예정**(D-031 재평가)인데, 저장해두면 규칙을 조여도 과거 행은 옛 마스킹 그대로 남는다. 백필을 빠뜨리면 이미 저장된 개인정보가 계속 응답으로 나간다
  - **AI 전송 전 마스킹과 같은 구현을 공유한다.** 두 벌이면 한쪽만 조여져서 화면에는 가려지는데 프롬프트에는 남는 상태가 된다
  - 비용은 측정 b(목록 p95)에 포함된다. 목표를 못 넘고 원인이 마스킹으로 지목되면 그때 컬럼 저장을 실측 근거로 다시 올린다

### AI 호출 규칙

- 프롬프트로 `{"category": ..., "confidence": 0.0~1.0}` JSON 강제
- **파싱 실패는 무조건 격리** (fail-safe 는 항상 격리 쪽). 단 이는 **판정 방향 지시이지 저장 값이 아니다** — `confidence` 컬럼에 `0` 을 쓰지 않는다 (D-022). 0 을 쓰면 측정 8ⓐ 의 최하위 신뢰도 구간에 "AI 가 0 이라 신고한 건"과 "응답이 깨진 건"이 섞여 오염된다
- **파싱이 됐어도 값을 검증한다 (D-034).** 아래 4개를 **임계값 비교 전에** 확인하고, 하나라도 위반하면 `FAILED` 로 본다

  | 검사 | 위반 예 |
  | --- | --- |
  | `confidence` 필드 존재 | 필드 자체가 없음 |
  | `0.0 <= confidence <= 1.0` | `1.5`, `-0.3` |
  | `category` 필드 존재 | 필드 자체가 없음 |
  | `category` 가 `InquiryCategory` 10종에 있음 | `REFUND_XYZ` |

  **범위 안으로 잘라 넣지 않는다.** `1.5 → 1.0` 으로 clamp 하면 측정 8ⓐ 의 최상위 구간에 "AI 가 1.0 이라 신고한 건"과 "1.5 를 잘라낸 건"이 섞인다 — D-022 가 `confidence 0` 을 거부한 것과 같은 논리다. `FAILED` 는 `category`·`confidence` 를 둘 다 null 로 남기므로 이상한 값이 측정에 흘러들 경로가 없다.

  **순서가 중요하다.** 검증을 임계값 비교 뒤에 두면 `1.5` 가 이미 자동 확정을 통과한 뒤가 되고, 그 건은 **자동 확정되면서 동시에 신뢰도 구간 집계에서 사라진다.**

  실패 사유(`PARSE_ERROR` / `OUT_OF_RANGE` / `UNKNOWN_CATEGORY` / `MISSING_FIELD` / `API_ERROR`)는 **구조화 로그**로 남긴다. 컬럼이나 enum 을 늘리지 않는다
- 파싱 실패도 `@Retryable` 재시도 대상. 3회 소진 시 `@Recover` 에서 `verdict=FAILED` (`category`·`confidence` 모두 null) + `CLASSIFY_FAILED` 로 큐 삽입. 조용히 삼키지 말 것
- **재사용된 분류 결과에는 `model` 에 출처(원본 결과 id)를 남긴다** — 어느 것이 실제 AI 호출이고 어느 것이 재사용인지 사후에 구분할 수 없으면 측정 6 과 8ⓑ 를 검산할 수 없다
- **자동으로 확정되는 것은 주체가 사람이어도 감사한다 (D-033).** 이 시스템의 주제는 "AI 를 믿지 않는다"가 아니라 **"자동으로 확정된 것을 믿지 않는다"** 이다. 사람이 확정한 답이라도 그것이 다른 문의로 **자동 전파**되는 순간 같은 검증이 필요하다 — 오히려 "사람이 정했다"는 사실이 신뢰의 근거가 되어 아무도 의심하지 않기 때문에 더 위험하다
- 문의 본문은 고객이 쓴 자연어라 **개인정보가 섞여 들어온다.** AI 로 보내기 전 정규화 단계의 마스킹을 거친다
- API key 는 환경변수만. 코드/설정 파일 하드코딩 금지

### 예외 처리 — Sentry 캡처 규칙 (D-030)

- catch 해서 로그만 찍고 끝내지 않는다 — 처리할 게 없으면 애초에 catch 하지 않는다 (자동 캡처됨). catch 했는데 아무것도 안 하면 자동도 수동도 아니라서 Sentry 가 영구히 모른다 (`SENTRY-GUIDE.md` 2-3, 실측 확인됨)
- 복구/재시도하다 최종 실패로 확정되는 지점에서는 `Sentry.captureException(e)` 를 명시적으로 부른다
- 판단 기준 전체 표는 `SENTRY-GUIDE.md` 2-4 참조 — 여기서 중복 안 함
- `api/` 착수 시 `@RestControllerAdvice` 같은 **공용 예외 처리 지점 1곳**을 먼저 만든다 — 이 규칙을 각자 흩어진 try-catch 마다 기억해서 지키게 하지 않고, 한 곳에서 강제되게 한다 (AI 코드리뷰 제안)

### 감사 샘플링 blind 규칙

- `GET /api/inquiry-review-queue` 는 **`reason` · `confidence` · `threshold` 를 파라미터로도 응답으로도 제공하지 않는다**. `category` 필터도 없다 — D-010
- 이유: `AUDIT_SAMPLE` 은 **정의상 `confidence >= threshold`** 다. 두 값을 주면 뺄셈 한 번으로 감사 표본이 100% 식별되므로 `reason` 만 가려도 소용없다
- `suggestedCategory` 는 남긴다 (가리면 `CLASSIFY_FAILED` 가 구별되고 검토 생산성도 떨어짐). 대신 **앵커링 편향이 남으므로 측정된 오분류율은 하한값**으로 해석한다
- 노출은 `GET /api/stats` (`ROLE_MANAGER`) 에서만
- 새 응답 필드를 추가할 때는 **"이 값만 보고 감사 표본을 알아낼 수 있나"** 를 먼저 확인한다

## 모듈 간 계약 (병렬 작업 기준선)

작업 패키지 **P1 접수·절감 경로(이용택) / P2 분류·검증(김준현) / P3 검토·관측(김은빈)** — D-015, 재배정은 D-031.
셋이 병렬로 가려면 **경계 3개만** 먼저 고정하면 된다. 이 계약을 바꾸는 변경은 세 담당자 합의 + `DECISIONS.md` 항목 필요.

**계약 A — `InquiryReceivedEvent` (P1 → P2)**

```text
InquiryReceivedEvent(inquiryId, normalizedKey, content)
  └ 문의 저장 트랜잭션 커밋 후 발행 (AFTER_COMMIT)
  └ 접수 전건에 발행한다. 그룹핑이 없으므로 "신규만 발행" 조건은 없다 (D-031)
  └ 절감 판단(캐시/DB hit 여부)은 발행 시점이 아니라 수신한 분류 담당이 한다
```

**계약 B — `inquiry_review_queue` 삽입 시 필수 컬럼 (P2 → P3)**

```text
inquiry_id, classification_result_id, reason, status=PENDING, created_at, version=0
  └ classification_result_id 는 세 reason 모두 반드시 존재 (FAILED 도 행은 남긴다)
  └ reason 판별 기준은 verdict 다. category=null 은 결과일 뿐 판별식이 아니다 — D-022
       LOW_CONFIDENCE  ← verdict=NEEDS_REVIEW   (category != null, confidence < threshold)
       CLASSIFY_FAILED ← verdict=FAILED         (category, confidence 모두 null)
       AUDIT_SAMPLE    ← verdict=AUTO_ACCEPTED  (confidence >= threshold)
                       ← verdict=REUSED         (감사로 뽑혔을 때만 — D-033)
  └ P3 는 reason 을 조회 응답에 노출하지 않는다 (blind, D-010)
```

> **`REUSED` 는 격리 사유가 아니다.** 재사용 건은 자동 확정되며 큐에 들어가지 않는다 — **감사로 뽑힐 때만** 들어가고 그때 사유는 기존과 같은 `AUDIT_SAMPLE` 이다. 계약 B 의 구조는 바뀌지 않고 `verdict → reason` 매핑에 한 줄이 늘 뿐이라, 판별식은 여전히 `verdict` 하나다 (D-033).

**계약 C — `classification:byNormalizedKey` 캐시 값 구조 (P1 읽기·쓰기 ↔ P2 쓰기 ↔ P3 쓰기)** — D-036 으로 개정

```text
key   : normalized_key (String)
value : { category, confidence, source, sourceResultId }
  └ source         : HUMAN | AI            — 캐시 단에서도 1순위/2순위를 구분한다
  └ sourceResultId : 원본 결과 id           — REUSED 행의 model 컬럼에 그대로 쓴다 (추적 경로)
       ⚠ 방금 만든 REUSED 행의 id 를 넣지 않는다. 넣으면 체인 금지가 캐시로 우회된다 (D-042)
       보장 지점은 조회 쪽이다 — 2순위가 verdict='AUTO_ACCEPTED' 등치라 REUSED 는 안 나오고,
       1순위가 주는 것은 사람 답이라 체인이 아니다. put 은 조회 결과를 옮길 뿐 판단하지 않는다
  └ confidence     : source=AI 면 원본 값 / source=HUMAN 이면 null (D-033)
  └ 판정이 확정된 결과만 담는다. 미판정 상태(NEEDS_REVIEW·FAILED)는 캐시하지 않는다
  └ inquiryId 를 담지 않는다 — 담으면 "이 문의의 판정"으로 오해돼 그룹핑처럼 쓰이게 된다
  └ 모델명을 담지 않는다 — 재사용 행의 model 은 모델명이 아니라 원본 id 다. 필요하면 원본을 따라간다

put   : 판정 행이 저장되는 트랜잭션의 AFTER_COMMIT, 그 두 자리뿐
          ② 커밋 후 — AUTO_ACCEPTED · REUSED   (원본이 사람 답이면 source=HUMAN 을 물려받는다)
          ③ 커밋 후 — 사람이 확정한 final_category (source=HUMAN, 조건 없이 덮는다)
        ⚠ "2단 hit 시에도 put" 은 별도 경로가 아니다 — 2단 hit 이면 REUSED 행을 저장하고 그게 곧 ②다
evict : 없음. TTL 은 메모리 상한 목적으로만 사용. 정확성은 evict 가 아니라 ③의 덮어쓰기가 지킨다
```

> **P3 가 캐시를 건드리는 것은 D-036 이 처음이다.** ③ 이 캐시를 갱신하지 않으면 사람이 정정한 답을 두고 1단이 옛 AI 답을 계속 돌려준다.

## 작업 경계

- 미션 외 디렉토리 수정 금지
- `src/main/` 외는 별도 PR — `docs/`, `tests/e2e/`, `.github/`
- `API-CONTRACT.md` 는 코드 PR 에 **동반 변경만** 허용 (계약과 구현의 괴리 방지)
- 기획 단계 산출물(`PRD.md`, `DECISIONS.md`)은 단독 PR 허용 — D-009
- 비밀 정보 (`.env`, JWT secret, Anthropic API key) commit 절대 금지

## 출력 형식

- 답을 통째로 주지 말고 단계별 reasoning 먼저
- API 변경은 반드시 `API-CONTRACT.md` 업데이트 동반
- 측정 결과는 본인 실측만 사용 (AI 추정값 금지)

### 문서 작성 규칙 — 신입 백엔드 개발자 기준으로 쓴다

문장을 있어 보이게 만들면 정작 읽어야 할 팀원이 걸러진다. **비개발자가 「한 줄 소개」와 「핵심 흐름」만 읽어도 무엇을 만드는지 알 수 있어야 한다.**

1. **현학적인 표현·불필요한 영어·약어·고급 설계 용어를 쓰지 않는다.** 짧고 구체적으로 쓴다
2. 기술 용어가 꼭 필요하면 **쉬운 한국어를 먼저 쓰고 괄호에 용어**를 넣는다
   - ✅ "목록 1번 + 항목마다 1번씩 쿼리가 더 나가는 문제(N+1)"
   - ❌ "N+1 문제를 `@EntityGraph` 로 해소한다"
3. 기능 설명은 **문제 → 왜 필요한가 → 어떻게 만드나 → 어떻게 확인하나** 순서로 통일한다
4. **안 만든 것을 만든 것처럼 쓰지 않는다.** 미착수 범위는 「지금 상태」에 명시한다
5. **재보지 않은 성능 수치를 만들지 않는다.** 목표치는 목표라고 밝히고, 모르는 값은 "재보기 전에는 알 수 없다"고 쓴다
6. `PRD.md` 에는 **5일 안에 만들 범위만** 남긴다. 설계 논쟁과 미래 확장은 `DECISIONS.md` 또는 「나중에 할 것」 표로 옮긴다
7. 새 기능·기술을 문서에 임의로 추가하지 않는다 (추가하려면 `DECISIONS.md` 항목 먼저)

> 그림은 예외다. **흐름도·시퀀스 다이어그램·상태 전이도는 글보다 쉬우므로 적극적으로 쓴다.** 단 라벨은 한국어로.

## AI 검증 규칙 (책 11장 4비법)

- AI 가 만든 응답시간/throughput 추정 수치는 evidence 사용 금지 — 본인 hey/wrk 측정만
- AI hallucination 사례는 즉시 `evidence/failure-cases.md` 에 추가
- 모든 prompt 는 페·목·형·제 4 요소 강제 (페르소나·목표·형식·제약)

## 라이프사이클 단계 책임자

각 단계는 책임자 1명이 산출물 끝까지 책임. 단계 간 협업은 PR 본문 + `INTEGRATION-LOG.md` 로 동기화.
분담 확정: `DECISIONS.md` **D-020 (채택)**. 진행 상태는 `LIFECYCLE-COVERAGE.md` 매트릭스.

| 단계 | 책임자 | 도구 |
| --- | --- | --- |
| 기획 | 김준현 | Jira MCP, AI PRD |
| 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI |
| 테스트 | 이용택 | Playwright MCP |
| 리뷰 | 김은빈 | Claude GitHub Actions, CodeRabbit |
| 배포·운영 | 이용택 | Sentry MCP, Docker |

인원 3명 / 단계 5개라 이용택이 2단계를 겸한다. **코딩은 전원 공동**이고, 코드 범위는 위 「모듈 간 계약」의 P1/P2/P3 로 나눈다.

## 변경 절차

이 헌법을 바꾸는 결정은 모두 `DECISIONS.md` 에 새 항목으로 추가. 헌법 그대로 덮어쓰기 금지.
