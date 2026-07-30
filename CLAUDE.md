# CLAUDE.md — 팀 헌법

> 이 파일은 모든 prompt 에 자동으로 포함되며 위반 시 hook 이 차단합니다.
> 변경은 반드시 `DECISIONS.md` 새 항목으로. 덮어쓰기 금지.
> 상세 요구사항은 `PRD.md`, endpoint 계약은 `API-CONTRACT.md`.

## 도메인 컨텍스트

### 시스템

- 에러를 **동일 그룹 단위로 묶어** AI가 자동 분류하고, **신뢰도가 카테고리별 임계값 미만이면 격리**하며, **자동 승인된 결과도 일부를 무작위 감사**하는 검증 파이프라인
- 페르소나: 클라이언트 SDK (`ROLE_INGEST`) / 검토자 (`ROLE_REVIEWER`) / 관리자 (`ROLE_ADMIN`)
- 핵심 흐름: 에러 수신 → fingerprint 그룹핑 (신규만 AI 호출) → AI 분류 → **카테고리별 임계값 검증** → 자동 승인 or 검토 큐 격리 → 사람 확정 → 캐시 갱신
- 감사 경로: 자동 승인 건 5% 무작위 → 검토 큐 (`AUDIT_SAMPLE`) → 사람 확정 → `category ≠ final_category` 면 오분류 1건 집계

**이 시스템의 존재 이유**: AI가 스스로 신고한 신뢰도를 무조건 믿지 않는다. 신뢰도 검증(1차) + 감사 샘플링(2차) 두 겹.

### 도메인 모델

```text
ErrorEvent(id, error_group_id, raw_message, stack_trace, source, occurred_at, created_at)
  └ 테이블명은 errors. append-only 발생 로그. 상태 없음.

ErrorGroup(id, fingerprint UNIQUE, sample_message, status, occurrence_count,
           current_category, current_confidence,
           first_seen_at, last_seen_at, created_at, updated_at)
  └ 분류의 단위. status: NEW | CLASSIFIED | UNCLASSIFIED
  └ current_* 는 classification_result 의 역정규화 사본 (D-011). 목록 조회 조인 제거용

ClassificationResult(id, error_group_id, category, confidence, model, raw_response,
                     verdict, final_category, attempt_count, created_at)
  └ verdict: AUTO_ACCEPTED | NEEDS_REVIEW | FAILED
  └ category = AI 제안, final_category = 사람 확정. 둘 다 보존 (덮어쓰기 금지)

ReviewQueueItem(id, error_group_id, classification_result_id, reason, status,
                reviewer_id, resolved_at, created_at, version)
  └ reason: LOW_CONFIDENCE | CLASSIFY_FAILED | AUDIT_SAMPLE
  └ status: PENDING | RESOLVED

ClassificationPolicy(category PK, threshold, updated_by, updated_at)
  └ 미등록 카테고리는 기본값 0.9 fallback (보수적 = 격리 쪽으로 실패)

ErrorCategory (10종): DB_CONNECTION, DB_QUERY, TIMEOUT, AUTH, VALIDATION,
                      EXTERNAL_API, NULL_REFERENCE, OUT_OF_MEMORY, SERIALIZATION, CONFIG
```

**불변 규칙 4개**

1. 상태(`status`)는 `ErrorGroup` 이 소유한다. `ErrorEvent` 는 상태를 갖지 않는다 (같은 에러 1000번 = 판정 1번). — D-004
2. `final_category` 기록 시 `category` 를 덮어쓰지 않는다. 덮어쓰면 오분류 증거가 사라진다.
3. `UNCLASSIFIED → CLASSIFIED` 전이는 **사람만** 일으킨다. AI 에게 이 전이 권한 없음.
4. `ErrorGroup.current_*` 는 역정규화 사본이므로 **판정이 확정되는 트랜잭션(②③) 안에서만** 갱신한다. 다른 경로에서 손대면 원본과 어긋난다. — D-011

### 핵심 쿼리 + 인덱스

