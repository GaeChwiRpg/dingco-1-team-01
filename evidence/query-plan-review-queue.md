# 검토 큐 조회 — 실행 계획 실측 (측정 5, TRI-56)

> `GET /api/inquiry-review-queue` (`InquiryReviewQueueRepository.search`)가 `V2` 마이그레이션의
> `idx_irq_status_created (status, created_at)` 인덱스를 실제로 타는지 확인한 값이다.
> AI 추정이 아니라 **MySQL에서 `EXPLAIN`을 직접 실행한 결과**다
> (CLAUDE.md 「AI 검증 규칙」).

## 핵심 결론

- 인덱스 설계(`(status, created_at)`)는 유효하다 — **1,000건 규모에서 인덱스를 타고 filesort가 없다.**
- 30건처럼 작은 테이블에서는 옵티마이저가 정상 판단으로 풀 스캔을 선택한다(버그 아님). **초기
  운영 환경에서는 인덱스가 안 쓰일 수 있다는 점이 한계**다.

## 데이터 규모를 바꿔가며 측정한 이유

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

### 핵심 결론

- 10만 건 규모에서도 인덱스(`idx_irq_status_created`)는 확실히 유효하다 — **1페이지 기준 80배,
  500페이지 기준 8배** 차이.
- 단, 인덱스가 있어도 **깊은 오프셋 비용("앞부분을 읽고 버리는" 비용)은 사라지지 않는다** —
  500페이지에서는 20건 반환에 1만 건을 읽는다.
- 결론의 근거는 절대 시간(ms)이 아닌 **상대 비교(몇 배 차이)**다(Windows + Docker Desktop
  환경 영향).

### 실측 값 (2026-08-13, rows=100,000)

쿼리는 둘 다 `status='PENDING' ORDER BY created_at ASC` (컨트롤러 기본 페이지 크기 20 기준,
500페이지 = `LIMIT 9980,20`). **인덱스(`idx_irq_status_created`)를 잠깐 `DROP`했다 복구하며
있을 때/없을 때를 같은 실행에서 나란히 쟀다** (재시드 없이, 데이터는 그대로 두고 인덱스만 뺐다
붙임).

| 조건 | 항목 | 1페이지 (`LIMIT 0,20`) | 500페이지 (`LIMIT 9980,20`) |
| --- | --- | --- | --- |
| 인덱스 있음 | type / key | `ref` / `idx_irq_status_created` | `ref` / `idx_irq_status_created` |
| 인덱스 있음 | 실제 걸린 시간 | 0.68ms | 9.73ms |
| 인덱스 있음 | 실제로 읽은 행 | 20건 | 10,000건 |
| 인덱스 없음 | type / Extra | `ALL` / `Using where; Using filesort` | `ALL` / `Using where; Using filesort` |
| 인덱스 없음 | 실제 걸린 시간 | 53.8ms | 77.2ms |
| 인덱스 없음 | 실제로 읽은 행 | 100,000건(스캔) | 100,000건(스캔) |

### 판정

1. **인덱스가 있을 때 — `EXPLAIN`만으로는 깊은 페이지의 실제 읽기 비용이 드러나지 않는다.**
   `EXPLAIN`(실행 계획)만 보면 1페이지와 500페이지가 모두
   `type=ref`, `key=idx_irq_status_created`로 동일하다. 따라서 계획만으로는
   "뒤 페이지로 갈수록 실제 읽는 행이 많아진다"는 사실이 드러나지 않는다.
   `EXPLAIN ANALYZE`로 실제 실행을 확인하면, 500페이지는 `LIMIT 9980,20`을 처리하기 위해
   앞의 9,980건을 건너뛴 뒤 20건을 반환하므로 총 약 10,000건을 실제로 읽는 것이 확인된다.
   그 결과 1페이지 대비 **약 14배** 느려졌다(0.68ms → 9.73ms).

