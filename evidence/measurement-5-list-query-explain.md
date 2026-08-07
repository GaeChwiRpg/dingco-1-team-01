# 측정 5 — `GET /api/inquiries` 목록 쿼리 EXPLAIN 실측 (TRI-36 · D-045(3))

> MySQL 8.0(Testcontainers, 운영과 같은 엔진)에 문의 **10만 건**을 심고 뜬 **실제 EXPLAIN**이다.
> AI 추정이 아니라 `ListQueryExplainIT` 로 파이프라인을 돌려 얻은 값이며,
> `EXPLAIN_MEASURE=true ./gradlew test --tests '*ListQueryExplainIT'` 로 재현한다 (2026-08-07).
>
> **V3 마이그레이션은 없다.** TRI-36 이 "V3 에 추가"하라던 두 인덱스는 도메인 전환(D-031) 때
> `V2__domain_switch.sql` 의 `inquiries` DDL 에 이미 들어갔다(55·57행). 같은 컬럼 인덱스를 이름만
> 바꿔 또 만들면 중복이라 쓰기 비용만 는다(D-018 계보). 그래서 이 측정은 **이미 있는 인덱스의
> 계획을 전/후로 확인**하는 것이다.
>
> seed 분포: `status` 3종 순환(CLASSIFIED ≈ 1/3 = 33,333건), `customer_id` 5천 명 분산,
> `received_at` 분 단위로 흩음, `current_category` 10종 순환.

## 예상과 실측 대조 (CLAUDE.md 핵심 쿼리 표)

| 케이스 | 예상 | 실측 `type` / `key` / `Extra` | 일치 |
| --- | --- | --- | --- |
| A. status + 정렬 | `(status, received_at)`, 정렬 커버 | `ref` / `idx_inquiries_status_received` / **Backward index scan (filesort 없음)** | ✅ |
| C. status + 기간범위 + 정렬 | `range`, 정렬 커버 | `range` / `idx_inquiries_status_received` / **Using index condition; Backward index scan** | ✅ |
| D. status + category + 정렬 | `(status, current_category, received_at)` | `ref` / `idx_inquiries_status_category_received` / Backward index scan | ✅ |

예상 세 건 모두 실측과 일치했다. `ORDER BY received_at DESC` 는 인덱스의 역방향 스캔
(Backward index scan)으로 처리돼 **filesort 가 없다.**

## 인덱스 전/후 (완료조건: 전후 둘 다 뜬다)

같은 status 정렬 쿼리를, status-선행 인덱스 2개를 **DROP 한 상태**로 다시 떴다.

| | `type` | `key` | `rows` | `Extra` |
| --- | --- | --- | --- | --- |
| **after** (인덱스 있음) | `ref` | idx_inquiries_status_received | 49,740 | Backward index scan |
| **before** (인덱스 없음) | `ALL` | — | 99,481 | **Using where; Using filesort** |

인덱스를 걷어내면 **풀 테이블 스캔 + filesort** 로 떨어진다. 인덱스가 스캔 범위와 정렬을 함께
없애준다는 것이 실측으로 확인된다.

## D-045(3) — 깊은 오프셋: 얕은 페이지 vs 깊은 오프셋

**plain EXPLAIN 은 둘을 구분하지 못한다** — `LIMIT 0,20` 과 `LIMIT 50000,20` 이 **같은 플랜**
(`ref`, rows=49,740)이다. 오프셋 페널티는 계획이 아니라 **"읽고 버리는" 실행 비용**이라
`EXPLAIN ANALYZE` 로만 보인다.

```
E-shallow  LIMIT 0,20
  -> Limit: 20 row(s)  (actual time=0.0714..0.0905 rows=20 loops=1)
     -> Index lookup ... (reverse)  (actual time=0.0627..0.0811 rows=20 loops=1)

E-deep     LIMIT 50000,20
  -> Limit/Offset: 20/50000 row(s)  (actual time=38.8..38.8 rows=0 loops=1)
     -> Index lookup ... (reverse)  (actual time=0.0186..38.1 rows=33333 loops=1)
```

- 얕은 페이지: 실제 **20행** 읽고 **0.09ms**.
- 깊은 오프셋: 실제 **33,333행**(CLASSIFIED 전체)을 읽어 5만 번째까지 건너뛰려다 동나 0행 반환,
  **38.8ms** — 같은 인덱스인데 **~430배**.

**결론**: 인덱스가 있어도 깊은 오프셋은 앞 행을 전부 훑어 버린다. 지금은 관찰만 기록한다
(D-018 — 이득을 재기 전에 커서 페이징 같은 쓰기/구조 비용을 얹지 않는다). 실제 서비스에서 깊은
페이지 요청이 관측되면 커서 페이징을 후속으로 올린다.

## 관찰 발견 2건 (지금 고치지 않음 — 측정 b 로 판단)

**F. `(:param IS NULL OR col=:param)` 래퍼는 리터럴에선 인덱스를 탄다.** TRI-34 목록 쿼리가 치는
이 형태를 리터럴로 뜨면 옵티마이저가 상수 접기로 `status='CLASSIFIED'` 만 남겨 인덱스를 쓴다
(`ref` / idx_inquiries_status_received). **단 Hibernate 는 바인드 파라미터를 쓴다** — 리터럴 접기가
바인드에도 그대로 성립하는지는 이 측정이 증명하지 못한다. **측정 b(목록 p95)에서 실제 쿼리로
확인**한다. 안 타면 필터 조합별 메서드 분리를 검토한다.

**G. 고객 범위 쿼리는 현재 인덱스를 못 탄다 — 풀스캔 + filesort.** `WHERE customer_id=? ORDER BY
received_at` 은 `type=ALL`, `Using where; Using filesort` 다. 기존 인덱스 3개가 전부 `status` ·
`normalized_key` 선행이라 `customer_id` 필터를 못 받는다.

| 쿼리 | `type` | `key` | `Extra` |
| --- | --- | --- | --- |
| G. `customer_id=? ORDER BY received_at` | `ALL` | — | Using where; Using filesort |

이건 **고객 목록(§2, D-038 소유자 강제 경로)의 실제 부하 지점**이다. 후보 대응은
`(customer_id, received_at)` 인덱스이나, **지금 붙이지 않는다** — 쓰기 비용을 얹기 전에 측정 b 로
고객 목록 p95 가 실제로 문제인지 먼저 본다(D-018). 이 발견은 TRI-34 로 피드백하고 측정 b 케이스에
넣는다.