| 쿼리 | 인덱스 | 예상 EXPLAIN |
| --- | --- | --- |
| `GET /api/review-queue` — status + 기간 필터 + `created_at ASC` (오래된 순) | `(status, created_at)` | `ref` + 인덱스 순서로 정렬, filesort 없음 |
| fingerprint 로 그룹 조회 (수신 경로, 최고 빈도) | `fingerprint` UNIQUE | `const` / `eq_ref` |
| `GET /api/error-groups?sort=occurrenceCount` — status + category 필터 | `(status, current_category, occurrence_count DESC)` | `ref`, 정렬까지 커버 |
| `GET /api/error-groups?sort=lastSeenAt` — 기간 범위 + 최근 순 | `(status, last_seen_at)` | `range`, 정렬까지 커버 |
| 기간 범위 + `occurrence_count DESC` 동시 사용 | **커버 불가** | 범위 + 다른 컬럼 정렬을 한 B-tree 로 동시 충족 못 함 → filesort. **기간 필터를 쓰면 `sort=lastSeenAt` 을 택한다** (D-012) |
| 그룹별 최신 분류 결과 | `(error_group_id, created_at DESC)` | `ref` |
| 감사 대조 — `verdict=AUTO_ACCEPTED AND final_category IS NOT NULL` 신뢰도 구간별 집계 | `(verdict, confidence)` | `range` |

> 4행(커버 불가)은 결함이 아니라 **B-tree 의 구조적 한계**다. 측정 5번(인덱스 EXPLAIN 비교)에서 이 예상이 맞는지 실측으로 확인하고, 틀렸으면 `evidence/` 에 기록한다.
> 검토 큐에 `category` 필터가 없는 이유는 blind 규칙 — 아래 참조.

## 6 공통 필수 기능 매핑

| 기능 | 본 시스템 매핑 | 코드 위치 |
| --- | --- | --- |
| 권한·역할 | 3역할 분리 — 전송 주체 ≠ 판정 주체 ≠ 정책 결정 주체 | `config/SecurityConfig.java` |
| 핵심 트랜잭션 | ① 그룹 upsert + 로그 삽입 + 카운트 증가<br>② 분류 결과 저장 + 그룹 전이 + 조건부 큐 삽입 (**감사 표본 삽입 포함** — D-012)<br>③ 큐 확정 + `final_category` 기록 + 그룹 전이 | ① `service/ErrorIngestService.ingest`<br>② `service/ClassificationService.verifyAndPersist`<br>③ `service/ReviewService.confirm` |
| 검색·필터 | 큐 복합 필터 + 페이징 | `domain/repository/ReviewQueueRepository.search` |
| 캐시 | ① `fingerprint → 그룹 요약` (수신 경로 DB 조회 제거 — 시스템 최고 QPS)<br>② 적체·감사 요약 통계 (TTL 10s) | ① `service/ClassificationCache`<br>② `service/StatsService.summary` |
| 비동기·이벤트 | `ErrorGroupCreatedEvent` → `@Async` 워커 + `@Retryable(3)` + `@Recover` | `service/AiClassifyWorker`, `service/event/` |
| AI 보조 | 분류 + **임계값 검증** + **감사 샘플링** | `service/AiClassificationService`, `service/PolicyService`, `service/AuditSamplingPolicy` |

## 코딩 규칙

### 3계층 분리

- `api/` — HTTP 입출력만. DTO 변환까지. 도메인 객체를 그대로 반환 금지
- `service/` — 비즈니스 흐름 + 트랜잭션 경계
- `domain/` — 도메인 객체 + `repository/` 저장만

### `@Transactional` 위치

- Controller 절대 X
- 단일 read 도 X (cost > benefit)
- 묶음 read+write 만 — 위 표의 ①②③ **세 메서드**. 여기 밖에 새로 붙이려면 근거를 PR 본문에 쓴다
- **측정 장치를 트랜잭션 밖에 두지 않는다.** 감사 표본 큐 삽입은 ② 안에 포함한다 — 누락되면 감사율이 설정값 미달이 되어 측정 8 의 분모가 조용히 줄어든다. "부차적이라 분리한다"는 판단은 외부 의존성에만 적용한다 (D-012)
- **캐시 갱신/evict 는 커밋 후** (`@TransactionalEventListener(AFTER_COMMIT)`). 롤백된 판정이 캐시에 남으면 안 됨