2. **인덱스가 없을 때 — 풀 스캔 + 정렬 비용이 크기 때문에 OFFSET에 따른 차이가 상대적으로 묻힌다.**
   `type=ALL` + `Using filesort`로 매 요청마다 10만 건을 전체 스캔하고 정렬한다.
   따라서 1페이지(53.8ms)와 500페이지(77.2ms)의 차이는 약 **1.4배**에 그친다.
   이는 OFFSET 비용이 없어서가 아니라, **풀 스캔 + 정렬이라는 큰 고정비용이 이미 발생하기
   때문에 추가 OFFSET 비용의 영향이 상대적으로 작게 보이는 것**이다.

3. **인덱스 있음 vs 없음 — 앞 페이지일수록 인덱스의 상대적 이점이 크게 나타난다.**
   - 1페이지: **약 80배** 빠름 (0.68ms → 53.8ms)
   - 500페이지: **약 8배** 빠름 (9.73ms → 77.2ms)
   
   인덱스가 있으면 정렬 없이 `created_at` 순서대로 필요한 행을 읽을 수 있지만,
   깊은 페이지에서는 앞쪽 행을 읽고 버려야 하는 OFFSET 비용이 추가된다.
   따라서 페이지가 깊어질수록 인덱스를 사용하더라도 조회 시간이 증가하고,
   인덱스 유무에 따른 상대적 격차도 좁아진다.

4. **결론과 한계**
   `idx_irq_status_created (status, created_at)` 인덱스는 **10만 건 규모에서도 유효하다.**
   특히 1페이지에서는 인덱스가 없는 경우보다 약 80배 빠르고, 500페이지에서도 약 8배 빠르다.
   다만 인덱스가 OFFSET 비용까지 제거해주는 것은 아니다. 더 깊은 페이지에서는
   `LIMIT offset, size` 특성상 앞쪽 행을 읽고 버리는 비용이 계속 증가할 수 있다.
   **이번 측정은 500페이지까지 확인했으며, 그 이후의 수치는 추가 측정 없이 추정하지 않는다.**

   - **한계①**: 큐 항목이 전부 같은 `inquiry_id`/`classification_result_id`를 가리키도록
     시드하여 DB 실행 계획과 읽기 비용을 확인했다. 실제 목록 응답에서 항목마다 서로 다른
     문의·분류 결과를 조회하는 경우의 `@EntityGraph` 조인 비용까지는 별도 측정이 필요하다.
   - **한계②**: 절대 시간(ms)은 Windows + Docker Desktop 등 실행 환경에 따라 달라질 수 있다.
     따라서 성능 판단은 절대 시간 자체보다 **인덱스 유무에 따른 상대적 차이**를 중심으로 한다.

### 재현법

```bash
$env:EXPLAIN_MEASURE = 'true'   # PowerShell. bash 는 EXPLAIN_MEASURE=true
./gradlew test --tests '*ReviewQueueDeepPageExplainIT'
```

결과는 `build/review-queue-deep-page-report.md`에 남는다 (커밋 대상 아님 — `build/` 산출물).
10만 건 시드는 `inquiry_review_queue`에 이미 10만 건 이상 있으면 건너뛴다(`@BeforeEach` 확인).
인덱스는 테스트 안에서 `DROP` 후 `finally` 로 반드시 복구하므로 테스트가 끝나면 스키마는
원래대로다.

## claim 필터 추가 후 — 목록 조회 + 만료 스윕 (TRI-93 · D-032)

> 위 측정까지는 **선점(claim — 검토 항목을 "내가 지금 보고 있다"고 표시해 다른 상담원이 동시에
> 손대지 못하게 하는 동작, D-032)** 컬럼이 없던 시점 값이다. `V3__review_queue_claim.sql`이
> `claimed_by`·`claimed_at` 컬럼과 `idx_irq_status_claimed_at (status, claimed_at)` 인덱스를
> 추가하면서, 목록 조회 쿼리에 선점 필터(`claimed_by is null or claimed_by = :agentId or
> claimed_at < :claimExpiryCutoff`)가 새로 붙었다. 이 절은 그 이후 실측이다.

### 핵심 결론

