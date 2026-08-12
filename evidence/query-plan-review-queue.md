# 검토 큐 조회 — 실행 계획 실측 (측정 5, TRI-56)

> `GET /api/inquiry-review-queue` (`InquiryReviewQueueRepository.search`)가 `V2` 마이그레이션의
> `idx_irq_status_created (status, created_at)` 인덱스를 실제로 타는지 확인한 값이다.
> AI 추정이 아니라 **실 MySQL(Testcontainers)에서 `EXPLAIN`을 직접 돌린 값**이다
> (CLAUDE.md 「AI 검증 규칙」).

## 왜 데이터 규모를 바꿔가며 쟀나

작은 테이블에서는 MySQL 옵티마이저가 인덱스를 사용하는 것보다 테이블 전체를 스캔하는 것이
더 저렴하다고 판단할 수 있다. 이는 버그가 아니라 정상적인 최적화 동작이다. 따라서 인덱스가
생성되어 있다는 이유만으로 실제 쿼리에서 인덱스를 사용한다고 가정해서는 안 되며, `EXPLAIN`
등의 실행 계획과 실제 실행 결과를 통해 확인해야 한다.

## 실측 값 (2026-08-07)

쿼리(둘 다 `status='PENDING'`, `from`/`to` 미지정, `ORDER BY created_at ASC LIMIT 20` —
JPQL `(:from is null or ...)` 이 실제로 바인드하는 모양을 그대로 재현):

| 데이터 규모 | type | possible_keys | key | rows | Extra |
| --- | --- | --- | --- | --- | --- |
| 30건 | `ALL` | `idx_irq_status_created` | `null` (미사용) | 1 | `Using where; Using filesort` |
| 1,000건 | `range` | `idx_irq_status_created` | `idx_irq_status_created` | 1000 | `Using index condition` |

## 판정

- **30건에서는 인덱스를 안 탄다** — `key=null`, `Using filesort`(전체 데이터를 메모리에서
  정렬하는 방식 — 인덱스 순서를 못 써서 따로 정렬해야 한다는 뜻). 옵티마이저의 정상 판단이라
  인덱스나 쿼리 결함이 아니다.
- **1,000건(현실적인 규모)에서는 인덱스를 탄다** — `key=idx_irq_status_created`,
  `Using index condition`(조건 일부를 인덱스 단계에서 먼저 걸러 스토리지 접근을 줄이는 방식)이고
  **`Using filesort`가 없다**(따로 정렬 안 하고 인덱스 순서를 그대로 씀).
  CLAUDE.md 표(76번째 줄)가 예상한 `filesort` 없음은 일치하지만, **`type`은 예상한
  `ref`(등치 조건으로 인덱스를 정확히 찾는 방식)가 아니라 `range`(인덱스의 일정 구간을 훑는
  방식)로 나왔다** — `status='PENDING'` 등치 조건만 있으면 `ref`가 맞는데, 실제 JPQL은
  `(:from is null or created_at >= :from) and (:to is null or created_at <= :to)`로
  `created_at`에 부등호 조건이 함께 걸려 있어 옵티마이저가 이를 범위 스캔으로 판단했다.
  인덱스를 실제로 쓰고 filesort가 없다는 핵심 결론에는 영향 없지만, `type` 하나는 예상표와
  어긋난 값이라 그대로 남긴다.
- **결론**: 인덱스 설계(`(status, created_at)`)는 유효하다.
- **CI 제외 이유**: 1,000건 seed가 무겁고, 데이터 분포·MySQL 버전에 따라 결과가 달라질 수 있어
  불안정한 테스트(flaky test)가 되기 쉽다.
- **대안**: 스키마에 인덱스가 실제로 존재하는지만 가볍게 상시 검증한다
  (`InquiryReviewQueueRepositoryTest.statusCreatedIndexExistsOnSchema`).
- **한계**: 데이터가 1,000건 미만인 초기 운영 환경에서는 인덱스가 사용되지 않을 수 있다(30건
  실측 참조).

## 재현법

```bash
./gradlew test --tests "*InquiryReviewQueueRepositoryTest"
```

1,000건 대량 seed는 재현 시점에 필요하면 raw JDBC 배치 insert로 임시로 채워서 확인한다
(커밋된 테스트에는 없음 — 위 판정 참조).

## 10만 건 — 1페이지 vs 500페이지 (측정 5ⓕ, TRI-87)

> 위 판정까지는 앞 몇 페이지(사실상 1페이지)만 봤다. 인덱스를 타고 filesort가 없다는 결론은
> 맞지만, **뒤쪽 페이지를 볼 때도 그대로인지는 따로 재야 안다** — 인덱스가 있어도 "앞부분을 읽고
> 버리는" 비용은 막아주지 않기 때문이다 (CLAUDE.md).