### LAZY 기본

- `@ManyToOne` / `@OneToMany` 모두 LAZY
- **알려진 N+1 지점**: `GET /api/review-queue` 에서 항목별 `ErrorGroup` + `ClassificationResult` 접근 → `@EntityGraph` 필수. 적용 후 쿼리 수 evidence 남김

### 락·동시성 전략 (3분할 — D-007)

경합 성격이 달라서 수단도 다르다. 하나로 통일하려 하지 말 것.

| 지점 | 수단 | 이유 |
| --- | --- | --- |
| `occurrence_count` 증가 (최고 빈도) | **JPQL 원자적 UPDATE** (`SET occurrence_count = occurrence_count + 1`) | 락 없이 DB 원자성 사용. 여기에 비관적 락 걸면 수신 경로 전체가 직렬화됨 |
| 신규 그룹 동시 생성 | **`fingerprint` UNIQUE + 위반 예외 캐치 후 재조회** | 선-조회 후-삽입만으로는 못 막는다. 락 아님 |
| 큐 항목 중복 확정 | **낙관적 락 `@Version`** → 충돌 시 409 | 두 검토자가 같은 항목을 집는 빈도가 낮음. 비관적 락은 과잉 |

- 사용자 규모 / 충돌 빈도 측정 후 재평가 (`DECISIONS.md` 후속 항목)

### 캐시 전략

- 변경 빈도 << 조회 빈도 인 지점만
- `classification:byFingerprint` — 수신 경로에서 조회. 값 구조는 **계약 C** 참조
  - **캐시가 줄이는 것은 DB 조회이지 AI 호출이 아니다** (D-014). 캐시 miss 여도 DB 에 그룹이 있으면 AI 를 부르지 않는다 — AI 절감을 만드는 건 그룹핑이다
  - 따라서 `hit rate` 와 `AI 절감률` 은 **별개 메트릭으로 각각 노출**한다. hit rate 는 항상 절감률 이하다
  - 정당화 근거: fingerprint→그룹 매핑은 그룹 생성 후 거의 불변이고 수신 요청마다 조회된다 = 변경 빈도 << 조회 빈도 조건에 가장 잘 맞는 지점
- `stats:summary` — TTL 10s + 큐 삽입/확정 시 `@CacheEvict(allEntries=true)`. Actuator gauge 가 매 스크랩마다 전수 count 치는 것 방지

### AI 호출 규칙

- 프롬프트로 `{"category": ..., "confidence": 0.0~1.0}` JSON 강제
- **파싱 실패 = `confidence 0` 으로 간주해 무조건 격리** (fail-safe 는 항상 격리 쪽)
- 재시도 3회 소진 시 `@Recover` 에서 `CLASSIFY_FAILED` 로 큐 삽입. 조용히 삼키지 말 것
- API key 는 환경변수만. 코드/설정 파일 하드코딩 금지

### 감사 샘플링 blind 규칙

- `GET /api/review-queue` 는 **`reason` · `confidence` · `threshold` 를 파라미터로도 응답으로도 제공하지 않는다**. `category` 필터도 없다 — D-010
- 이유: `AUDIT_SAMPLE` 은 **정의상 `confidence >= threshold`** 다. 두 값을 주면 뺄셈 한 번으로 감사 표본이 100% 식별되므로 `reason` 만 가려도 소용없다
- `suggestedCategory` 는 남긴다 (가리면 `CLASSIFY_FAILED` 가 구별되고 검토 생산성도 떨어짐). 대신 **앵커링 편향이 남으므로 측정된 오분류율은 하한값**으로 해석한다
- 노출은 `GET /api/stats` (ADMIN) 에서만
- 새 응답 필드를 추가할 때는 **"이 값으로 감사 표본을 역산할 수 있나"** 를 먼저 확인한다