- **목록 조회**: 새 인덱스(`idx_irq_status_claimed_at`)를 안 탄다. 옵티마이저가 기존
  `idx_irq_status_created`를 골랐고, `LIMIT`이 실제 읽기 비용을 억제해준다 — **단, 이건
  통과하는 행이 앞쪽(오래된 순)에 충분할 때 얘기다.** 맨 앞을 의도적으로 선점된 행으로 채워
  재측정하니, `LIMIT 20`을 채우려고 실제로 120건을 읽는 것으로 확인됐다(아래 「분포 편향
  재현 실측」).
- **만료 스윕 UPDATE**: 새 인덱스(`idx_irq_status_claimed_at`)를 정확히 탄다 — 설계 의도와
  실측이 일치.
- **재현 경로 부재**: 이 절의 측정(초기값 + 분포 편향 재현)은 자동화 테스트가 아니라 수동으로
  뜬 값이라 커밋된 재현 경로가 없다 — 다시 확인하려면 이 문서를 보고 손으로 재현해야 한다.
  자동화된 IT 테스트로 옮기는 일은 아직 착수 전이다(아래 「다음 행동」 참조).

### 실측 환경 (2026-08-13)

- **`docker compose`로 띄운 실제 MySQL 8.0 컨테이너**에 mysql
  클라이언트로 직접 접속해 잰 값이다 — 위 측정들과 실행 경로는 다르지만 엔진은 같다(MySQL 8).
  자동화된 테스트로 아직 옮기지 않았다.
- 테이블에는 앞선 10만 건 시드(측정 5ⓕ, `k-explain-seed`)가 그대로 남아 있었다 — 이번 측정
  전용으로 새로 만든 데이터가 아니다. 이 데이터는 선점 기능이 생기기 전에 들어간 것이라
  `claimed_by`가 실질적으로 전부 `NULL`이다. **다른 상담원이 선점한 행이나 만료 경계 조건까지
  골고루 섞인 분포는 이 측정이 검증하지 못한다** — 확인한 것은 "인덱스를 타는가"와 "`LIMIT`이
  실제로 읽는 행 수를 줄여주는가"까지다.

### 스키마 확인

`SHOW INDEX FROM inquiry_review_queue`로 `idx_irq_status_claimed_at (status, claimed_at)`이
`idx_irq_status_created`와 나란히 존재함을 확인했다 (7개 인덱스 중 하나, `Cardinality` 낮음 —
`claimed_by`가 대부분 NULL인 현재 데이터 분포와 일치).

### 목록 조회 — `EXPLAIN` / `EXPLAIN ANALYZE` (rows≈99,640)

```sql
SELECT * FROM inquiry_review_queue
WHERE status = 'PENDING'
  AND (claimed_by IS NULL OR claimed_by = 1 OR claimed_at < '2026-08-13 09:00:00')
ORDER BY created_at ASC LIMIT 20;
```

| type | possible_keys | key | rows(추정) | filtered | Extra |
| --- | --- | --- | --- | --- | --- |
| `ref` | `idx_irq_status_created`, `idx_irq_status_claimed_at` | **`idx_irq_status_created`** | 49,820 | 46.00% | `Using where` |

**해석**

- **새 인덱스(`idx_irq_status_claimed_at`)를 안 탄다** — `possible_keys`엔 뜨지만 옵티마이저가
  기존 `idx_irq_status_created`를 선택했다. 이유는 추정이다:
  - 선점 조건이 `IS NULL / 등치 / 부등호`가 섞인 OR라 단일 인덱스로 커버가 안 된다.
  - `ORDER BY created_at`을 정렬 없이 처리하려면 어차피 `idx_irq_status_created`가 필요하다.
  - `Using filesort`는 없다 — 정렬은 그대로 인덱스가 커버.
- `filtered: 46%`는 **추정치**다(옵티마이저 통계 기반) — 실제 값과 얼마나 다른지는 아래
  「추정치 검증」에서 `COUNT(*)`로 대조했다(결론만 먼저: **크게 틀렸다, 실제는 99%**). 우선
  실제 실행 동작은 `EXPLAIN ANALYZE`로 확인한다:

```
-> Limit: 20 row(s)  (actual time=11.7..12.1 rows=20 loops=1)
   -> Filter: (claimed_by is null or claimed_by = 1 or claimed_at < ...)
        (actual time=11.4..11.8 rows=20 loops=1)
      -> Index lookup on idx_irq_status_created (status='PENDING')
           (actual time=11.1..11.5 rows=20 loops=1)
```

**관찰 결과**

- **매 단계 `rows=20`** — `EXPLAIN`(계획)의 `rows=49,820`이라는 숫자와 달리, **실제로는
  `LIMIT 20`이 채워지는 순간 멈춘다.** 선점 필터를 인덱스가 아니라 서버에서 후처리해도, 이
  쿼리가 PENDING 전체(약 5만 건)를 다 훑는 것은 아니라는 뜻이다. 전체 소요 약 **12ms**.
- **단, 이건 앞부분(오래된 순)에 필터를 통과하는 행이 충분할 때 얘기다.** 이 조건이 깨지면
  어떻게 되는지는 아래 「분포 편향 재현 실측」에서 실제로 만들어서 확인했다 — 결과는 예상대로,
  더 많이 읽는다.

### 앞쪽에 선점된 문의가 몰린 경우 실측 — 맨 앞을 의도적으로 선점된 행으로 채움 (2026-08-13)

> 위 결과 하나만으로는 "운이 좋아서 빨랐던 것"인지 "구조적으로 항상 빠른 것"인지 구분이 안
> 된다. 그래서 **일부러 최악의 조건을 만들어** 재측정했다 — `ORDER BY created_at ASC`가 훑는
> 순서의 맨 앞(가장 오래된 행) 100건을 전부 "다른 상담원(agentId=2)이 선점, 안 만료"(필터를
> 통과 못 해 목록에서 빠져야 하는 행)로 덮어썼다.

```sql
UPDATE inquiry_review_queue
SET claimed_by = 2, claimed_at = NOW()
WHERE id IN (
  SELECT id FROM (
    SELECT id FROM inquiry_review_queue
    WHERE status = 'PENDING'
    ORDER BY created_at ASC
    LIMIT 100
  ) AS t
);
```

같은 목록 쿼리를 다시 `EXPLAIN ANALYZE`:

```
-> Limit: 20 row(s)  (actual time=2.82..2.84 rows=20 loops=1)
   -> Filter: (claimed_by is null or claimed_by = 1 or claimed_at < ...)
        (actual time=2.79..2.8 rows=20 loops=1)
      -> Index lookup on idx_irq_status_created (status='PENDING')
           (actual time=2.28..2.33 rows=120 loops=1)
```

**해석**

- **`Index lookup`이 실제로 120건을 읽었다** — 맨 앞 100건(전부 필터 탈락)을 건너뛴 뒤에야
  통과하는 20건을 채웠다. 앞선 측정의 `rows=20`(즉시 채움)과 대비된다 — **20건 → 120건, 6배.**
- **가설이 실측으로 확인됐다.** "필터를 통과 못 하는 행이 정렬 맨 앞에 몰리면 그만큼 더 읽고
  버린다"는 우려가 추측이 아니라 실제로 재현된다. 몰린 행이 100건이 아니라 500건, 1,000건이면
  그만큼 더 늘어날 것으로 추정된다(그 지점까지는 측정하지 않았다).
- 이 결과는 측정 5ⓕ(TRI-87)의 "깊은 오프셋 비용"과 같은 성격이다 — 다만 오프셋이 아니라
  **"필터 탈락 행이 앞쪽에 뭉쳐 있을 때"** 발생하는 비용이라는 게 다르다.
- **분포 편향 한계는 이걸로 해소됐다** — 처음엔 우연히 통과 행이 앞에 많아서 빨랐던 건지 알 수
  없었는데, 지금은 "최악의 경우 이렇게 된다"는 것 자체는 확인이 끝났다. 다만 **"운영에서 이런
  분포가 실제로 얼마나 자주 나오는가"는 여전히 모른다** — 그건 이 측정의 범위 밖이다.