### 실측 값 (2026-08-13, rows=100,000)

쿼리는 둘 다 `status='PENDING' ORDER BY created_at ASC` (컨트롤러 기본 페이지 크기 20 기준,
500페이지 = `LIMIT 9980,20`). **인덱스(`idx_irq_status_created`)를 잠깐 `DROP`했다 복구하며
있을 때/없을 때를 같은 실행에서 나란히 쟀다** (재시드 없이, 데이터는 그대로 두고 인덱스만 뺐다
붙임).

| | 1페이지 (`LIMIT 0,20`) | 500페이지 (`LIMIT 9980,20`) |
| --- | --- | --- |
| **인덱스 있음** — type/key | `ref` / `idx_irq_status_created` | `ref` / `idx_irq_status_created` |
| **인덱스 있음** — 실제 걸린 시간 | 0.68ms | 9.73ms |
| **인덱스 있음** — 실제로 읽은 행 | 20건 | 10,000건 |
| **인덱스 없음** — type/Extra | `ALL` / `Using where; Using filesort` | `ALL` / `Using where; Using filesort` |
| **인덱스 없음** — 실제 걸린 시간 | 53.8ms | 77.2ms |
| **인덱스 없음** — 실제로 읽은 행 | 100,000건(스캔) | 100,000건(스캔) |

### 판정

- **인덱스가 있으면**: `EXPLAIN`(계획)만 보면 1페이지와 500페이지가 완전히 동일하다(`type`·`key`
  같음). 계획만 봐서는 "뒤로 갈수록 느려진다"는 안 보이고, `EXPLAIN ANALYZE`(실제 실행)를 떠야
  드러난다 — 500페이지는 20건을 돌려주기 위해 앞의 9,980건 + 20건, 총 1만 건을 실제로 읽고
  그중 20건만 남기고 버린다. 그 결과 1페이지 대비 **약 14배** 느려진다(0.68ms → 9.73ms).
- **인덱스가 없으면**: `type=ALL`(풀 스캔) + `Using filesort` — 매 요청마다 10만 건 전체를 스캔하고
  정렬한다. 이때는 1페이지(53.8ms)와 500페이지(77.2ms) 차이가 상대적으로 작다(1.4배) — 오프셋
  비용이 없어서가 아니라, **풀 스캔+정렬이라는 고정비용 자체가 너무 커서 오프셋 비용이 묻힌다.**
- **인덱스 있음 vs 없음**: 1페이지 기준 약 **80배**(0.68ms → 53.8ms), 500페이지 기준 약 **8배**
  (9.73ms → 77.2ms) 인덱스 있는 쪽이 빠르다. 인덱스가 절대적으로 벌어주는 이득은 오프셋이
  작을수록(앞 페이지일수록) 더 크다 — 오프셋이 커지면 인덱스를 타도 "읽고 버리는" 비용이 늘어나
  격차가 좁혀지기 때문이다.
- **결론**: 인덱스(`idx_irq_status_created`)는 10만 건 규모에서도 확실히 유효하다. 다만 인덱스가
  있어도 깊은 오프셋 비용 자체는 없어지지 않고, 오프셋이 더 커지면(더 뒤 페이지) 이 시점(500페이지)
  의 14배 격차보다 더 벌어질 수 있다 — 여기까지만 쟀고 그 이상은 추정하지 않는다.
- **한계**: 큐 항목이 전부 같은 `inquiry_id`/`classification_result_id`를 가리키도록 시드해
  `EXPLAIN` 계획만 확인했다 — 목록 응답까지 재려면(항목마다 다른 문의를 가리키는 경우의
  `@EntityGraph` 조인 비용은) 별도다. 절대 시간(ms)은 실행 환경(이번 측정은 Windows + Docker
  Desktop)에 따라 흔들릴 수 있으니, **상대 비교(몇 배 차이)를 결론의 근거로 삼는다.**

### 재현법

```bash
$env:EXPLAIN_MEASURE = 'true'   # PowerShell. bash 는 EXPLAIN_MEASURE=true
./gradlew test --tests '*ReviewQueueDeepPageExplainIT'
```

결과는 `build/review-queue-deep-page-report.md`에 남는다 (커밋 대상 아님 — `build/` 산출물).
10만 건 시드는 `inquiry_review_queue`에 이미 10만 건 이상 있으면 건너뛴다(`@BeforeEach` 확인).
인덱스는 테스트 안에서 `DROP` 후 `finally` 로 반드시 복구하므로 테스트가 끝나면 스키마는
원래대로다.