## 모듈 간 계약 (병렬 작업 기준선)

작업 패키지 P1(수신·그룹핑) / P2(분류·검증) / P3(검토·관측)가 병렬로 가려면 **경계 3개만** 먼저 고정하면 된다.
이 계약을 바꾸는 변경은 세 패키지 담당자 합의 + `DECISIONS.md` 항목 필요.

**계약 A — `ErrorGroupCreatedEvent` (P1 → P2)**

```text
ErrorGroupCreatedEvent(errorGroupId, fingerprint, sampleMessage, stackTrace)
  └ 신규 그룹 생성 트랜잭션 커밋 후 발행 (AFTER_COMMIT)
  └ 기존 그룹 재발 시에는 발행하지 않는다
```

**계약 B — `review_queue` 삽입 시 필수 컬럼 (P2 → P3)**

```text
error_group_id, classification_result_id, reason, status=PENDING, created_at, version=0
  └ reason 별 보장: LOW_CONFIDENCE / AUDIT_SAMPLE 은 classification_result_id 반드시 존재
                    CLASSIFY_FAILED 는 classification_result.category = null
  └ P3 는 reason 을 조회 응답에 노출하지 않는다 (blind, D-010)
```

**계약 C — `classification:byFingerprint` 캐시 값 구조 (P1 읽기 ↔ P2 쓰기)**

```text
key   : fingerprint (String)
value : { groupId, status, currentCategory, currentConfidence }
  └ groupId 는 필수 — hit 시 errors INSERT + occurrence_count++ 에 필요
  └ currentCategory / currentConfidence 는 미판정(status=NEW) 이면 null

put   : ① 그룹 생성 트랜잭션 커밋 후 (status=NEW) — 분류 대기 중 폭주 유입 흡수
        ② 판정 확정 트랜잭션(②③) 커밋 후 재 put — status / current_* 갱신
evict : 없음 (그룹은 삭제되지 않음). TTL 은 메모리 상한 목적으로만 사용
```

## 작업 경계

- 미션 외 디렉토리 수정 금지
- `src/main/` 외는 별도 PR — `docs/`, `tests/e2e/`, `.github/`
- `API-CONTRACT.md` 는 코드 PR 에 **동반 변경만** 허용 (계약과 구현의 괴리 방지)
- 기획 단계 산출물(`PRD.md`, `DECISIONS.md`)은 단독 PR 허용 — D-009
- 비밀 정보 (`.env`, JWT secret, OpenAI API key) commit 절대 금지

## 출력 형식

- 답을 통째로 주지 말고 단계별 reasoning 먼저
- API 변경은 반드시 `API-CONTRACT.md` 업데이트 동반
- 측정 결과는 본인 실측만 사용 (AI 추정값 금지)

## AI 검증 규칙 (책 11장 4비법)

- AI 가 만든 응답시간/throughput 추정 수치는 evidence 사용 금지 — 본인 hey/wrk 측정만
- AI hallucination 사례는 즉시 `evidence/failure-cases.md` 에 추가
- 모든 prompt 는 페·목·형·제 4 요소 강제 (페르소나·목표·형식·제약)

## 라이프사이클 단계 책임자

각 단계는 책임자 1명이 산출물 끝까지 책임. 단계 간 협업은 PR 본문 + `INTEGRATION-LOG.md` 로 동기화.
분담 확정은 `DECISIONS.md` **D-002 (현재 보류 — 팀 합의 대기)**.

| 단계 | 책임자 | 도구 |
| --- | --- | --- |
| 기획 | (D-002 확정 전) | Jira MCP, AI PRD |
| 코딩 | 팀 전원 | claude.md, Commands, Hooks, gh CLI |
| 테스트 | (D-002 확정 전) | Playwright MCP |
| 리뷰 | (D-002 확정 전) | Claude GitHub Actions |
| 배포·운영 | (D-002 확정 전) | Sentry MCP, Docker |

## 변경 절차

이 헌법을 바꾸는 결정은 모두 `DECISIONS.md` 에 새 항목으로 추가. 헌법 그대로 덮어쓰기 금지.