### 추정치 검증 — `EXPLAIN`의 `rows`·`filtered`를 실제 `COUNT(*)`와 대조 (2026-08-13)

> `EXPLAIN`(plain)의 `rows`·`filtered`는 실행하지 않고 테이블 통계만으로 낸 **추정치**다.
> 위에서 본 `rows=49,820`·`filtered=46%`가 실제와 얼마나 다른지 `COUNT(*)`로 직접 재봤다.
> **선점 만료 기준(5분)처럼 시간 임계값이 낀 조건은, 사람이 대화하며 재는 동안 실제 시간이
> 흘러 처음 심은 "만료되지 않은" 표본이 진짜로 만료돼버리는 함정이 있다** — 그래서 아래 값은 `UPDATE`
> 직후 곧바로 이어서 잰, 시간차가 없는 값이다.

```sql
-- 방금 새로 심은 "타인 선점, 미만료" 1,000건이 실제로 미만료 상태인지 확인
SELECT COUNT(*) FROM inquiry_review_queue
WHERE claimed_by = 2 AND claimed_at >= NOW() - INTERVAL 5 MINUTE;      -- 1,000

SELECT COUNT(*) FROM inquiry_review_queue WHERE status = 'PENDING';    -- PENDING 전체

SELECT COUNT(*) FROM inquiry_review_queue
WHERE status = 'PENDING'
  AND (claimed_by IS NULL OR claimed_by = 1 OR claimed_at < NOW() - INTERVAL 5 MINUTE);  -- 필터 통과
```

| 값 | `EXPLAIN` 추정 | 실제(`COUNT(*)`) | 오차 |
| --- | --- | --- | --- |
| PENDING 전체 행 수 | 49,820 | **100,000** | 약 **2배** 과소추정 |
| 선점 필터 통과율(`filtered`) | 46.00% | **99%**(99,000 / 100,000) | 약 **2배** 이상 과소추정 |

**해석**

- **둘 다 크게 틀렸다.** `rows=49,820`은 실제 PENDING 전체(100,000)의 절반도 안 됐고,
  `filtered=46%`는 실제 통과율(99%)의 절반이 채 안 됐다. 우연이 아니라 **같은 방향(과소추정)**
  으로 일관되게 틀렸다.
- **왜 틀렸는지는 특정하지 않는다** — 이 테이블은 측정 5ⓕ(TRI-87)의 10만 건 일괄 시드 이후
  `ANALYZE TABLE`을 명시적으로 돌린 적이 없어, 통계가 그 시점 이후 갱신 안 됐을 가능성이
  높다는 정황은 있지만, 정확한 원인(샘플링 오차인지 stale 통계인지)까지는 이 측정으로
  가려내지 못한다.
- **결론**: `EXPLAIN`의 `rows`·`filtered`는 "대략의 크기감"을 잡는 용도로만 쓰고, 실제 값이
  중요한 판단(예: 이 필터가 정말 46%만 걸러내는지)에는 **반드시 `COUNT(*)`나
  `EXPLAIN ANALYZE`로 재검증**해야 한다 — 이번 측정 자체가 그 원칙(CLAUDE.md 「AI 검증
  규칙」)이 실행 계획 추정치에도 그대로 적용된다는 실증 사례다.
- **재현 시 주의**: 시간 임계값이 낀 조건(`claimed_at < NOW() - INTERVAL 5 MINUTE` 같은)을
  검증할 때는, "표본을 심는 시점"과 "재는 시점" 사이 실제 경과 시간이 임계값보다 훨씬 짧아야
  한다 — 그렇지 않으면 "미만료"로 심은 표본이 재는 도중 "만료"로 넘어가 결과가 오염된다.

### 만료 스윕 UPDATE — `EXPLAIN` (rows≈99,640)

```sql
UPDATE inquiry_review_queue SET claimed_by = NULL, claimed_at = NULL
WHERE status = 'PENDING' AND claimed_at < '2026-08-13 09:00:00';
```

| type | possible_keys | key | key_len | ref | rows | filtered | Extra |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `range` | `idx_irq_status_created`, `idx_irq_status_claimed_at` | **`idx_irq_status_claimed_at`** | 91 | const,const | 1 | 100.00% | `Using where; Using temporary` |

**해석**

- 새 인덱스를 사용하는 것은 확인됐다. 다만 `EXPLAIN`의 `rows=1`은 실제 개수가 아니라
  예상값이므로, 실제 만료 대상이 몇 건인지는 `COUNT(*)`로 별도 확인해야 한다(아직 안 함).
- `Using temporary`도 확인됐다. 만료된 선점을 찾으면서 동시에 `claimed_at`을 NULL로
  변경하기 때문에 MySQL이 업데이트할 대상 행을 임시로 잡아두는 과정이다. 현재 규모에서는
  문제라고 판단하지 않고 관찰만 한다(D-018과 같은 논리 — 문제로 확인되기 전에는 구조를 안
  바꾼다).
- `EXPLAIN ANALYZE`는 이 문장에서 실행되지 않는다 — MySQL 8은 `UPDATE`/`DELETE` 같은 데이터
  변경 문에 `EXPLAIN ANALYZE`를 지원하지 않는다(`<not executable by iterator executor>`).

### 판정

- **목록 조회는 새 인덱스를 사용하지 않았다.** 대신 앞쪽에 바로 가져올 수 있는 항목이
  충분하면 `LIMIT 20` 덕분에 20건만 읽고 끝나서 크게 느리지 않았다. 하지만 앞쪽 항목
  대부분이 다른 상담원에게 선점되어 있으면 더 많은 행을 읽어야 한다. 실제로 그런
  상황에서는 20건을 가져오기 위해 120건을 읽었다.
- **만료 선점 스윕은 새 인덱스를 사용했다.** 따라서 `(status, claimed_at)` 인덱스를 추가한
  목적대로 동작하는 것을 확인했다.
- **`EXPLAIN`의 `rows`와 `filtered`는 실제 값이 아니라 예상값이다.** 실제 데이터를 확인해
  보니 예상과 실제가 크게 달랐다. 따라서 중요한 성능 판단은 `EXPLAIN`의 예상 숫자만 믿지
  않고 `COUNT(*)`나 `EXPLAIN ANALYZE`로 실제 값을 확인한다.

### 다음 행동 (TODO) — 손으로 확인한 것을 자동화된 테스트로 옮기기

선점된 문의가 앞쪽에 많이 몰리면 조회할 때 더 많은 데이터를 읽어야 한다는 것과, `EXPLAIN`의
예상값이 실제 결과와 다를 수 있다는 것을 직접 확인했다. 현재는 MySQL에 데이터를 직접 넣어서
확인한 상태라 자동으로 다시 확인할 수는 없다. 나중에 테스트 코드를 추가하면
아래 4가지 선점 상황을 자동으로 만들어서 같은 검증을 반복할 수 있다. 다만 이 작업은 현재
범위 밖이라(5일 범위 밖, D-032) 아직 진행하지 않았다.

**검증할 시나리오 4종**

1. **아무도 선점하지 않은 행** (`claimed_by IS NULL`)
   — 목록에 포함되어야 한다 (OR 첫째 조건).

2. **조회하는 상담원 본인이 이미 선점한 행** (`claimed_by = :agentId`)
   — 본인이 작업 중인 항목이므로 목록에 포함되어야 한다 (OR 둘째 조건 — 연장을 위해 계속
   보여야 함).

3. **다른 상담원이 선점했고 아직 만료되지 않은 행**
   (`claimed_by != :agentId`, `claimed_at >= cutoff`)
   — 다른 사람이 작업 중이므로 목록에서 제외되어야 한다.

4. **다른 상담원이 선점했지만 선점 시간이 만료된 행**
   (`claimed_by != :agentId`, `claimed_at < cutoff`)
   — 선점이 만료된 것으로 간주하여 목록에 다시 노출되어야 한다 (OR 셋째 조건).
